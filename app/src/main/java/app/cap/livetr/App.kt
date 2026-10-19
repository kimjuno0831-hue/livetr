package app.cap.livetr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

// ───────────────────────── 설정값 저장소 ─────────────────────────
object Prefs {
    const val ENGINE_GOOGLE = "google"
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_MLKIT = "mlkit"
    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("livetr", Context.MODE_PRIVATE)

    fun engine(ctx: Context) = get(ctx).getString("engine", ENGINE_GOOGLE) ?: ENGINE_GOOGLE
    fun geminiKey(ctx: Context) = (get(ctx).getString("geminiKey", "") ?: "").trim()
    fun geminiModel(ctx: Context): String {
        val m = (get(ctx).getString("geminiModel", "") ?: "").trim()
        return m.ifEmpty { DEFAULT_MODEL }
    }
    fun showOrig(ctx: Context) = get(ctx).getBoolean("showOrig", true)
    fun locked(ctx: Context) = get(ctx).getBoolean("locked", false)
    fun textSize(ctx: Context) = get(ctx).getInt("textSize", 19)
}

// ───────────────────────── 번역 담당 ─────────────────────────
// 결과: (번역문, 실제로 쓴 번역기 이름)
class Translators(private val ctx: Context) {
    private var mlkit: Translator? = null

    fun translate(zh: String, history: List<String>): Pair<String, String> {
        val engine = Prefs.engine(ctx)
        if (engine == Prefs.ENGINE_GEMINI) {
            try {
                return Pair(gemini(zh, history), "제미나이")
            } catch (_: Exception) {
                // 실패하면 구글 번역으로 대신함
            }
        }
        if (engine == Prefs.ENGINE_GEMINI || engine == Prefs.ENGINE_GOOGLE) {
            try {
                return Pair(google(zh), "구글")
            } catch (_: Exception) {
                // 인터넷 문제 등 → 기기 안 번역으로 대신함
            }
        }
        return Pair(mlkit(zh), "기기번역")
    }

    // 설정 화면의 [번역 테스트]용: 실패하면 이유를 그대로 보여줌
    fun test(zh: String): String {
        val sb = StringBuilder()
        when (Prefs.engine(ctx)) {
            Prefs.ENGINE_GEMINI -> {
                sb.append("제미나이: ")
                sb.append(runCatching { gemini(zh, emptyList()) }.getOrElse { "실패 - ${it.message}" })
                sb.append("\n구글(대체용): ")
                sb.append(runCatching { google(zh) }.getOrElse { "실패 - ${it.message}" })
            }
            Prefs.ENGINE_GOOGLE -> {
                sb.append("구글: ")
                sb.append(runCatching { google(zh) }.getOrElse { "실패 - ${it.message}" })
            }
            else -> {
                sb.append("기기번역: ")
                sb.append(runCatching { mlkit(zh) }.getOrElse { "실패 - ${it.message}" })
            }
        }
        return sb.toString()
    }

    private fun google(zh: String): String {
        val q = URLEncoder.encode(zh, "UTF-8")
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

    private fun gemini(zh: String, history: List<String>): String {
        val key = Prefs.geminiKey(ctx)
        if (key.isEmpty()) throw RuntimeException("API 키가 비어 있어요")
        val model = Prefs.geminiModel(ctx)

        val system = "너는 중국어 영상의 실시간 자막 번역가다. 입력은 음성 인식 결과라서 " +
            "오탈자나 발음이 비슷한 글자로 잘못 인식된 부분이 있을 수 있다. 앞 문맥을 참고해서 " +
            "말하는 사람이 실제로 했을 법한 의미로, 자연스러운 한국어 구어체 자막으로 번역하라. " +
            "번역문만 출력하고 설명, 따옴표, 원문, 앞 문맥의 번역은 절대 쓰지 마라."
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

// ───────────────────────── 화면(앱 첫 화면) ─────────────────────────
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var engineGroup: RadioGroup
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var geminiArea: LinearLayout
    private val reqPerm = 1
    private val reqCapture = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs.get(this)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun title(t: String) = TextView(this).apply {
            text = t
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (18 * d).toInt(), 0, (4 * d).toInt())
        }
        fun note(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.GRAY)
        }

        // ── 번역기 선택
        col.addView(title("번역기"))
        engineGroup = RadioGroup(this)
        val rbGoogle = RadioButton(this).apply { id = 101; text = "구글 번역 (무료, 키 필요 없음)" }
        val rbGemini = RadioButton(this).apply { id = 102; text = "제미나이 AI (무료 키 필요, 가장 자연스러움)" }
        val rbMlkit = RadioButton(this).apply { id = 103; text = "기기 안 번역 (인터넷 없이, 품질 낮음)" }
        engineGroup.addView(rbGoogle)
        engineGroup.addView(rbGemini)
        engineGroup.addView(rbMlkit)
        engineGroup.check(
            when (Prefs.engine(this)) {
                Prefs.ENGINE_GEMINI -> 102
                Prefs.ENGINE_MLKIT -> 103
                else -> 101
            }
        )
        col.addView(engineGroup)

        geminiArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        geminiArea.addView(note("제미나이 API 키 (aistudio.google.com/apikey 에서 무료 발급)"))
        keyBox = EditText(this).apply {
            hint = "AIza로 시작하는 키 붙여넣기"
            setText(Prefs.geminiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        geminiArea.addView(keyBox)
        geminiArea.addView(note("모델 이름 (보통 그대로 두세요)"))
        modelBox = EditText(this).apply {
            setText(Prefs.geminiModel(this@MainActivity))
            setSingleLine(true)
        }
        geminiArea.addView(modelBox)
        geminiArea.addView(note("제미나이가 실패하거나 한도를 넘으면 자동으로 구글 번역으로 바꿔서 보여줘요."))
        col.addView(geminiArea)
        geminiArea.visibility = if (engineGroup.checkedRadioButtonId == 102) View.VISIBLE else View.GONE
        engineGroup.setOnCheckedChangeListener { _, id ->
            geminiArea.visibility = if (id == 102) View.VISIBLE else View.GONE
            save()
        }

        val testBtn = Button(this).apply {
            text = "번역 테스트 (제대로 번역되는지 확인)"
            setOnClickListener { runTest() }
        }
        col.addView(testBtn)

        // ── 자막 모양
        col.addView(title("자막 모양"))
        val cbOrig = CheckBox(this).apply {
            text = "중국어 원문도 같이 보기"
            isChecked = Prefs.showOrig(this@MainActivity)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean("showOrig", v).apply() }
        }
        col.addView(cbOrig)
        val cbLock = CheckBox(this).apply {
            text = "자막 위치 잠금 (켜면 자막을 눌러도 영상이 조작돼요)"
            isChecked = Prefs.locked(this@MainActivity)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean("locked", v).apply() }
        }
        col.addView(cbLock)
        val sizeLabel = note("글자 크기: ${Prefs.textSize(this)}")
        col.addView(sizeLabel)
        col.addView(SeekBar(this).apply {
            max = 18
            progress = Prefs.textSize(this@MainActivity) - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    sizeLabel.text = "글자 크기: ${v + 12}"
                    p.edit().putInt("textSize", v + 12).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        col.addView(note(
            "· 자막을 손가락으로 끌면 위치를 옮길 수 있어요.\n" +
                "· 자막을 짧게 톡 누르면 원문 표시가 켜졌다 꺼져요.\n" +
                "· 설정은 자막이 켜져 있어도 바로 반영돼요 (번역기 바꾸기 포함)."
        ))

        // ── 시작 / 중지
        col.addView(title("실행"))
        status = TextView(this).apply {
            textSize = 15f
            text = "[자막 시작]을 누르고 권한을 모두 허용한 뒤, 빌리빌리에서 영상을 재생하세요."
        }
        col.addView(status)
        col.addView(Button(this).apply {
            text = "자막 시작"
            setOnClickListener { save(); begin() }
        })
        col.addView(Button(this).apply {
            text = "자막 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "중지했어요."
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        val engine = when (engineGroup.checkedRadioButtonId) {
            102 -> Prefs.ENGINE_GEMINI
            103 -> Prefs.ENGINE_MLKIT
            else -> Prefs.ENGINE_GOOGLE
        }
        Prefs.get(this).edit()
            .putString("engine", engine)
            .putString("geminiKey", keyBox.text.toString().trim())
            .putString("geminiModel", modelBox.text.toString().trim())
            .apply()
    }

    private fun runTest() {
        save()
        status.text = "번역 테스트 중…"
        val sample = "大家好，今天我们来聊一聊这个游戏的新版本，更新内容还挺多的。"
        thread {
            val tr = Translators(applicationContext)
            val res = try {
                tr.test(sample)
            } catch (e: Exception) {
                "오류: ${e.message}"
            } finally {
                tr.close()
            }
            runOnUiThread {
                status.text = "원문: $sample\n(뜻: 여러분 안녕하세요, 오늘은 이 게임의 새 버전에 대해 얘기해볼게요. " +
                    "업데이트 내용이 꽤 많아요.)\n\n$res"
            }
        }
    }

    private fun begin() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), reqPerm)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시'를 허용하고 돌아와서 다시 눌러주세요", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            status.text = "오디오 녹음 권한이 필요해요. (마이크가 아니라 소리 캡처용이에요)"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                status.text = "시작했어요! 빌리빌리로 가서 영상을 재생하세요."
                moveTaskToBack(true)
            } else {
                status.text = "화면/소리 캡처 허용이 취소됐어요."
            }
        }
    }
}

// ───────────────────── 소리 잡기 → 글자 → 번역 담당 ─────────────────────
class CaptureService : Service() {
    companion object {
        const val CH = "livetr"
        const val ACTION_STOP = "app.cap.livetr.STOP"
        const val SR = 16000
    }

    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var overlay: Overlay? = null
    @Volatile
    private var running = false
    private val queue = LinkedBlockingQueue<FloatArray>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startAsForeground()

        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || code != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                ui.post { stopSelf() }
            }
        }, ui)

        overlay = Overlay(this).also { it.attach() }
        running = true
        thread(name = "asr-worker") { asrLoop() }
        thread(name = "capture") { captureLoop() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "실시간 번역", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "중지", stopPi
        ).build()
        val n = Notification.Builder(this, CH)
            .setContentTitle("실시간 번역 실행 중")
            .setContentText("끄려면 '중지'를 누르세요")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(action)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // 1) 재생 중인 소리를 잡아서, 말하는 구간만 잘라 큐에 넣는다
    private fun captureLoop() {
        var vad: Vad? = null
        try {
            val v = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.35f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 8.0f,
                    ),
                    sampleRate = SR,
                    numThreads = 1,
                    provider = "cpu",
                )
            )
            vad = v

            val proj = projection ?: return
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, 48000 * 4))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            record = rec
            rec.startRecording()

            // 48kHz 스테레오 → 16kHz 모노 (6개 값을 평균내서 1개로)
            val shorts = ShortArray(3840)
            val win = FloatArray(512)
            var wi = 0
            var acc = 0f
            var cnt = 0
            while (running) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) {
                    Thread.sleep(10)
                    continue
                }
                for (i in 0 until n) {
                    acc += shorts[i]
                    cnt++
                    if (cnt == 6) {
                        win[wi++] = acc / (6f * 32768f)
                        acc = 0f
                        cnt = 0
                        if (wi == 512) {
                            v.acceptWaveform(win.copyOf())
                            wi = 0
                            while (!v.empty()) {
                                val seg = v.front()
                                v.pop()
                                queue.offer(seg.samples)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            overlay?.status("오류(소리 캡처): ${e.message}")
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            record = null
            vad?.release()
        }
    }

    // 2) 잘라낸 말소리 → 중국어 글자 → 한국어 번역 → 자막
    private fun asrLoop() {
        var recognizer: OfflineRecognizer? = null
        val translators = Translators(applicationContext)
        try {
            overlay?.status("중국어 인식 모델 불러오는 중…")
            val rec = OfflineRecognizer(
                assetManager = assets,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "model.int8.onnx",
                            language = "zh",
                            useInverseTextNormalization = true,
                        ),
                        tokens = "tokens.txt",
                        numThreads = 3,
                        debug = false,
                        provider = "cpu",
                        modelType = "sense_voice",
                    ),
                )
            )
            recognizer = rec
            overlay?.status("준비 완료! 영상을 재생하세요\n(자막을 끌어서 옮길 수 있어요)")

            val tag = Regex("<\\|.*?\\|>")
            val history = ArrayDeque<String>()
            while (running) {
                val seg = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                while (queue.size > 3) queue.poll()   // 너무 밀리면 오래된 건 버림

                val stream = rec.createStream()
                stream.acceptWaveform(seg, SR)
                rec.decode(stream)
                val raw = rec.getResult(stream).text
                stream.release()

                val zh = raw.replace(tag, "").trim()
                if (zh.isEmpty() || !zh.any { it.isLetterOrDigit() }) continue

                overlay?.show(zh, "…", "")
                val (ko, used) = try {
                    translators.translate(zh, history.toList())
                } catch (e: Exception) {
                    Pair("(번역 실패: ${e.message})", "")
                }
                val chosen = when (Prefs.engine(this)) {
                    Prefs.ENGINE_GEMINI -> "제미나이"
                    Prefs.ENGINE_GOOGLE -> "구글"
                    else -> "기기번역"
                }
                val tagText = if (used.isNotEmpty() && used != chosen) "↩$used" else ""
                overlay?.show(zh, ko, tagText)

                history.addLast(zh)
                while (history.size > 4) history.removeFirst()
            }
        } catch (e: Exception) {
            overlay?.status("오류: ${e.message}")
        } finally {
            recognizer?.release()
            translators.close()
        }
    }

    override fun onDestroy() {
        running = false
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        overlay?.remove()
        super.onDestroy()
    }
}

// ───────────────────────── 화면 위에 뜨는 자막 ─────────────────────────
// · 끌어서 옮기기 (위치 기억)   · 짧게 누르면 원문 켜기/끄기
// · '위치 잠금'이면 터치가 영상으로 그대로 통과
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val box = LinearLayout(ctx)
    private val tvOrig = TextView(ctx)
    private val tvTr = TextView(ctx)
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )
    private var added = false
    private var dragging = false
    private var lastOrig = ""
    private val hideTask = Runnable { setVisible(false) }

    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        ui.post {
            box.orientation = LinearLayout.VERTICAL
            box.background = GradientDrawable().apply {
                setColor(Color.argb(175, 0, 0, 0))
                cornerRadius = dp(10f).toFloat()
            }
            box.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            tvOrig.setTextColor(Color.rgb(200, 200, 200))
            tvOrig.maxLines = 3
            tvTr.setTextColor(Color.WHITE)
            tvTr.typeface = Typeface.DEFAULT_BOLD
            tvTr.maxLines = 4
            box.addView(tvOrig)
            box.addView(tvTr)

            val p = Prefs.get(ctx)
            val dm = ctx.resources.displayMetrics
            lp.gravity = Gravity.TOP or Gravity.START
            lp.width = (dm.widthPixels * 0.94f).toInt()
            lp.x = p.getInt("posX", (dm.widthPixels * 0.03f).toInt())
            lp.y = p.getInt("posY", (dm.heightPixels * 0.68f).toInt())
            box.visibility = View.INVISIBLE
            applyTouchable(false)

            box.setOnTouchListener(object : View.OnTouchListener {
                var downRawX = 0f
                var downRawY = 0f
                var startX = 0
                var startY = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = e.rawX
                            downRawY = e.rawY
                            startX = lp.x
                            startY = lp.y
                            dragging = false
                            ui.removeCallbacks(hideTask)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - downRawX
                            val dy = e.rawY - downRawY
                            if (!dragging && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) dragging = true
                            if (dragging) {
                                lp.x = startX + dx.toInt()
                                lp.y = startY + dy.toInt()
                                clamp()
                                safeUpdate()
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (dragging) {
                                Prefs.get(ctx).edit().putInt("posX", lp.x).putInt("posY", lp.y).apply()
                            } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                                // 짧게 누름 → 원문 표시 켜기/끄기
                                val now = !Prefs.showOrig(ctx)
                                Prefs.get(ctx).edit().putBoolean("showOrig", now).apply()
                                refreshOrig()
                                Toast.makeText(ctx, if (now) "원문 표시 켬" else "원문 표시 끔", Toast.LENGTH_SHORT).show()
                            }
                            dragging = false
                            ui.postDelayed(hideTask, 6000)
                        }
                    }
                    return true
                }
            })

            wm.addView(box, lp)
            added = true
        }
    }

    fun status(msg: String) = set("", msg, "", 8000)

    fun show(orig: String, tr: String, tag: String) = set(orig, tr, tag, 7000)

    private fun set(orig: String, tr: String, tag: String, ms: Long) {
        ui.post {
            if (!added) return@post
            val size = Prefs.textSize(ctx).toFloat()
            tvTr.textSize = size
            tvOrig.textSize = size * 0.68f
            lastOrig = if (tag.isNotEmpty() && orig.isNotEmpty()) "$orig   $tag" else orig
            tvTr.text = tr
            refreshOrig()

            // 화면 회전(가로 영상) 대응: 폭 다시 맞추기
            val dm = ctx.resources.displayMetrics
            val wantW = (dm.widthPixels * 0.94f).toInt()
            if (lp.width != wantW) lp.width = wantW
            clamp()
            setVisible(true)
            ui.removeCallbacks(hideTask)
            if (!dragging) ui.postDelayed(hideTask, ms)
        }
    }

    private fun refreshOrig() {
        val show = Prefs.showOrig(ctx) && lastOrig.isNotEmpty()
        tvOrig.text = lastOrig
        tvOrig.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setVisible(v: Boolean) {
        if (!added) return
        box.visibility = if (v) View.VISIBLE else View.INVISIBLE
        // 숨어 있을 때나 잠금일 때는 터치가 영상으로 통과하게
        applyTouchable(v && !Prefs.locked(ctx))
        safeUpdate()
    }

    private fun applyTouchable(touchable: Boolean) {
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    private fun clamp() {
        val dm = ctx.resources.displayMetrics
        val w = if (lp.width > 0) lp.width else box.width
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - w))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - maxOf(box.height, dp(40f))))
    }

    private fun safeUpdate() {
        if (added) {
            try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
        }
    }

    fun remove() {
        ui.post {
            if (added) {
                try { wm.removeView(box) } catch (_: Exception) {}
                added = false
            }
        }
    }
}
