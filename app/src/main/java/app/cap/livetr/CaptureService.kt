package app.cap.livetr

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// 소리 잡기 → 말소리 구간 자르기 → 중국어 글자 → 한국어 번역 → 자막
class CaptureService : Service() {
    companion object {
        const val CH = "livetr"
        const val ACTION_STOP = "app.cap.livetr.STOP"
        const val SR = 16000

        // 앱 전체에서 아직 살아 있는 작업 스레드 수 (껐다 바로 켤 때 이전 작업이 끝나길 기다리는 데 씀)
        val activeWorkers = AtomicInteger(0)

        // 빌리빌리 앱 이름들 (중국판, HD판, 국제판, 콘셉트판)
        val BILI_PACKAGES = listOf(
            "tv.danmaku.bili", "tv.danmaku.bilibilihd", "com.bstar.intl", "com.bilibili.app.blue"
        )
    }

    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var overlay: Overlay? = null

    // 시작할 때마다 번호가 올라감. 작업 스레드는 자기 번호가 현재 번호일 때만 계속 돈다.
    @Volatile private var session = 0
    @Volatile private var destroyed = false

    private val segQueue = LinkedBlockingQueue<FloatArray>()
    private val trQueue = LinkedBlockingQueue<Pair<Long, String>>()

    // 지금 말하는 중인 소리 (아직 문장이 안 끊긴 부분) — 미리 보기용
    private val liveLock = Any()
    private val liveChunks = ArrayList<FloatArray>()
    @Volatile private var liveSamples = 0

    // 제미나이 통역 모드: 켜져 있으면 소리를 폰 안 인식기 대신 제미나이로 보냄
    @Volatile private var liveMode = false
    @Volatile private var live: LiveSession? = null

    // 상태 표시용
    @Volatile private var nShown = 0
    @Volatile private var nHidden = 0
    @Volatile private var captureNote = ""
    @Volatile private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    private fun alive(my: Int) = !destroyed && session == my

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground("준비 중…")

        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || code != Activity.RESULT_OK) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }

        // 이미 돌고 있었다면 이전 작업을 끝내고 새로 시작
        val my = ++session
        segQueue.clear()
        trQueue.clear()
        projection?.let { old ->
            projection = null
            try { old.stop() } catch (_: Exception) {}
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 지금 쓰는 캡처가 시스템에 의해 끝났을 때만 서비스 종료
                ui.post { if (projection === proj && !destroyed) stopSelf() }
            }
        }, ui)

        val ov = overlay ?: Overlay(this).also { overlay = it; it.attach() }
        ov.clearError()
        live?.close()
        live = null
        liveMode = false
        if (Prefs.engine(this) == Prefs.ENGINE_LIVE) {
            if (Prefs.geminiKey(this).isNotEmpty()) liveMode = true
            else ov.error("제미나이 키가 없어서 기본 방식(폰 인식 + 번역)으로 동작해요.")
        }
        nShown = 0
        nHidden = 0

        // 이전 작업 스레드가 다 끝날 때까지 기다린 뒤 새 작업 시작
        thread(name = "session-$my") {
            val deadline = SystemClock.uptimeMillis() + 10_000
            if (activeWorkers.get() > 0) ov.info("이전 작업 정리 중…")
            while (activeWorkers.get() > 0 && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            if (!alive(my)) return@thread
            activeWorkers.addAndGet(if (liveMode) 4 else 3)
            if (liveMode) {
                thread(name = "live-$my") { try { liveLoop(my) } finally { activeWorkers.decrementAndGet() } }
            }
            thread(name = "capture-$my") { try { captureLoop(my, proj) } finally { activeWorkers.decrementAndGet() } }
            thread(name = "asr-$my") { try { asrLoop(my) } finally { activeWorkers.decrementAndGet() } }
            thread(name = "translate-$my") { try { translateLoop(my) } finally { activeWorkers.decrementAndGet() } }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "중지", stopPi
        ).build()
        return Notification.Builder(this, CH)
            .setContentTitle("실시간 번역 실행 중")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(action)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "실시간 번역", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // 상태줄 + 알림 갱신
    private fun report(force: Boolean = false) {
        val hidden = if (nHidden > 0) " · 숨김 $nHidden" else ""
        val mode = if (liveMode) "제미나이 통역 중" else "듣는 중"
        val text = "$mode · 자막 $nShown$hidden" + if (captureNote.isNotEmpty()) " · $captureNote" else ""
        overlay?.info(text)
        val now = SystemClock.uptimeMillis()
        if (force || now - lastNotify > 3000) {
            lastNotify = now
            try {
                getSystemService(NotificationManager::class.java).notify(1, notification(text))
            } catch (_: Exception) {}
        }
    }

    private fun biliUid(): Int? {
        val pm = packageManager
        for (p in BILI_PACKAGES) {
            try {
                return pm.getApplicationInfo(p, 0).uid
            } catch (_: Exception) {
            }
        }
        return null
    }

    // 1) 재생 중인 소리를 잡아서, 말하는 구간만 잘라 큐에 넣는다. 실패하면 최대 3번 다시 시도.
    private fun captureLoop(my: Int, proj: MediaProjection) {
        var vad: Vad? = null
        // 자막 길이: 짧게는 빨리 뜨고, 길게는 문장이 덜 잘림 (다시 시작해야 적용)
        val (silence, maxLen) = when (Prefs.segLen(this)) {
            "long" -> Pair(0.4f, 8.0f)
            "normal" -> Pair(0.3f, 5.0f)
            else -> Pair(0.2f, 3.5f)
        }
        try {
            val v = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = silence,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = maxLen,
                    ),
                    sampleRate = SR,
                    numThreads = 1,
                    provider = "cpu",
                )
            )
            vad = v
            var attempt = 0
            while (alive(my) && attempt < 3) {
                attempt++
                try {
                    captureOnce(my, proj, v)
                    if (!alive(my)) break
                    overlay?.error("소리 캡처가 끊겨서 다시 연결하는 중… ($attempt/3)")
                } catch (e: Exception) {
                    if (!alive(my)) break
                    overlay?.error("소리 캡처 오류, 다시 시도 중 ($attempt/3): ${e.message}")
                }
                Thread.sleep(1500)
            }
            if (alive(my)) overlay?.error("소리를 잡지 못했어요. 자막을 중지했다가 다시 시작해주세요.")
        } catch (e: Exception) {
            if (alive(my)) overlay?.error("오류(소리 캡처 준비): ${e.message}")
        } finally {
            vad?.release()
        }
    }

    private fun captureOnce(my: Int, proj: MediaProjection, v: Vad) {
        val b = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        val uid = if (Prefs.biliOnly(this)) biliUid() else null
        if (uid != null) b.addMatchingUid(uid)
        captureNote = if (uid != null) "빌리빌리 소리만" else "모든 앱 소리"

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
            .setAudioPlaybackCaptureConfig(b.build())
            .build()
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw RuntimeException("녹음기를 만들 수 없어요")
            rec.startRecording()
            v.reset()
            overlay?.clearError()
            report(force = true)

            // 48kHz 스테레오 → 16kHz 모노 (6개 값을 평균내서 1개로)
            val shorts = ShortArray(3840)
            val win = FloatArray(512)
            var wi = 0
            var acc = 0f
            var cnt = 0
            var badReads = 0
            var lastSound = SystemClock.uptimeMillis()
            var warnedSilence = false
            while (alive(my)) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n < 0) {
                    if (++badReads > 20) return   // 계속 실패 → 바깥에서 다시 연결
                    Thread.sleep(50)
                    continue
                }
                badReads = 0
                if (n == 0) {
                    Thread.sleep(10)
                    continue
                }
                var anySound = false
                for (i in 0 until n) {
                    val s = shorts[i]
                    if (s.toInt() != 0) anySound = true
                    acc += s
                    cnt++
                    if (cnt == 6) {
                        win[wi++] = acc / (6f * 32768f)
                        acc = 0f
                        cnt = 0
                        if (wi == 512) {
                            val chunk = win.copyOf()
                            wi = 0
                            val ls = live
                            if (liveMode) {
                                // 통역 모드: 제미나이로 보냄 (연결 준비 중이면 그냥 버림)
                                ls?.send(chunk)
                                continue
                            }
                            v.acceptWaveform(chunk)
                            var cut = false
                            while (!v.empty()) {
                                val seg = v.front()
                                v.pop()
                                segQueue.offer(seg.samples)
                                cut = true
                            }
                            synchronized(liveLock) {
                                if (cut) {
                                    liveChunks.clear()
                                    liveSamples = 0
                                }
                                if (v.isSpeechDetected() && liveSamples < SR * 10) {
                                    liveChunks.add(chunk)
                                    liveSamples += chunk.size
                                } else if (!v.isSpeechDetected() && liveSamples > 0) {
                                    liveChunks.clear()
                                    liveSamples = 0
                                }
                            }
                        }
                    }
                }
                // 완전한 무음이 오래가면: 재생이 멈췄거나 앱이 소리 캡처를 막은 것
                val now = SystemClock.uptimeMillis()
                if (anySound) {
                    lastSound = now
                    if (warnedSilence) {
                        warnedSilence = false
                        overlay?.clearError()
                        report(force = true)
                    }
                } else if (!warnedSilence && now - lastSound > 15_000) {
                    warnedSilence = true
                    val hint = if (captureNote == "빌리빌리 소리만") {
                        " 다른 앱이나 브라우저로 보는 중이면 설정에서 '빌리빌리 소리만'을 끄세요."
                    } else ""
                    overlay?.error("소리가 안 들어와요. 영상이 재생 중인지 확인하세요.$hint")
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    // 0) 제미나이 통역 모드: 연결 유지, 끊기면 다시 연결, 계속 안 되면 기본 방식으로 전환
    private fun liveLoop(my: Int) {
        val key = Prefs.geminiKey(this)
        var quickFails = 0
        try {
            while (alive(my) && liveMode) {
                overlay?.info("제미나이 통역 연결 중…")
                val sess = LiveSession(key, overlay) { nShown++; report() }
                live = sess
                sess.connect()
                val t0 = SystemClock.uptimeMillis()
                while (alive(my) && !sess.connected && sess.closedReason == null &&
                    SystemClock.uptimeMillis() - t0 < 10_000
                ) Thread.sleep(100)
                if (sess.connected) {
                    overlay?.clearError()
                    report(force = true)
                }
                while (alive(my) && sess.closedReason == null && sess.connected) {
                    sess.tick()
                    Thread.sleep(300)
                }
                val reason = sess.closedReason ?: if (!sess.connected) "연결 시간 초과" else ""
                val lived = SystemClock.uptimeMillis() - t0
                sess.close()
                live = null
                if (!alive(my)) break

                val r = reason.lowercase()
                val quota = r.contains("quota") || r.contains("429") || r.contains("exhausted") || r.contains("limit")
                if (quota) {
                    liveMode = false
                    overlay?.error("제미나이 통역 무료 한도에 걸렸어요 → 기본 방식으로 전환 ($reason)")
                    break
                }
                // 오래 잘 돌다가 끊긴 건(세션 시간 제한 등) 바로 다시 연결. 금방 끊기는 게 반복되면 포기.
                if (lived > 60_000) quickFails = 0 else quickFails++
                if (quickFails >= 3) {
                    liveMode = false
                    overlay?.error("제미나이 통역 연결이 계속 실패해요 → 기본 방식으로 전환 ($reason)")
                    break
                }
                overlay?.info("제미나이 통역 다시 연결 중… ($reason)")
                Thread.sleep(if (quickFails == 0) 300L else 2000L)
            }
        } finally {
            live?.close()
            live = null
        }
    }

    // 2) 잘라낸 말소리 → 글자 (+ 중국어인지 판별)
    private fun asrLoop(my: Int) {
        // 통역 모드에서는 폰 인식기가 필요 없음. 통역이 실패해서 전환될 때까지 대기.
        while (alive(my) && liveMode) Thread.sleep(300)
        if (!alive(my)) return
        try {
            val modelId = Prefs.asrModel(this)
            if (!Models.isReady(this, modelId)) {
                overlay?.error("선택한 인식 모델이 아직 내려받아지지 않았어요. 앱에서 내려받은 뒤 다시 시작하세요.")
                return
            }
            overlay?.info("인식 모델 불러오는 중… (처음엔 몇 초~수십 초)")
            val rec = Asr.get(this, modelId)
            if (!alive(my)) return
            val detects = Models.spec(modelId).detectsLanguage
            report(force = true)

            val tag = Regex("<\\|.*?\\|>")
            val hangul = Regex("[\\uAC00-\\uD7A3\\u3131-\\u318E]")
            // 미리 보기는 가벼운 SenseVoice에서만 (Fun-ASR Nano는 너무 무거움)
            val canPartial = modelId == Models.SV2025
            var lastPartialAt = 0
            while (alive(my)) {
                val seg = segQueue.poll(200, TimeUnit.MILLISECONDS)
                if (seg == null) {
                    // 문장이 아직 안 끊겼으면, 지금까지 들은 만큼 중국어를 미리 보여줌 (약 0.7초마다)
                    if (canPartial && Prefs.partial(this)) {
                        val n = liveSamples
                        if (n >= SR * 8 / 10 && n - lastPartialAt >= SR * 7 / 10) {
                            lastPartialAt = n
                            val buf = synchronized(liveLock) {
                                val out = FloatArray(liveSamples)
                                var o = 0
                                for (c in liveChunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
                                out
                            }
                            val ps = rec.createStream()
                            ps.acceptWaveform(buf, SR)
                            rec.decode(ps)
                            val pr = rec.getResult(ps)
                            ps.release()
                            val pt = pr.text.replace(tag, "").trim()
                            val plang = pr.lang.replace("<|", "").replace("|>", "").trim()
                            val skip = Prefs.onlyChinese(this) &&
                                ((plang.isNotEmpty() && plang != "zh" && plang != "yue") || hangul.containsMatchIn(pt))
                            if (pt.isNotEmpty() && !skip) overlay?.live(pt)
                        } else if (n == 0 && lastPartialAt != 0) {
                            lastPartialAt = 0
                        }
                    }
                    continue
                }
                lastPartialAt = 0
                while (segQueue.size > 3) segQueue.poll()   // 너무 밀리면 오래된 건 버림

                val stream = rec.createStream()
                stream.acceptWaveform(seg, SR)
                rec.decode(stream)
                val result = rec.getResult(stream)
                stream.release()
                if (!alive(my)) break

                val text = result.text.replace(tag, "").trim()
                if (text.isEmpty() || !text.any { it.isLetterOrDigit() }) continue

                if (Prefs.onlyChinese(this)) {
                    val lang = result.lang.replace("<|", "").replace("|>", "").trim()
                    val notChinese = (detects && lang.isNotEmpty() && lang != "zh" && lang != "yue") ||
                        hangul.containsMatchIn(text)
                    if (notChinese) {
                        overlay?.live("")
                        nHidden++
                        report()
                        continue
                    }
                }

                val id = overlay?.add(text) ?: continue
                nShown++
                report()
                trQueue.offer(Pair(id, text))
            }
        } catch (e: Throwable) {
            if (alive(my)) overlay?.error("오류(음성 인식): ${e.message}")
        }
    }

    // 3) 글자 → 한국어 (따로 돌려서 번역이 느려도 인식이 밀리지 않게)
    private fun translateLoop(my: Int) {
        val tr = Translators(applicationContext)
        val history = ArrayDeque<String>()
        try {
            while (alive(my)) {
                val (id, zh) = trQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val (ko, used) = try {
                    tr.translate(zh, history.toList())
                } catch (e: Exception) {
                    Pair("(번역 실패: ${e.message})", "")
                }
                val chosen = when (Prefs.engine(this)) {
                    Prefs.ENGINE_GEMINI, Prefs.ENGINE_LIVE -> "제미나이"
                    Prefs.ENGINE_GOOGLE -> "구글"
                    else -> "기기번역"
                }
                overlay?.update(id, ko, if (used.isNotEmpty() && used != chosen) "↩$used" else "")
                history.addLast(zh)
                while (history.size > 4) history.removeFirst()
            }
        } finally {
            tr.close()
        }
    }

    override fun onDestroy() {
        destroyed = true
        session++
        live?.close()
        live = null
        projection?.let { p ->
            projection = null
            try { p.stop() } catch (_: Exception) {}
        }
        overlay?.remove()
        overlay = null
        super.onDestroy()
    }
}
