package app.cap.soopvod

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Vod(val no: String, val title: String) {
    val url: String get() = "https://vod.sooplive.co.kr/player/$no"
}

/** SOOP 채널 다시보기 목록 (yt-dlp가 쓰는 것과 같은 공개 API) */
object Soop {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // .co.kr이 막히면 .com으로 재시도 (SOOP이 도메인을 옮기는 중)
    private val hosts = listOf("https://chapi.sooplive.co.kr", "https://chapi.sooplive.com")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 최신순 다시보기 목록 */
    fun latestReplays(id: String, count: Int = 20): List<Vod> {
        var last: Exception? = null
        for (h in hosts) {
            try {
                val url = "$h/api/$id/vods/review?page=1&per_page=$count&orderby=reg_date"
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Referer", "https://www.sooplive.co.kr/")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body?.string() ?: throw IOException("빈 응답")
                    val arr = JSONObject(body).optJSONArray("data")
                        ?: throw IOException("목록 형식이 바뀜")
                    val out = ArrayList<Vod>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val no = o.opt("title_no")?.toString().orEmpty()
                        if (no.isEmpty() || no == "null") continue
                        val title = o.optString("title_name").ifBlank { o.optString("title") }
                        out.add(Vod(no, title.ifBlank { "다시보기 $no" }))
                    }
                    return out
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("알 수 없는 오류")
    }

    /** 아이디 또는 채널/방송 주소에서 스트리머 아이디 뽑기 */
    fun parseId(input: String): String {
        val s = input.trim()
        val patterns = listOf(
            Regex("""/station/([A-Za-z0-9_]+)"""),
            Regex("""ch\.(?:sooplive\.co\.kr|sooplive\.com|afreecatv\.com)/([A-Za-z0-9_]+)"""),
            Regex("""play\.(?:sooplive\.co\.kr|sooplive\.com|afreecatv\.com)/([A-Za-z0-9_]+)"""),
            Regex("""bj\.afreecatv\.com/([A-Za-z0-9_]+)"""),
        )
        for (p in patterns) p.find(s)?.let { return it.groupValues[1] }
        return s.trimStart('@').filter { it.isLetterOrDigit() || it == '_' }
    }
}
