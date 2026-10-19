package app.cap.livetr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// 음성 인식 모델 목록과 내려받기
object Models {
    const val SV2025 = "sv2025"
    const val FUNNANO = "funnano"

    class Spec(
        val id: String,
        val label: String,
        val archive: String?,   // null 이면 앱에 내장
        val sizeMb: Int,
        val detectsLanguage: Boolean,
    )

    val all = listOf(
        Spec(SV2025, "SenseVoice 2025 (기본, 앱에 내장, 가볍고 빠름)", null, 0, true),
        Spec(FUNNANO, "Fun-ASR Nano (가장 최신·무거움, 약 840MB 내려받기)",
            "sherpa-onnx-funasr-nano-int8-2025-12-30", 840, false),
    )

    fun spec(id: String) = all.firstOrNull { it.id == id } ?: all[0]

    // 예전 버전에서 따로 내려받았던 SenseVoice 2025는 이제 앱에 내장이라 지워서 공간 돌려받기
    fun cleanupOld(ctx: Context) {
        File(ctx.filesDir, "models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09").deleteRecursively()
        File(ctx.filesDir, "models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tmp").deleteRecursively()
    }

    private fun dir(ctx: Context, s: Spec) = File(ctx.filesDir, "models/${s.archive}")

    fun isReady(ctx: Context, id: String): Boolean {
        val s = spec(id)
        return s.archive == null || File(dir(ctx, s), "done.flag").exists()
    }

    // 진행 상황: (받은 바이트, 전체 바이트)
    fun download(ctx: Context, id: String, progress: (Long, Long) -> Unit) {
        val s = spec(id)
        val archive = s.archive ?: return
        val free = ctx.filesDir.usableSpace / (1024 * 1024)
        if (free < s.sizeMb * 2L) {
            throw RuntimeException("저장 공간이 부족해요 (남은 공간 ${free}MB, 필요 약 ${s.sizeMb * 2}MB)")
        }
        val final = dir(ctx, s)
        val tmp = File(final.parentFile, "$archive.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()

        val url = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2")
        val c = url.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            if (c.responseCode != 200) throw RuntimeException("내려받기 실패 (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            var got = 0L
            var lastReport = 0L
            val counting = object : FilterInputStream(c.inputStream) {
                private fun add(n: Int) {
                    if (n > 0) {
                        got += n
                        if (got - lastReport > 2_000_000) {
                            lastReport = got
                            progress(got, total)
                        }
                    }
                }
                override fun read(): Int {
                    val b = super.read()
                    if (b >= 0) add(1)
                    return b
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    add(n)
                    return n
                }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    // 맨 앞 폴더 이름 떼기, 테스트 음성/설명 파일은 건너뜀
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty() || rel.startsWith("test_wavs") || rel.endsWith(".md")) continue
                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> copy(tar, o) }
                    }
                }
            }
            progress(total, total)
        } finally {
            c.disconnect()
        }
        File(tmp, "done.flag").writeText("ok")
        final.deleteRecursively()
        if (!tmp.renameTo(final)) throw RuntimeException("파일 정리 실패")
    }

    private fun copy(input: InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    fun create(ctx: Context, id: String): OfflineRecognizer {
        val s = spec(id)
        val feat = FeatureConfig(sampleRate = 16000, featureDim = 80)
        return when (s.id) {
            FUNNANO -> {
                val d = dir(ctx, s).absolutePath
                OfflineRecognizer(
                    assetManager = null,
                    config = OfflineRecognizerConfig(
                        featConfig = feat,
                        modelConfig = OfflineModelConfig(
                            funasrNano = OfflineFunAsrNanoModelConfig(
                                encoderAdaptor = "$d/encoder_adaptor.int8.onnx",
                                llm = "$d/llm.int8.onnx",
                                embedding = "$d/embedding.int8.onnx",
                                tokenizer = "$d/Qwen3-0.6B",
                            ),
                            tokens = "",
                            numThreads = 4,
                            provider = "cpu",
                        ),
                    )
                )
            }
            else -> OfflineRecognizer(
                assetManager = ctx.assets,
                config = OfflineRecognizerConfig(
                    featConfig = feat,
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "model.int8.onnx",
                            language = "",
                            useInverseTextNormalization = true,
                        ),
                        tokens = "tokens.txt",
                        numThreads = 3,
                        provider = "cpu",
                        modelType = "sense_voice",
                    ),
                )
            )
        }
    }
}

// 인식기는 한 번 불러오면 앱이 살아 있는 동안 재사용 (껐다 켤 때 빠르고, 메모리에 두 번 안 올라감)
object Asr {
    private var rec: OfflineRecognizer? = null
    private var recId = ""

    @Synchronized
    fun get(ctx: Context, id: String): OfflineRecognizer {
        val cur = rec
        if (cur != null && recId == id) return cur
        cur?.release()
        rec = null
        recId = ""
        val r = Models.create(ctx.applicationContext, id)
        rec = r
        recId = id
        return r
    }
}
