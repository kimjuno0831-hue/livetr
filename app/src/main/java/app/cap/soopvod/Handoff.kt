package app.cap.soopvod

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.provider.Settings

/** 다운로드 앱(YTDLnis 등)에 링크 넘기기 */
object Handoff {
    private fun share(url: String) = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
        // YTDLnis 자동화용: 동영상으로, 다운로드 카드 없이 바로 받기
        .putExtra("TYPE", "video")
        .putExtra("BACKGROUND", true)

    /** 텍스트 공유를 받을 수 있는 앱들 */
    fun targets(c: Context): List<ResolveInfo> =
        c.packageManager.queryIntentActivities(share("https://example.com"), 0)
            .filter { it.activityInfo.packageName != c.packageName }

    fun resolve(c: Context): ComponentName? {
        val list = targets(c)
        val pref = Store.target(c)
        val pick = list.firstOrNull { "${it.activityInfo.packageName}/${it.activityInfo.name}" == pref }
            ?: list.firstOrNull { it.activityInfo.packageName == "com.deniscerri.ytdl" }
            ?: list.firstOrNull { it.activityInfo.packageName.startsWith("com.deniscerri.ytdl") }
            ?: list.firstOrNull { it.activityInfo.packageName.lowercase().contains("ytdl") }
            ?: return null
        return ComponentName(pick.activityInfo.packageName, pick.activityInfo.name)
    }

    fun targetLabel(c: Context): String {
        val cn = resolve(c) ?: return "없음 (YTDLnis를 설치하거나 직접 골라줘)"
        return try {
            val ai = c.packageManager.getActivityInfo(cn, 0)
            ai.loadLabel(c.packageManager).toString()
        } catch (e: Exception) {
            cn.packageName
        }
    }

    /** 보낼 앱이 정해져 있으면 그 앱으로, 아니면 공유 선택창 */
    fun intentFor(c: Context, url: String): Intent {
        val cn = resolve(c)
        val i = if (cn != null) share(url).setComponent(cn)
        else Intent.createChooser(share(url), "다운로드할 앱 선택")
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 백그라운드에서 바로 띄울 수 있는지 (다른 앱 위에 표시 권한) */
    fun canAutoStart(c: Context) = Settings.canDrawOverlays(c)

    /** 바로 보내기. 성공하면 true */
    fun send(c: Context, url: String, fromForeground: Boolean = false): Boolean {
        val cn = resolve(c) ?: return false
        if (!fromForeground && !canAutoStart(c)) return false
        return try {
            c.startActivity(share(url).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }
}
