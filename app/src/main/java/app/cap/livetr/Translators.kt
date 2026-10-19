package app.cap.livetr

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 번역 담당. 결과: (번역문, 실제로 쓴 번역기 이름)
class Translators(private val ctx: Context) {
    private var mlkit: Translator? = null

    fun translate(zh: String, history: List<String>): Pair<String, String> {
        val engine = Prefs.engine(ctx)
        val gl = Prefs.glossary(ctx)
        // 통역 모드가 실패해서 기본 방식으로 넘어왔을 때도 같은 키로 제미나이 번역
        if (engine == Prefs.ENGINE_GEMINI || engine == Prefs.ENGINE_LIVE) {
            try {
                return Pair(gemini(zh, history, gl), "제미나이")
            } catch (_: Exception) {
                // 실패하면 구글 번역으로 대신함
            }
        }
        if (engine == Prefs.ENGINE_GEMINI || engine == Prefs.ENGINE_LIVE || engine == Prefs.ENGINE_GOOGLE) {
            try {
                return Pair(google(applyGlossary(zh, gl)), "구글")
            } catch (_: Exception) {
                // 인터넷 문제 등 → 기기 안 번역으로 대신함
            }
        }
        return Pair(mlkit(applyGlossary(zh, gl)), "기기번역")
    }

    // 설정 화면의 [번역 테스트]용: 실패하면 이유를 그대로 보여줌
    fun test(zh: String): String {
        val gl = Prefs.glossary(ctx)
        val sb = StringBuilder()
        when (Prefs.engine(ctx)) {
            Prefs.ENGINE_GEMINI, Prefs.ENGINE_LIVE -> {
                if (Prefs.engine(ctx) == Prefs.ENGINE_LIVE) {
                    sb.append("(제미나이 통역은 소리로만 동작해서 여기선 키만 확인해요. 자막을 시작해서 확인하세요.)\n")
                }
                sb.append("제미나이: ")
                sb.append(runCatching { gemini(zh, emptyList(), gl) }.getOrElse { "실패 - ${it.message}" })
                sb.append("\n구글(대체용): ")
                sb.append(runCatching { google(applyGlossary(zh, gl)) }.getOrElse { "실패 - ${it.message}" })
            }
            Prefs.ENGINE_GOOGLE -> {
                sb.append("구글: ")
                sb.append(runCatching { google(applyGlossary(zh, gl)) }.getOrElse { "실패 - ${it.message}" })
            }
            else -> {
                sb.append("기기번역: ")
                sb.append(runCatching { mlkit(applyGlossary(zh, gl)) }.getOrElse { "실패 - ${it.message}" })
            }
        }
        return sb.toString()
    }

    // 구글/기기번역용: 번역 전에 용어집 단어를 한국어로 미리 바꿔둠
    private fun applyGlossary(zh: String, gl: List<Pair<String, String>>): String {
        var s = zh
        for ((k, v) in gl) s = s.replace(k, " $v ")
        return s.replace(Regex(" {2,}"), " ").trim()
    }

    private fun google(text: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=zh-CN&tl=ko&dt=t&q=$q"
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val code = c.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val segs = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val part = segs.optJSONArray(i)?.opt(0)
                if (part is String) sb.append(part)
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw RuntimeException("빈 결과")
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun gemini(zh: String, history: List<String>, gl: List<Pair<String, String>>): String {
        val key = Prefs.geminiKey(ctx)
        if (key.isEmpty()) throw RuntimeException("API 키가 비어 있어요")
        val model = Prefs.geminiModel(ctx)

        val context = history.joinToString("\n") + "\n" + zh
        val used = gl.filter { context.contains(it.first) }
        val system = buildString {
            append("너는 중국어 영상의 실시간 자막 번역가다. 입력은 음성 인식 결과라서 ")
            append("오탈자나 발음이 비슷한 글자로 잘못 인식된 부분이 있을 수 있다. 앞 문맥을 참고해서 ")
            append("말하는 사람이 실제로 했을 법한 의미로, 자연스러운 한국어 구어체 자막으로 번역하라. ")
            append("번역문만 출력하고 설명, 따옴표, 원문, 앞 문맥의 번역은 절대 쓰지 마라.")
            if (used.isNotEmpty()) {
                append("\n\n[용어집 - 아래 단어는 반드시 이렇게 번역]\n")
                used.forEach { append(it.first).append(" → ").append(it.second).append('\n') }
            }
        }
        val user = buildString {
            if (history.isNotEmpty()) {
                append("[앞 문맥(번역하지 말 것)]\n")
                history.forEach { append(it).append('\n') }
                append('\n')
            }
            append("[번역할 문장]\n").append(zh)
        }
        val req = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.2))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-goog-api-key", key)
        try {
            c.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val msg = runCatching {
                    JSONObject(err).getJSONObject("error").getString("message")
                }.getOrDefault(err.take(150))
                val hint = when (code) {
                    429 -> " (무료 한도 초과 - 잠시 후 다시)"
                    400, 403 -> " (키 또는 모델 이름 확인)"
                    404 -> " (모델 이름이 틀렸어요)"
                    else -> ""
                }
                throw RuntimeException("HTTP $code$hint $msg")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
            }
            val out = sb.toString().trim().trim('"', '“', '”')
            if (out.isEmpty()) throw RuntimeException("빈 결과")
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun mlkit(zh: String): String {
        val t = mlkit ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        ).also {
            Tasks.await(it.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            mlkit = it
        }
        return Tasks.await(t.translate(zh))
    }

    fun close() {
        mlkit?.close()
        mlkit = null
    }
}
