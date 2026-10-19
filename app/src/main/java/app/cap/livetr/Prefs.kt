package app.cap.livetr

import android.content.Context
import android.content.SharedPreferences

// 앱 설정값 저장소. 설정은 자막이 켜져 있어도 바로 반영되도록 매번 새로 읽는다.
object Prefs {
    const val ENGINE_GOOGLE = "google"
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_MLKIT = "mlkit"
    const val ENGINE_LIVE = "live"   // 제미나이 통역 (소리를 바로 번역)
    const val ENGINE_HEAR = "hear"   // 제미나이 듣고 번역 (말소리 조각 + 용어집·문맥)

    // 맥락을 보고 판단할 수 있는 제미나이 계열 번역기인지
    fun geminiFamily(engine: String) = engine == ENGINE_GEMINI || engine == ENGINE_LIVE || engine == ENGINE_HEAR
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

    // 말하는 도중 미리 번역 (구글 번역으로)
    fun partialTr(ctx: Context) = get(ctx).getBoolean("partialTr", true)

    // 발음이 같은데 글자만 다르게 인식된 용어집 단어를 자동으로 고치기
    fun pinyinFix(ctx: Context) = get(ctx).getBoolean("pinyinFix", true)

    // ── 작품 ──
    // 등록한 작품 목록과 지금 보는 작품(여러 개 선택 가능). 작품마다 용어집이 따로 있음.
    private fun jsonList(ctx: Context, key: String): MutableList<String> {
        val raw = get(ctx).getString(key, null) ?: return mutableListOf()
        return runCatching {
            val a = org.json.JSONArray(raw)
            MutableList(a.length()) { a.getString(it) }
        }.getOrElse { mutableListOf() }
    }

    private fun putList(ctx: Context, key: String, v: List<String>) {
        val a = org.json.JSONArray()
        v.forEach { a.put(it) }
        get(ctx).edit().putString(key, a.toString()).apply()
    }

    fun works(ctx: Context): List<String> {
        // 예전 버전의 '지금 보는 내용' 한 줄을 작품 하나로 옮김
        if (!get(ctx).contains("works")) {
            val old = (get(ctx).getString("topic", "") ?: "").trim()
            val init = if (old.isNotEmpty()) listOf(old) else emptyList()
            putList(ctx, "works", init)
            putList(ctx, "activeWorks", init)
            return init
        }
        return jsonList(ctx, "works")
    }

    fun activeWorks(ctx: Context): List<String> {
        val all = works(ctx)
        return jsonList(ctx, "activeWorks").filter { it in all }
    }

    fun addWork(ctx: Context, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val all = works(ctx).toMutableList()
        if (n !in all) all.add(n)
        putList(ctx, "works", all)
    }

    fun removeWork(ctx: Context, name: String) {
        putList(ctx, "works", works(ctx).filter { it != name })
        putList(ctx, "activeWorks", activeWorks(ctx).filter { it != name })
        get(ctx).edit().remove("gl:$name").remove("al:$name").apply()
    }

    fun setActive(ctx: Context, name: String, on: Boolean) {
        val cur = activeWorks(ctx).toMutableList()
        if (on && name !in cur) cur.add(name)
        if (!on) cur.remove(name)
        putList(ctx, "activeWorks", cur)
    }

    // 번역·표기 찾기에 넘기는 '지금 보는 작품' 설명
    fun topic(ctx: Context) = activeWorks(ctx).joinToString(", ")

    // ── 용어집 ──
    // 공통 용어집 (작품과 상관없이 늘 쓰는 단어)
    fun glossaryText(ctx: Context) = get(ctx).getString("glossary", "") ?: ""

    fun workGlossaryText(ctx: Context, work: String) = get(ctx).getString("gl:$work", "") ?: ""

    fun setWorkGlossary(ctx: Context, work: String, text: String) =
        get(ctx).edit().putString("gl:$work", text).apply()

    // work 가 null 이면 공통 용어집
    fun glossaryTextOf(ctx: Context, work: String?) =
        if (work == null) glossaryText(ctx) else workGlossaryText(ctx, work)

    fun setGlossaryOf(ctx: Context, work: String?, text: String) {
        if (work == null) get(ctx).edit().putString("glossary", text).apply()
        else setWorkGlossary(ctx, work, text)
    }

    // 한 용어집에 '중국어=한국어' 한 줄 추가 (같은 중국어가 있으면 바꿔치기)
    fun putEntry(ctx: Context, work: String?, zh: String, ko: String) {
        val lines = glossaryTextOf(ctx, work).lines().filter { it.isNotBlank() }.toMutableList()
        val idx = lines.indexOfFirst { it.substringBefore('=').trim() == zh }
        val line = "$zh=$ko"
        if (idx >= 0) lines[idx] = line else lines.add(line)
        setGlossaryOf(ctx, work, lines.joinToString("\n"))
    }

    // "原神=원신" 형식의 줄들을 (중국어, 한국어) 목록으로
    fun parse(text: String): List<Pair<String, String>> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .mapNotNull {
                val i = it.indexOf('=')
                val zh = it.substring(0, i).trim()
                val ko = it.substring(i + 1).trim()
                if (zh.isEmpty() || ko.isEmpty()) null else Pair(zh, ko)
            }

    // ── 잘못 인식된 형태 기억 (예: 安米安>阿米娅) ──
    // 폰 음성 인식이 같은 이름을 같은 식으로 틀리는 경우가 많아서, 고치기 할 때 자동으로 같이 저장함
    private fun aliasKey(work: String?) = if (work == null) "aliases" else "al:$work"

    fun putAlias(ctx: Context, work: String?, wrong: String, right: String) {
        if (wrong.isEmpty() || wrong == right || wrong.contains('>') || wrong.contains('\n')) return
        val lines = (get(ctx).getString(aliasKey(work), "") ?: "").lines()
            .filter { it.isNotBlank() && it.substringBefore('>') != wrong }.toMutableList()
        lines.add("$wrong>$right")
        get(ctx).edit().putString(aliasKey(work), lines.joinToString("\n")).apply()
    }

    // 공통 + 지금 보는 작품들. 결과: (잘못 인식된 형태, 올바른 중국어). 긴 것부터.
    fun aliases(ctx: Context): List<Pair<String, String>> {
        val m = LinkedHashMap<String, String>()
        (listOf<String?>(null) + activeWorks(ctx)).forEach { w ->
            (get(ctx).getString(aliasKey(w), "") ?: "").lines().forEach { l ->
                val i = l.indexOf('>')
                if (i > 0 && i < l.length - 1) m[l.substring(0, i).trim()] = l.substring(i + 1).trim()
            }
        }
        return m.entries.map { Pair(it.key, it.value) }.sortedByDescending { it.first.length }
    }

    // 실제 번역에 쓰는 용어집: 공통 + 지금 보는 작품들 (작품 쪽이 우선). 긴 단어부터.
    fun glossary(ctx: Context): List<Pair<String, String>> {
        val m = LinkedHashMap<String, String>()
        parse(glossaryText(ctx)).forEach { m[it.first] = it.second }
        activeWorks(ctx).forEach { w -> parse(workGlossaryText(ctx, w)).forEach { m[it.first] = it.second } }
        return m.entries.map { Pair(it.key, it.value) }.sortedByDescending { it.first.length }
    }
}
