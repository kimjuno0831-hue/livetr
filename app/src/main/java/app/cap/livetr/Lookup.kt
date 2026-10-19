package app.cap.livetr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 한국어 이름 → 올바른(공식) 중국어 표기 찾기
// 1순위: 위키데이터 (사람들이 정리해둔 표기, 무료)
// 2순위: 제미나이 추측 (앞뒤 자막 + '지금 보는 내용'을 근거로)
object Lookup {
    class Candidate(val zh: String, val note: String, val source: String)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        // 위키미디어는 앱 이름이 담긴 User-Agent를 요구함
        c.setRequestProperty("User-Agent", "LiveTr/0.2 (personal subtitle app; https://github.com/kimjuno0831-hue/livetr)")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // 위키데이터에서 한국어 이름으로 찾고, 연결된 중국어(간체 우선) 이름을 가져옴
    fun wiki(ko: String): List<Candidate> {
        val q = URLEncoder.encode(ko.trim(), "UTF-8")
        val search = JSONObject(
            get("https://www.wikidata.org/w/api.php?action=wbsearchentities&search=$q&language=ko&uselang=ko&type=item&limit=5&format=json")
        ).optJSONArray("search") ?: return emptyList()
        if (search.length() == 0) return emptyList()
        val ids = ArrayList<String>()
        val desc = HashMap<String, String>()
        for (i in 0 until search.length()) {
            val o = search.getJSONObject(i)
            val id = o.optString("id")
            if (id.isEmpty()) continue
            ids.add(id)
            desc[id] = listOf(o.optString("label"), o.optString("description")).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        if (ids.isEmpty()) return emptyList()
        val ents = JSONObject(
            get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids.joinToString("%7C")}&props=labels&languages=zh-hans%7Czh-cn%7Czh%7Czh-hant&format=json")
        ).optJSONObject("entities") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (id in ids) {
            val labels = ents.optJSONObject(id)?.optJSONObject("labels") ?: continue
            val zh = listOf("zh-hans", "zh-cn", "zh", "zh-hant")
                .firstNotNullOfOrNull { labels.optJSONObject(it)?.optString("value")?.takeIf { v -> v.isNotEmpty() } }
                ?: continue
            if (out.none { it.zh == zh }) out.add(Candidate(zh, desc[id] ?: "", "위키"))
        }
        return out
    }

    // 제미나이 추측
    fun guess(ctx: Context, ko: String, recognizedZh: String, context: List<String>): Candidate? {
        if (Prefs.geminiKey(ctx).isEmpty()) return null
        val topic = Prefs.topic(ctx)
        val system = "너는 중국어 고유명사 전문가다. 한국어로 적힌 이름/용어의 공식 중국어(간체) 표기를 찾아라. " +
            "중국어 음성 인식 결과에는 발음이 비슷한 다른 글자로 잘못 적힌 부분이 있을 수 있다. " +
            "반드시 JSON 한 줄로만 답하라: {\"zh\":\"중국어 표기\",\"why\":\"한국어로 짧은 근거\"}"
        val user = buildString {
            if (topic.isNotEmpty()) append("[지금 보는 내용]\n").append(topic).append("\n\n")
            if (context.isNotEmpty()) {
                append("[앞뒤 자막(음성 인식 결과)]\n")
                context.forEach { append(it).append('\n') }
                append('\n')
            }
            if (recognizedZh.isNotEmpty()) append("[인식된 중국어 (틀렸을 수 있음)]\n").append(recognizedZh).append("\n\n")
            append("[한국어 이름]\n").append(ko)
        }
        val tr = Translators(ctx)
        val raw = try { tr.ask(system, user) } finally { tr.close() }
        val json = raw.substring(raw.indexOf('{').coerceAtLeast(0), (raw.lastIndexOf('}') + 1).coerceAtLeast(0))
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val zh = o.optString("zh").trim()
        if (zh.isEmpty()) return null
        return Candidate(zh, o.optString("why"), "제미나이 추측")
    }

    // 용어집 점검용: 여러 줄을 한 번에 제미나이로 확인. 결과: (줄 번호, 올바른 중국어, 근거)
    fun guessBatch(ctx: Context, entries: List<Pair<String, String>>): List<Triple<Int, String, String>> {
        if (Prefs.geminiKey(ctx).isEmpty() || entries.isEmpty()) return emptyList()
        val topic = Prefs.topic(ctx)
        val system = "너는 중국어 고유명사 전문가다. 용어집의 각 줄은 '중국어=한국어' 형식이다. 중국어 쪽은 음성 인식 " +
            "결과에서 따온 것이라 발음이 비슷한 다른 글자로 잘못 적혔을 수 있다. 한국어 이름에 맞는 공식 중국어(간체) " +
            "표기가 다른 줄만 골라 JSON 배열로만 답하라: [{\"i\":줄번호,\"zh\":\"올바른 중국어\",\"why\":\"짧은 근거\"}]. " +
            "이미 맞거나 확신이 없으면 그 줄은 빼라."
        val user = buildString {
            if (topic.isNotEmpty()) append("[지금 보는 내용]\n").append(topic).append("\n\n")
            append("[용어집]\n")
            entries.forEachIndexed { i, (z, k) -> append(i).append(". ").append(z).append('=').append(k).append('\n') }
        }
        val tr = Translators(ctx)
        val raw = try { tr.ask(system, user) } finally { tr.close() }
        val a = raw.indexOf('[')
        val b = raw.lastIndexOf(']')
        if (a < 0 || b <= a) return emptyList()
        val arr = runCatching { JSONArray(raw.substring(a, b + 1)) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Triple<Int, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("i", -1)
            val zh = o.optString("zh").trim()
            if (idx in entries.indices && zh.isNotEmpty() && zh != entries[idx].first) {
                out.add(Triple(idx, zh, o.optString("why")))
            }
        }
        return out
    }

    fun webSearchUrl(ctx: Context, ko: String): String {
        val topic = Prefs.topic(ctx)
        val q = listOf(topic, ko, "중국어 표기").filter { it.isNotEmpty() }.joinToString(" ")
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }
}
