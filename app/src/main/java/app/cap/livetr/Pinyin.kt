package app.cap.livetr

import android.content.Context

// 발음(병음) 기준으로 용어집 이름이 잘못 인식된 곳 찾기
// · fix(): 발음이 '완전히 같은' 곳만 바로 교체 (구글·기기 번역용, 맥락 판단을 못 하니 확실한 것만)
// · hints(): 발음이 '비슷한' 곳 + 잘못 인식된 형태 + 앞 줄과 이어진 곳을 후보로만 알려줌
//            (제미나이 계열용, 바꿀지는 제미나이가 맥락을 보고 결정)
object Pinyin {
    @Volatile private var table: Map<Char, List<String>>? = null

    class Hint(val found: String, val term: String, val ko: String, val kind: String)

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

    fun of(ctx: Context, word: String): String {
        val t = runCatching { load(ctx) }.getOrNull() ?: return ""
        return word.map { t[it]?.firstOrNull() ?: "?" }.joinToString(" ")
    }

    private fun sameSound(t: Map<Char, List<String>>, a: Char, b: Char): Boolean {
        if (a == b) return true
        val pa = t[a] ?: return false
        val pb = t[b] ?: return false
        return pa.any { it in pb }
    }

    // 중국어 음성 인식이 흔히 헷갈리는 소리를 같게 취급해서 비교
    // 성모: zh/z, ch/c, sh/s, n/l, f/h, r/l, y·w는 없는 것으로 / 운모: 끝의 ng→n→(없음), u→v 등
    private fun loose(syl: String): String {
        var s = syl
        val initials = listOf("zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "j", "q", "x", "r", "z", "c", "s", "y", "w")
        var ini = ""
        for (i in initials) if (s.startsWith(i)) { ini = i; break }
        var fin = s.substring(ini.length)
        ini = when (ini) {
            "zh" -> "z"; "ch" -> "c"; "sh" -> "s"; "n", "r" -> "l"; "f" -> "h"; "y", "w" -> ""
            else -> ini
        }
        // y·w 가 붙은 운모 정리 (ya→a, yi→i, wu→u, you→ou)
        if (fin.isEmpty()) fin = "i"
        fin = fin.removeSuffix("g").removeSuffix("n")
        fin = fin.replace('v', 'u')
        if (fin.length > 1 && (fin.startsWith("i") || fin.startsWith("u"))) {
            // ia→a, ian→a, uo→o 처럼 앞 반모음은 약하게 들리는 경우가 많음
            fin = fin.substring(1)
        }
        s = ini + fin
        return s
    }

    private fun looseSame(t: Map<Char, List<String>>, a: Char, b: Char): Boolean {
        if (sameSound(t, a, b)) return true
        val pa = t[a] ?: return false
        val pb = t[b] ?: return false
        return pa.any { x -> pb.any { y -> loose(x) == loose(y) } }
    }

    // 발음이 완전히 같은 곳만 바로 교체
    fun fix(ctx: Context, text: String, glossary: List<Pair<String, String>>): String {
        if (glossary.isEmpty() || text.isEmpty()) return text
        val t = try { load(ctx) } catch (_: Exception) { return text }
        var s = text
        for ((term, _) in glossary) {
            val n = term.length
            if (n < 2 || term.any { it !in t } || s.length < n) continue
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

    // 잘못 인식된 형태(직접 확인해서 저장한 것) 바로 교체
    fun applyAliases(text: String, aliases: List<Pair<String, String>>): String {
        var s = text
        for ((wrong, right) in aliases) if (wrong.isNotEmpty()) s = s.replace(wrong, right)
        return s
    }

    // 제미나이용 후보 찾기. prevTail: 바로 앞 줄의 끝 몇 글자 (이름이 줄 경계에 걸린 경우용)
    fun hints(
        ctx: Context,
        text: String,
        prevTail: String,
        glossary: List<Pair<String, String>>,
        aliases: List<Pair<String, String>>,
    ): List<Hint> {
        val out = ArrayList<Hint>()
        val ko = glossary.toMap()
        // 1) 직접 저장해둔 잘못 인식된 형태
        for ((wrong, right) in aliases) {
            if (text.contains(wrong)) out.add(Hint(wrong, right, ko[right] ?: "", "예전에 이렇게 잘못 인식된 적 있음"))
        }
        val t = runCatching { load(ctx) }.getOrNull() ?: return out.take(6)
        val joined = prevTail + text
        val cut = prevTail.length
        for ((term, k) in glossary) {
            val n = term.length
            if (n < 2 || term.any { it !in t } || joined.length < n) continue
            if (text.contains(term)) continue
            for (i in 0..joined.length - n) {
                val w = joined.substring(i, i + n)
                if (w == term) {
                    // 앞 줄과 이어 붙였을 때만 정확히 맞는 경우 (이름이 줄 경계에서 잘림)
                    if (i < cut) out.add(Hint(w, term, k, "앞 줄 끝과 이어진 이름일 수 있음"))
                    continue
                }
                val exact = w.indices.all { sameSound(t, w[it], term[it]) }
                val looseN = w.indices.count { looseSame(t, w[it], term[it]) }
                val similar = exact || looseN == n || (n >= 3 && looseN >= n - 1 && w.indices.any { w[it] == term[it] })
                if (!similar) continue
                val kind = when {
                    i < cut -> "앞 줄 끝과 이어서 비슷한 소리"
                    exact -> "발음이 같음"
                    else -> "발음이 비슷함"
                }
                if (out.none { it.found == w && it.term == term }) out.add(Hint(w, term, k, kind))
            }
        }
        return out.take(6)
    }
}
