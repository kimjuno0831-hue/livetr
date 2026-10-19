package app.cap.livetr

import android.content.Context

// 발음(병음) 기준 자동 교정
// 폰이 용어집 단어를 "발음은 같은데 다른 글자"로 받아 적었으면 (예: 派蒙 → 拍萌) 번역 전에 올바른 글자로 바꿈.
// 성조는 무시하고, 글자마다 대표 발음 2개까지 비교.
object Pinyin {
    @Volatile private var table: Map<Char, List<String>>? = null

    private fun load(ctx: Context): Map<Char, List<String>> {
        table?.let { return it }
        synchronized(this) {
            table?.let { return it }
            val m = HashMap<Char, List<String>>(30000)
            ctx.assets.open("pinyin.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.length < 3 || line[1] != ' ') continue
                    m[line[0]] = line.substring(2).split(',')
                }
            }
            table = m
            return m
        }
    }

    private fun sameSound(t: Map<Char, List<String>>, a: Char, b: Char): Boolean {
        if (a == b) return true
        val pa = t[a] ?: return false
        val pb = t[b] ?: return false
        return pa.any { it in pb }
    }

    // 결과: 고친 문장 (고칠 게 없으면 그대로)
    fun fix(ctx: Context, text: String, glossary: List<Pair<String, String>>): String {
        if (glossary.isEmpty() || text.isEmpty()) return text
        val t = try { load(ctx) } catch (_: Exception) { return text }
        var s = text
        // 긴 단어부터 (짧은 단어가 긴 단어의 일부를 먼저 바꾸지 않게)
        for ((term, _) in glossary) {
            val n = term.length
            // 한 글자 단어는 너무 자주 겹쳐서 제외. 한자만 있는 단어만.
            if (n < 2 || term.any { it !in t }) continue
            if (s.length < n) continue
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (i + n <= s.length) {
                    val w = s.substring(i, i + n)
                    if (w != term && w.indices.all { sameSound(t, w[it], term[it]) }) {
                        sb.append(term)
                        i += n
                        continue
                    }
                }
                sb.append(s[i])
                i++
            }
            s = sb.toString()
        }
        return s
    }
}
