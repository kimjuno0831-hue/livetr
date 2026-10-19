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
    companion object {
        // 이 모델이 '생각 정도' 설정을 거부하면 다음부턴 빼고 보냄 (앱이 켜져 있는 동안 기억)
        @Volatile private var thinkingRejected = HashSet<String>()
    }

    private var mlkit: Translator? = null

    // onPartial: 제미나이 번역이 글자 단위로 흘러들어올 때마다 지금까지의 번역을 알려줌
    fun translate(
        zh: String,
        history: List<String>,
        onPartial: ((String) -> Unit)? = null,
    ): Pair<String, String> {
        val engine = Prefs.engine(ctx)
        val gl = Prefs.glossary(ctx)
        // 통역 모드가 실패해서 기본 방식으로 넘어왔을 때도 같은 키로 제미나이 번역
        if (engine == Prefs.ENGINE_GEMINI || engine == Prefs.ENGINE_LIVE) {
            try {
                return Pair(geminiTranslate(zh, history, gl, onPartial), "제미나이")
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

    // 말하는 도중 미리 보기용 빠른 번역. 한도 걱정 없는 구글 번역만 씀 (기기번역 모드면 안 함)
    fun preview(zh: String): String? {
        if (Prefs.engine(ctx) == Prefs.ENGINE_MLKIT) return null
        return runCatching { google(applyGlossary(zh, Prefs.glossary(ctx))) }.getOrNull()
    }

    // 번역 고치기 화면용: 번역문의 어떤 단어가 원문의 어느 중국어에서 나왔는지 찾기
    fun findSource(zh: String, ko: String, word: String): String {
        val system = "너는 중국어-한국어 번역 정렬 도우미다. 주어진 중국어 원문과 한국어 번역에서, " +
            "지정한 한국어 단어가 번역된 원문 부분을 찾아라. 원문에 실제로 있는 연속된 글자를 그대로, " +
            "그 부분만 출력하라. 설명이나 따옴표는 쓰지 마라."
        val user = "[중국어 원문]\n$zh\n\n[한국어 번역]\n$ko\n\n[찾을 한국어 단어]\n$word"
        return gemini(system, user, false, null).trim().trim('"', '“', '”', '「', '」')
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
                val t0 = System.currentTimeMillis()
                sb.append(runCatching { geminiTranslate(zh, emptyList(), gl, null) }.getOrElse { "실패 - ${it.message}" })
                sb.append("  (${System.currentTimeMillis() - t0}ms)")
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

    private fun geminiTranslate(
        zh: String,
        history: List<String>,
        gl: List<Pair<String, String>>,
        onPartial: ((String) -> Unit)?,
    ): String {
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
        return gemini(system, user, onPartial != null, onPartial).trim().trim('"', '“', '”')
    }

    // 제미나이 호출. stream=true면 글자가 나오는 대로 onPartial로 알려줌.
    // '생각'을 가장 낮게 해서 빨리 답하게 함 (모델이 거부하면 빼고 다시 시도).
    private fun gemini(system: String, user: String, stream: Boolean, onPartial: ((String) -> Unit)?): String {
        val key = Prefs.geminiKey(ctx)
        if (key.isEmpty()) throw RuntimeException("API 키가 비어 있어요")
        val model = Prefs.geminiModel(ctx)
        val withThinking = !thinkingRejected.contains(model)
        return try {
            geminiOnce(key, model, system, user, stream, onPartial, withThinking)
        } catch (e: ThinkingRejected) {
            thinkingRejected = HashSet(thinkingRejected).apply { add(model) }
            geminiOnce(key, model, system, user, stream, onPartial, false)
        }
    }

    private class ThinkingRejected : Exception()

    private fun geminiOnce(
        key: String,
        model: String,
        system: String,
        user: String,
        stream: Boolean,
        onPartial: ((String) -> Unit)?,
        withThinking: Boolean,
    ): String {
        val gen = JSONObject().put("temperature", 0.2)
        if (withThinking) {
            // Flash-Lite·3.6 Flash는 'minimal'이 가장 낮고, 3.8 Flash는 'low'가 가장 낮음
            val level = if (model.contains("3.8") && !model.contains("lite")) "low" else "minimal"
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", level))
        }
        val req = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", gen)

        val endpoint = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:$endpoint")
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
                if (code == 400 && withThinking && msg.lowercase().contains("think")) throw ThinkingRejected()
                val hint = when (code) {
                    429 -> " (무료 한도 초과 - 잠시 후 다시)"
                    400, 403 -> " (키 또는 모델 이름 확인)"
                    404 -> " (모델 이름이 틀렸어요)"
                    else -> ""
                }
                throw RuntimeException("HTTP $code$hint $msg")
            }
            val sb = StringBuilder()
            if (stream) {
                // 서버가 "data: {...}" 줄을 조금씩 보내줌
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val json = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
                        val added = textOf(json)
                        if (added.isNotEmpty()) {
                            sb.append(added)
                            onPartial?.invoke(sb.toString().trim())
                        }
                    }
                }
            } else {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                sb.append(textOf(JSONObject(body)))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw RuntimeException("빈 결과")
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun textOf(o: JSONObject): String {
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString()
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
