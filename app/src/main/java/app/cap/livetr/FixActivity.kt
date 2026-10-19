package app.cap.livetr

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

// 자막 줄을 길게 눌렀을 때 뜨는 "번역 고치기" 창
// ① 번역문에서 틀린 단어 선택 → 원문의 어느 중국어인지 찾기 (폰이 받아 적은 글자 기준)
// ② 원하는 한국어 이름으로 '올바른' 중국어 표기 찾기: 위키 → 제미나이 추측 → 웹 검색
// ③ 저장 → 용어집에 올바른 중국어로 저장, 그 줄 다시 번역
//    (폰이 발음은 같은 다른 글자로 받아 적어도 다음부터 발음 기준으로 자동 교정됨)
class FixActivity : Activity() {
    private lateinit var zhView: EditText
    private lateinit var koView: EditText
    private lateinit var zhField: EditText
    private lateinit var koField: EditText
    private lateinit var info: TextView
    private lateinit var candBox: LinearLayout
    private var id = -1L
    private var zh = ""
    private var ko = ""
    private var around: List<String> = emptyList()
    private var recognizedPart = ""   // 폰이 받아 적은 원문 중 고칠 부분

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra("id", -1)
        zh = intent.getStringExtra("zh") ?: ""
        ko = intent.getStringExtra("ko") ?: ""
        around = intent.getStringArrayListExtra("around") ?: emptyList()
        title = "번역 고치기"

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        // 글자를 손가락으로 선택만 할 수 있고 고칠 수는 없는 칸
        fun readOnly(t: String, size: Float) = EditText(this).apply {
            setText(t)
            textSize = size
            keyListener = null
            setTextIsSelectable(true)
            showSoftInputOnFocus = false
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        col.addView(label("중국어 원문 (폰이 받아 적은 것, 길게 눌러 선택 가능)"))
        zhView = readOnly(zh, 17f)
        col.addView(zhView)
        col.addView(label("한국어 번역 (틀린 단어를 길게 눌러 선택하세요)"))
        koView = readOnly(ko, 17f).apply { typeface = Typeface.DEFAULT_BOLD }
        col.addView(koView)
        col.addView(button("① 선택한 번역 단어의 원문 찾기") { findSource() })

        col.addView(label("앞으로 이렇게 번역해줘 (한국어)"))
        koField = EditText(this).apply {
            setSingleLine(true)
            hint = "예) 페이몬"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(koField)
        col.addView(button("② 이 이름의 올바른 중국어 표기 찾기 (위키 → 제미나이)") { lookup() })
        candBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(candBox)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("웹에서 찾아보기") {
            val k = koField.text.toString().trim()
            if (k.isEmpty()) toast("먼저 한국어 이름을 적어주세요")
            else runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Lookup.webSearchUrl(this, k)))) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(button("원문 선택 부분 쓰기") {
            val sel = selected(zhView)
            if (sel.isEmpty()) toast("중국어 원문에서 먼저 글자를 선택하세요")
            else {
                recognizedPart = sel
                zhField.setText(sel)
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row1)

        col.addView(label("저장할 중국어 단어 (올바른 표기)"))
        zhField = EditText(this).apply {
            setSingleLine(true)
            hint = "예) 派蒙"
        }
        col.addView(zhField)

        info = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, (8 * d).toInt(), 0, 0)
            text = if (Prefs.engine(this@FixActivity) == Prefs.ENGINE_LIVE) {
                "참고: '제미나이 통역' 모드는 용어집을 지원하지 않아서, 저장한 내용은 다른 번역기로 볼 때 반영돼요. 이 줄은 저장하면 바로 다시 번역해서 보여줘요."
            } else ""
        }
        col.addView(info)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("취소") { finish() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("③ 저장하고 다시 번역") { save() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        setContentView(ScrollView(this).apply { addView(col) })
        window?.setLayout((resources.displayMetrics.widthPixels * 0.95f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun selected(v: EditText): String {
        val a = v.selectionStart
        val b = v.selectionEnd
        if (a < 0 || b < 0 || a == b) return ""
        return v.text.substring(minOf(a, b), maxOf(a, b)).trim()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ① 번역 단어 → 폰이 받아 적은 원문의 어느 부분인지
    private fun findSource() {
        val word = selected(koView)
        if (word.isEmpty()) {
            toast("한국어 번역에서 틀린 단어를 먼저 길게 눌러 선택하세요")
            return
        }
        if (koField.text.isEmpty()) koField.setText(word)
        if (Prefs.geminiKey(this).isEmpty()) {
            info.text = "제미나이 키가 없어서 자동으로 찾을 수 없어요. 원문에서 직접 선택해 주세요."
            return
        }
        info.text = "'$word'의 원문을 찾는 중…"
        thread {
            val tr = Translators(applicationContext)
            val res = runCatching { tr.findSource(zh, ko, word) }
            tr.close()
            runOnUiThread {
                val found = res.getOrNull()?.trim().orEmpty()
                when {
                    res.isFailure -> info.text = "찾지 못했어요: ${res.exceptionOrNull()?.message}\n원문에서 직접 선택해 주세요."
                    found.isNotEmpty() && zh.contains(found) -> {
                        recognizedPart = found
                        if (zhField.text.isEmpty()) zhField.setText(found)
                        info.text = "'$word' ← 폰이 받아 적은 '$found'. 이 글자가 잘못 인식됐을 수 있으니, " +
                            "아래 칸에 원하는 이름을 적고 ②로 올바른 표기를 확인해보세요."
                    }
                    else -> info.text = "정확히 찾지 못했어요 (제미나이 답: $found). 원문에서 직접 선택해 주세요."
                }
            }
        }
    }

    // ② 한국어 이름 → 올바른 중국어 표기 후보
    private fun lookup() {
        val k = koField.text.toString().trim()
        if (k.isEmpty()) {
            toast("먼저 '앞으로 이렇게 번역해줘' 칸에 한국어 이름을 적어주세요")
            return
        }
        candBox.removeAllViews()
        info.text = "위키에서 '$k' 찾는 중…"
        thread {
            val wiki = runCatching { Lookup.wiki(k) }.getOrElse { emptyList() }
            val cands = ArrayList(wiki)
            var note = ""
            if (cands.isEmpty()) {
                runOnUiThread { info.text = "위키에 없어서 제미나이에게 물어보는 중…" }
                val g = runCatching { Lookup.guess(applicationContext, k, recognizedPart.ifEmpty { zh }, around + zh) }
                g.getOrNull()?.let { cands.add(it) }
                if (g.isFailure) note = " (제미나이 오류: ${g.exceptionOrNull()?.message})"
                else if (Prefs.geminiKey(this).isEmpty()) note = " (제미나이 키가 없어서 추측은 못 했어요)"
            }
            runOnUiThread { showCandidates(k, cands, note) }
        }
    }

    private fun showCandidates(k: String, cands: List<Lookup.Candidate>, note: String) {
        candBox.removeAllViews()
        if (cands.isEmpty()) {
            info.text = "'$k'의 표기를 찾지 못했어요$note. [웹에서 찾아보기]로 직접 확인해주세요."
            return
        }
        info.text = "후보를 눌러서 고르세요. 출처가 '제미나이 추측'이면 한 번 더 확인해보세요."
        for (c in cands) {
            candBox.addView(Button(this).apply {
                isAllCaps = false
                text = "${c.zh}   [${c.source}]" + if (c.note.isNotEmpty()) "\n${c.note}" else ""
                setOnClickListener { pick(c) }
            })
        }
    }

    private fun pick(c: Lookup.Candidate) {
        zhField.setText(c.zh)
        val r = recognizedPart
        info.text = when {
            r.isEmpty() || r == c.zh -> "'${c.zh}'(으)로 저장할게요."
            Pinyin.fix(this, r, listOf(Pair(c.zh, ""))) == c.zh ->
                "폰이 '$r'(으)로 받아 적었지만 발음이 같아요. 저장하면 다음부터 자동으로 '${c.zh}'(으)로 바로잡아요."
            else -> "폰이 받아 적은 '$r'와 발음이 달라요. '${c.zh}'(으)로 저장하면, 같은 잘못 인식은 자동 교정이 안 될 수 있어요. " +
                "그때는 '$r'도 따로 저장해두면 돼요."
        }
    }

    private fun save() {
        val z = zhField.text.toString().trim()
        val k = koField.text.toString().trim()
        if (z.isEmpty() || k.isEmpty()) {
            toast("중국어 단어와 원하는 번역을 모두 적어주세요")
            return
        }
        if (z.contains('=') || z.contains('\n') || k.contains('\n')) {
            toast("'=' 기호나 줄바꿈은 쓸 수 없어요")
            return
        }
        // 같은 중국어 단어가 이미 있으면 바꿔치기, 없으면 맨 아래에 추가
        val p = Prefs.get(this)
        val lines = Prefs.glossaryText(this).lines().filter { it.isNotBlank() }.toMutableList()
        val idx = lines.indexOfFirst { it.substringBefore('=').trim() == z }
        val line = "$z=$k"
        if (idx >= 0) lines[idx] = line else lines.add(line)
        p.edit().putString("glossary", lines.joinToString("\n")).apply()

        // 이 줄 다시 번역 (발음 교정도 다시 적용)
        if (id >= 0 && zh.isNotEmpty()) {
            val fixed = if (Prefs.pinyinFix(this)) Pinyin.fix(this, zh, Prefs.glossary(this)) else zh
            try {
                startService(
                    Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_RETRANSLATE)
                        .putExtra("id", id)
                        .putExtra("zh", fixed)
                )
            } catch (_: Exception) {}
        }
        toast("용어집에 저장했어요: $line")
        finish()
    }
}
