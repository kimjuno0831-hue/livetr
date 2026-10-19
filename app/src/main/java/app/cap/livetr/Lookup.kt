package app.cap.livetr

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 작품 기준으로 중국어 표기 찾기
// 앱이 웹 검색 결과 페이지를 가져오고(무료, 키 없음), 제미나이가 그 글을 읽고 후보와 근거를 뽑는다.
// 제미나이 무료 등급에는 검색 기능이 없어서, 검색은 앱이 하고 제미나이는 읽기만 함.
object Lookup {
    class Candidate(val zh: String, val note: String, val source: String, val evidence: String = "")

    // ── 웹 검색: 결과 페이지를 글자만 남겨서 돌려줌. 여러 곳을 차례로 시도. ──
    private val engines = listOf(
        "https://html.duckduckgo.com/html/?q=",
        "https://www.bing.com/search?setlang=ko&q=",
        "https://lite.duckduckgo.com/lite/?q=",
    )

    private fun fetch(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.instanceFollowRedirects = true
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
        c.setRequestProperty("Accept-Language", "ko,zh-CN;q=0.8,en;q=0.5")
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun toText(html: String): String =
        html.replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h\\d|tr|a)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()

    // 결과: 검색 결과 글 (못 가져오면 빈 문자열)
    fun search(query: String, maxChars: Int = 3500): String {
        val q = URLEncoder.encode(query, "UTF-8")
        for (e in engines) {
            val html = fetch(e + q) ?: continue
            val text = toText(html)
            // 결과가 거의 없거나 '로봇 확인' 같은 화면이면 다음 곳으로
            if (text.length < 300 || text.contains("captcha", true) || text.contains("unusual traffic", true)) continue
            return text.take(maxChars)
        }
        return ""
    }

    private fun json(raw: String, open: Char, close: Char): String? {
        val a = raw.indexOf(open)
        val b = raw.lastIndexOf(close)
        return if (a >= 0 && b > a) raw.substring(a, b + 1) else null
    }

    private fun ask(ctx: Context, system: String, user: String): String {
        val tr = Translators(ctx)
        return try { tr.ask(system, user) } finally { tr.close() }
    }

    private const val CAUTION = "중요: 이건 위 작품 속 등장인물·지명·용어일 가능성이 높다. 같은 이름의 실존 인물, " +
        "역사 인물, 문학 작품, 일반 단어와 헷갈리지 마라. 중국어 표기는 그 작품의 중국어판(간체)에서 실제로 쓰는 것으로."

    // ── (가) 한 이름 찾기 ──
    fun find(ctx: Context, ko: String, recognizedZh: String, around: List<String>): List<Candidate> {
        if (Prefs.geminiKey(ctx).isEmpty()) throw RuntimeException("제미나이 키가 필요해요")
        val works = Prefs.topic(ctx)
        val web = StringBuilder()
        val queries = if (works.isNotEmpty()) {
            listOf("$works $ko 중국어", "$works $ko 中文名")
        } else {
            listOf("$ko 중국어 이름")
        }
        for (q in queries) {
            val r = search(q)
            if (r.isNotEmpty()) web.append("### 검색어: ").append(q).append('\n').append(r).append("\n\n")
        }
        val system = "너는 중국어-한국어 고유명사 전문가다. 사용자가 보는 작품의 중국어 영상에 나온 이름/용어의 " +
            "중국어(간체) 표기를 찾는다. 웹 검색 결과가 주어지면 반드시 그것을 우선 근거로 삼고, " +
            "근거 문장을 결과에서 그대로 짧게 인용하라. 검색 결과에 근거가 없으면 네 지식으로 추측하되 evidence는 빈 문자열로 둬라. " +
            CAUTION + "\n후보는 가능성 높은 순서로 최대 3개, JSON 배열로만 답하라: " +
            "[{\"zh\":\"중국어\",\"why\":\"어떤 작품의 무엇인지 한국어로 짧게\",\"evidence\":\"근거 인용 또는 빈 문자열\"}]"
        val user = buildString {
            append("[지금 보는 작품]\n").append(works.ifEmpty { "(모름)" }).append("\n\n")
            if (around.isNotEmpty()) {
                append("[앞뒤 자막(음성 인식 결과, 틀린 글자가 있을 수 있음)]\n")
                around.forEach { append(it).append('\n') }
                append('\n')
            }
            if (recognizedZh.isNotEmpty()) append("[폰이 받아 적은 중국어(틀렸을 수 있음)]\n").append(recognizedZh).append("\n\n")
            append("[찾을 한국어 이름]\n").append(ko).append("\n\n")
            append("[웹 검색 결과]\n").append(web.ifEmpty { "(검색 결과를 가져오지 못함)" })
        }
        val arr = json(ask(ctx, system, user), '[', ']')?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val zh = o.optString("zh").trim()
            if (zh.isEmpty() || out.any { it.zh == zh }) continue
            val ev = o.optString("evidence").trim()
            out.add(Candidate(zh, o.optString("why"), if (ev.isNotEmpty()) "웹 검색 근거" else "제미나이 추측", ev))
        }
        return out
    }

    // ── (나) 작품 용어집 초안 만들기: (한국어, 중국어, 설명) ──
    fun buildWorkGlossary(ctx: Context, work: String): List<Triple<String, String, String>> {
        if (Prefs.geminiKey(ctx).isEmpty()) throw RuntimeException("제미나이 키가 필요해요")
        val web = StringBuilder()
        for (q in listOf("$work 등장인물 중국어 이름", "$work 角色 中文名", "$work 용어 중국어")) {
            val r = search(q, 3000)
            if (r.isNotEmpty()) web.append("### 검색어: ").append(q).append('\n').append(r).append("\n\n")
        }
        val system = "너는 중국어-한국어 고유명사 전문가다. 한국인이 이 작품의 중국어 영상을 볼 때 쓸 용어집 초안을 만든다. " +
            "주요 등장인물, 지명, 조직, 자주 나오는 고유 용어를 골라라. 중국어는 그 작품 중국어판(간체)의 실제 표기, " +
            "한국어는 그 작품 한국어판의 실제 표기로. 웹 검색 결과를 우선 근거로 삼고, 확신 없는 항목은 빼라. " +
            "최대 40개, JSON 배열로만 답하라: [{\"ko\":\"한국어\",\"zh\":\"중국어\",\"note\":\"짧은 설명(인물/지명 등)\"}]"
        val user = "[작품]\n$work\n\n[웹 검색 결과]\n" + web.ifEmpty { "(검색 결과를 가져오지 못함 — 네 지식으로만, 확신 있는 것만)" }
        val arr = json(ask(ctx, system, user), '[', ']')?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        val out = ArrayList<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("ko").trim()
            val z = o.optString("zh").trim()
            if (k.isEmpty() || z.isEmpty() || z.contains('=') || out.any { it.second == z }) continue
            out.add(Triple(k, z, o.optString("note").trim()))
        }
        return out
    }

    // ── 용어집 점검: 한 용어집의 항목들을 작품 기준으로 확인. 결과: (줄 번호, 올바른 중국어, 근거) ──
    fun check(ctx: Context, work: String?, entries: List<Pair<String, String>>): List<Triple<Int, String, String>> {
        if (Prefs.geminiKey(ctx).isEmpty() || entries.isEmpty()) return emptyList()
        val ctxWork = work ?: Prefs.topic(ctx)
        val web = if (!work.isNullOrEmpty()) search("$work 등장인물 중국어 이름", 3000) else ""
        val system = "너는 중국어-한국어 고유명사 전문가다. 용어집의 각 줄은 '중국어=한국어'이고, 중국어 쪽은 음성 인식 결과에서 " +
            "따온 것이라 발음이 비슷한 다른 글자로 잘못 적혔을 수 있다. 한국어 이름에 맞는 그 작품 중국어판(간체)의 " +
            "실제 표기가 다른 줄만 골라라. " + CAUTION + " 이미 맞거나 확신이 없으면 빼라. " +
            "JSON 배열로만 답하라: [{\"i\":줄번호,\"zh\":\"올바른 중국어\",\"why\":\"짧은 근거\"}]"
        val user = buildString {
            append("[작품]\n").append(ctxWork.ifEmpty { "(모름)" }).append("\n\n[용어집]\n")
            entries.forEachIndexed { i, (z, k) -> append(i).append(". ").append(z).append('=').append(k).append('\n') }
            if (web.isNotEmpty()) append("\n[웹 검색 결과]\n").append(web)
        }
        val arr = json(ask(ctx, system, user), '[', ']')?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        val out = ArrayList<Triple<Int, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("i", -1)
            val zh = o.optString("zh").trim()
            if (idx in entries.indices && zh.isNotEmpty() && zh != entries[idx].first && !zh.contains('=')) {
                out.add(Triple(idx, zh, o.optString("why")))
            }
        }
        return out
    }

    fun webSearchUrl(ctx: Context, ko: String): String {
        val q = listOf(Prefs.topic(ctx), ko, "중국어 이름").filter { it.isNotEmpty() }.joinToString(" ")
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }
}
