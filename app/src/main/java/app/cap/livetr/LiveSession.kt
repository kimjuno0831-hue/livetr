package app.cap.livetr

import android.os.SystemClock
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// 제미나이 통역 모드 (gemini-3.5-live-translate-preview)
// 소리를 그대로 제미나이로 보내면, 번역된 음성 + 원문/번역 자막 글씨가 조금씩 돌아온다.
// 음성은 버리고 글씨만 자막 창에 쌓는다.
class LiveSession(
    private val key: String,
    private val overlay: Overlay?,
    private val onShown: () -> Unit,
) {
    companion object {
        const val MODEL = "gemini-3.5-live-translate-preview"
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val END = Regex("[.!?。！？…]\\s*$")
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false
        private set
    @Volatile var closedReason: String? = null   // null 이면 아직 살아 있음
        private set

    // 보낼 소리 모으기 (100ms = 1600개)
    private val pcm = ShortArray(1600)
    private var pcmN = 0

    // 지금 쌓고 있는 자막 한 줄
    private val lock = Any()
    private var curId: Long? = null
    private val curKo = StringBuilder()
    private val curZh = StringBuilder()
    @Volatile private var lastOut = 0L
    @Volatile private var lastZh = 0L

    fun connect() {
        closedReason = null
        connected = false
        val req = Request.Builder().url("$URL?key=$key").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put(
                    "setup", JSONObject()
                        .put("model", "models/$MODEL")
                        .put(
                            "generationConfig", JSONObject()
                                .put("responseModalities", JSONArray().put("AUDIO"))
                                .put("inputAudioTranscription", JSONObject())
                                .put("outputAudioTranscription", JSONObject())
                                .put(
                                    "translationConfig", JSONObject()
                                        .put("targetLanguageCode", "ko")
                                        // 이미 한국어인 말은 번역하지 않고 조용히 넘김
                                        .put("echoTargetLanguage", false)
                                )
                        )
                )
                webSocket.send(setup.toString())
                connected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish("연결 종료 ($code${if (reason.isNotEmpty()) ", $reason" else ""})")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish("연결 종료 ($code${if (reason.isNotEmpty()) ", $reason" else ""})")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = runCatching { response?.body?.string() }.getOrNull()
                val msg = runCatching { JSONObject(body ?: "").getJSONObject("error").getString("message") }
                    .getOrNull() ?: t.message ?: "알 수 없는 오류"
                finish("연결 실패: ${response?.code ?: ""} $msg".trim())
            }
        })
    }

    private fun finish(reason: String) {
        connected = false
        if (closedReason == null) closedReason = reason
        flush()
    }

    // 16kHz 모노 소리 조각을 받아 100ms씩 보냄
    fun send(samples: FloatArray) {
        val w = ws ?: return
        if (!connected) return
        for (f in samples) {
            val v = (f * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[pcmN++] = v.toShort()
            if (pcmN == pcm.size) {
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    val s = pcm[i].toInt()
                    bytes[i * 2] = (s and 0xff).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
                }
                pcmN = 0
                val msg = JSONObject().put(
                    "realtimeInput", JSONObject().put(
                        "audio", JSONObject()
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            .put("mimeType", "audio/pcm;rate=16000")
                    )
                )
                w.send(msg.toString())
            }
        }
    }

    private fun handle(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        o.optJSONObject("error")?.let {
            finish("오류: ${it.optString("message")}")
            return
        }
        val sc = o.optJSONObject("serverContent") ?: return
        sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) addZh(it) }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) addKo(it) }
        if (sc.optBoolean("turnComplete", false)) flush()
    }

    private fun addZh(t: String) {
        synchronized(lock) {
            lastZh = SystemClock.uptimeMillis()
            curZh.append(t)
            curId?.let { overlay?.setOrig(it, curZh.toString().trim()) }
        }
    }

    private fun addKo(t: String) {
        synchronized(lock) {
            lastOut = SystemClock.uptimeMillis()
            if (curId == null) {
                curId = overlay?.add(curZh.toString().trim())
                onShown()
            }
            curKo.append(t)
            val ko = curKo.toString().trim()
            curId?.let { overlay?.update(it, ko, "") }
            // 문장이 끝났거나 자막 한 칸(32자)을 넘으면 다음 줄로
            if (END.containsMatchIn(ko) || ko.length >= Overlay.MAX_CHUNK) flushLocked()
        }
    }

    // 번역 글씨가 잠깐 멈추면 지금 줄을 마감 (서비스에서 주기적으로 부름)
    fun tick() {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            if (curId != null && now - lastOut > 1500) flushLocked()
            // 번역이 안 나온 원문(예: 한국어라 건너뛴 말)이 쌓이지 않게 정리
            if (curId == null && curZh.isNotEmpty() && now - lastZh > 3000) curZh.setLength(0)
        }
    }

    private fun flush() = synchronized(lock) { flushLocked() }

    private fun flushLocked() {
        curId = null
        curKo.setLength(0)
        curZh.setLength(0)
    }

    fun close() {
        connected = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        client.dispatcher.executorService.shutdown()
    }
}
