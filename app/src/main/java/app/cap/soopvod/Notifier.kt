package app.cap.soopvod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

object Notifier {
    private const val CH = "new_vod"

    private fun nm(c: Context): NotificationManager {
        val m = c.getSystemService(NotificationManager::class.java)
        if (m.getNotificationChannel(CH) == null) {
            m.createNotificationChannel(
                NotificationChannel(CH, "새 다시보기", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        return m
    }

    fun newVod(c: Context, streamer: String, v: Vod, sent: Boolean) {
        val code = v.no.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val sendPi = PendingIntent.getActivity(c, code, Handoff.intentFor(c, v.url), flags)
        val openPi = PendingIntent.getActivity(
            c, code + 1,
            Intent(Intent.ACTION_VIEW, Uri.parse(v.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val title = if (sent) "$streamer 다시보기 다운로드 보냄" else "$streamer 새 다시보기"
        val n = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(v.title)
            .setStyle(Notification.BigTextStyle().bigText(v.title))
            .setAutoCancel(true)
            .setContentIntent(if (sent) openPi else sendPi)
            .addAction(Notification.Action.Builder(null, if (sent) "다시 보내기" else "다운로드 앱으로 보내기", sendPi).build())
            .addAction(Notification.Action.Builder(null, "SOOP에서 열기", openPi).build())
            .build()
        try {
            nm(c).notify(code, n)
        } catch (e: SecurityException) {
            // 알림 권한 없음
        }
    }
}
