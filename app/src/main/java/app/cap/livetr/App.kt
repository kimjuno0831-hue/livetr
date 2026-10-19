package app.cap.livetr

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// ───────────────────────── 화면(앱 첫 화면) ─────────────────────────
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val reqPerm = 1
    private val reqCapture = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            textSize = 16f
            text = "1) [자막 시작]을 누르세요.\n" +
                "2) 권한 창이 뜨면 모두 허용하세요.\n" +
                "3) 빌리빌리 앱으로 가서 영상을 재생하세요."
        }
        val start = Button(this).apply {
            text = "자막 시작"
            setOnClickListener { begin() }
        }
        val stop = Button(this).apply {
            text = "자막 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "중지했어요."
            }
        }
        col.addView(status)
        col.addView(start)
        col.addView(stop)
        setContentView(ScrollView(this).apply { addView(col) })
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

        overlay = Overlay(this).also { it.show() }
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
        var translator: Translator? = null
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

            overlay?.status("번역 모델 내려받는 중… (처음 한 번만, 인터넷 필요)")
            val tr = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE)
                    .setTargetLanguage(TranslateLanguage.KOREAN)
                    .build()
            )
            translator = tr
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            overlay?.status("준비 완료! 영상을 재생하세요")

            val tag = Regex("<\\|.*?\\|>")
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

                overlay?.show(zh, "…")
                val ko = try {
                    Tasks.await(tr.translate(zh))
                } catch (e: Exception) {
                    "(번역 실패)"
                }
                overlay?.show(zh, ko)
            }
        } catch (e: Exception) {
            overlay?.status("오류: ${e.message}")
        } finally {
            recognizer?.release()
            translator?.close()
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
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val box = LinearLayout(ctx)
    private val tvOrig = TextView(ctx)
    private val tvTr = TextView(ctx)
    private val hideTask = Runnable { box.visibility = View.INVISIBLE }
    private var added = false

    fun show() {
        ui.post {
            val d = ctx.resources.displayMetrics.density
            box.orientation = LinearLayout.VERTICAL
            box.setBackgroundColor(Color.argb(170, 0, 0, 0))
            box.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            tvOrig.setTextColor(Color.LTGRAY)
            tvOrig.textSize = 13f
            tvOrig.maxLines = 2
            tvTr.setTextColor(Color.WHITE)
            tvTr.textSize = 19f
            tvTr.typeface = Typeface.DEFAULT_BOLD
            tvTr.maxLines = 4
            box.addView(tvOrig)
            box.addView(tvTr)
            box.visibility = View.INVISIBLE
            val lp = WindowManager.LayoutParams(
                (ctx.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.y = (90 * d).toInt()
            wm.addView(box, lp)
            added = true
        }
    }

    fun status(msg: String) = set("", msg, 8000)

    fun show(orig: String, tr: String) = set(orig, tr, 7000)

    private fun set(orig: String, tr: String, ms: Long) {
        ui.post {
            if (!added) return@post
            tvOrig.text = orig
            tvOrig.visibility = if (orig.isEmpty()) View.GONE else View.VISIBLE
            tvTr.text = tr
            box.visibility = View.VISIBLE
            ui.removeCallbacks(hideTask)
            ui.postDelayed(hideTask, ms)
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
