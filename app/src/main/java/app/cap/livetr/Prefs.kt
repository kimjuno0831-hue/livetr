package app.cap.livetr

import android.content.Context
import android.content.SharedPreferences

// 앱 설정값 저장소. 설정은 자막이 켜져 있어도 바로 반영되도록 매번 새로 읽는다.
object Prefs {
    const val ENGINE_GOOGLE = "google"
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_MLKIT = "mlkit"
    const val ENGINE_LIVE = "live"   // 제미나이 통역 (소리를 바로 번역)
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
    fun textSize(ctx: Context) = get(ctx).getInt("textSize", 18)
    fun onlyChinese(ctx: Context) = get(ctx).getBoolean("onlyChinese", true)
    fun biliOnly(ctx: Context) = get(ctx).getBoolean("biliOnly", true)
    // 없어진 예전 모델(sv2024 등)이 저장돼 있으면 기본 모델로
    fun asrModel(ctx: Context): String {
        val v = get(ctx).getString("asrModel", Models.SV2025) ?: Models.SV2025
        return if (Models.all.any { it.id == v }) v else Models.SV2025
    }

    // 자막 끊는 길이: short / normal / long
    fun segLen(ctx: Context) = get(ctx).getString("segLen", "short") ?: "short"

    // 말하는 도중 중국어 미리 보여주기
    fun partial(ctx: Context) = get(ctx).getBoolean("partial", true)
    fun glossaryText(ctx: Context) = get(ctx).getString("glossary", "") ?: ""

    // "原神=원신" 형식의 줄들을 (중국어, 한국어) 목록으로. 긴 단어부터.
    fun glossary(ctx: Context): List<Pair<String, String>> =
        glossaryText(ctx).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .mapNotNull {
                val i = it.indexOf('=')
                val zh = it.substring(0, i).trim()
                val ko = it.substring(i + 1).trim()
                if (zh.isEmpty() || ko.isEmpty()) null else Pair(zh, ko)
            }
            .sortedByDescending { it.first.length }
}
