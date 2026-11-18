package app.cap.soopvod

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 설정·기록 저장소 (SharedPreferences) */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("soopvod", Context.MODE_PRIVATE)

    fun streamers(c: Context): List<String> =
        (p(c).getString("streamers", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun addStreamer(c: Context, id: String): Boolean {
        val l = streamers(c)
        if (id in l) return false
        p(c).edit().putString("streamers", (l + id).joinToString(",")).apply()
        return true
    }

    fun removeStreamer(c: Context, id: String) {
        p(c).edit()
            .putString("streamers", streamers(c).filter { it != id }.joinToString(","))
            .remove("seen_$id").remove("init_$id").remove("live_$id").remove("hot_$id")
            .apply()
    }

    /** 이미 본(보낸) 다시보기 번호들 */
    fun seen(c: Context, id: String): MutableSet<String> =
        HashSet(p(c).getStringSet("seen_$id", emptySet()) ?: emptySet())

    fun setSeen(c: Context, id: String, s: Set<String>) {
        // 최신 300개만 유지
        val kept = s.sortedByDescending { it.toLongOrNull() ?: 0L }.take(300).toSet()
        p(c).edit().putStringSet("seen_$id", HashSet(kept)).apply()
    }

    fun isInit(c: Context, id: String) = p(c).getBoolean("init_$id", false)
    fun setInit(c: Context, id: String) = p(c).edit().putBoolean("init_$id", true).apply()


    fun interval(c: Context) = p(c).getInt("interval", 10)
    fun setInterval(c: Context, v: Int) = p(c).edit().putInt("interval", v).apply()

    fun bgFlag(c: Context) = p(c).getBoolean("bg", true)
    fun setBgFlag(c: Context, v: Boolean) = p(c).edit().putBoolean("bg", v).apply()

    fun autoSend(c: Context) = p(c).getBoolean("auto", true)
    fun setAutoSend(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()

    /** 보낼 앱 (패키지/액티비티). 비어 있으면 YTDLnis 자동 탐색 */
    fun target(c: Context) = p(c).getString("target", "") ?: ""
    fun setTarget(c: Context, v: String) = p(c).edit().putString("target", v).apply()

    // ── 빠른 확인 모드 ──
    fun smart(c: Context) = p(c).getBoolean("smart", true)
    fun setSmart(c: Context, v: Boolean) = p(c).edit().putBoolean("smart", v).apply()
    fun fastInterval(c: Context) = p(c).getInt("fast", 1)
    fun setFastInterval(c: Context, v: Int) = p(c).edit().putInt("fast", v).apply()

    /** 마지막으로 본 방송 상태 (true=방송 중) */
    fun wasLive(c: Context, id: String) = p(c).getBoolean("live_$id", false)
    fun setWasLive(c: Context, id: String, v: Boolean) = p(c).edit().putBoolean("live_$id", v).apply()

    /** 이 시각까지는 다시보기를 기다리며 자주 확인 */
    fun hotUntil(c: Context, id: String) = p(c).getLong("hot_$id", 0L)
    fun setHotUntil(c: Context, id: String, t: Long) = p(c).edit().putLong("hot_$id", t).apply()

    fun isHot(c: Context): Boolean {
        val now = System.currentTimeMillis()
        return streamers(c).any { wasLive(c, it) || hotUntil(c, it) > now }
    }

    fun nextCheck(c: Context) = p(c).getLong("next", 0L)
    fun setNextCheck(c: Context, t: Long) = p(c).edit().putLong("next", t).apply()

    fun lastCheck(c: Context) = p(c).getLong("last", 0L)
    fun setLastCheck(c: Context, t: Long) = p(c).edit().putLong("last", t).apply()

    fun log(c: Context, msg: String) {
        val t = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        val old = (p(c).getString("log", "") ?: "").lines().filter { it.isNotBlank() }
        val next = (listOf("[$t] $msg") + old).take(60)
        p(c).edit().putString("log", next.joinToString("\n")).apply()
    }

    fun logs(c: Context) = p(c).getString("log", "") ?: ""
    fun clearLogs(c: Context) = p(c).edit().remove("log").apply()
}
