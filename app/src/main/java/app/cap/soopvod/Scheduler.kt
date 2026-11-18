package app.cap.soopvod

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import kotlin.concurrent.thread

/** 정확한 시각 알람으로 다음 확인 예약 (15분 제한 없음) */
object Scheduler {
    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 10, Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun currentIntervalMin(c: Context): Int =
        if (Store.smart(c) && Store.isHot(c)) minOf(Store.fastInterval(c), Store.interval(c))
        else Store.interval(c)

    fun scheduleNext(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (Store.streamers(c).isEmpty() || !Store.enabled(c)) {
            am.cancel(pi(c))
            Store.setNextCheck(c, 0L)
            return
        }
        val at = System.currentTimeMillis() + currentIntervalMin(c) * 60_000L
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        Store.setNextCheck(c, at)
    }

    /** 알람 + 예비용 WorkManager(15분) 둘 다 걸기 */
    fun start(c: Context) {
        if (!Store.enabled(c)) {
            stop(c)
            return
        }
        scheduleNext(c)
        CheckWorker.schedule(c)
    }

    fun stop(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c))
        Store.setNextCheck(c, 0L)
        WorkManager.getInstance(c).cancelUniqueWork("check")
        WorkManager.getInstance(c).cancelUniqueWork("now")
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        val pending = goAsync()
        val wl = c.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soopvod:check")
        wl.acquire(55_000L)
        thread {
            try {
                Checker.run(c)
            } catch (e: Exception) {
                Store.log(c, "확인 중 오류: ${e.message}")
            } finally {
                Scheduler.scheduleNext(c)
                if (wl.isHeld) wl.release()
                pending.finish()
            }
        }
    }
}

/** 재부팅·업데이트 후 다시 예약 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.start(context.applicationContext)
    }
}
