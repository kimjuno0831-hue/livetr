package app.cap.livetr

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
// 1) 번역문에서 틀린 단어를 선택 → 제미나이가 원문의 어느 중국어인지 찾아줌 (또는 원문에서 직접 선택)
// 2) 원하는 한국어 입력 → 용어집에 저장 → 그 줄을 다시 번역
class FixActivity : Activity() {
    private lateinit var zhView: EditText
    private lateinit var koView: EditText
    private lateinit var zhField: EditText
    private lateinit var koField: EditText
    private lateinit var info: TextView
    private var id = -1L
    private var zh = ""
    private var ko = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra("id", -1)
        zh = intent.getStringExtra("zh") ?: ""
        ko = intent.getStringExtra("ko") ?: ""
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

        col.addView(label("중국어 원문 (길게 눌러 선택할 수 있어요)"))
        zhView = readOnly(zh, 17f)
        col.addView(zhView)
        col.addView(label("한국어 번역 (틀린 단어를 길게 눌러 선택하세요)"))
        koView = readOnly(ko, 17f).apply { typeface = Typeface.DEFAULT_BOLD }
        col.addView(koView)

        col.addView(Button(this).apply {
            text = "① 선택한 번역 단어의 원문 찾기 (제미나이)"
            setOnClickListener { findSource() }
        })
        col.addView(Button(this).apply {
            text = "또는: 원문에서 선택한 부분 그대로 쓰기"
            setOnClickListener {
                val sel = selected(zhView)
                if (sel.isEmpty()) toast("중국어 원문에서 먼저 글자를 선택하세요")
                else zhField.setText(sel)
            }
        })

        col.addView(label("고칠 중국어 단어"))
        zhField = EditText(this).apply {
            setSingleLine(true)
            hint = "예) 派蒙"
        }
        col.addView(zhField)
        col.addView(label("앞으로 이렇게 번역해줘"))
        koField = EditText(this).apply {
            setSingleLine(true)
            hint = "예) 페이몬"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(koField)

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
        row.addView(Button(this).apply {
            text = "취소"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "② 저장하고 다시 번역"
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
                        zhField.setText(found)
                        info.text = "'$word' ← '$found' 로 찾았어요. 맞는지 확인하고, 아래에 원하는 번역을 적으세요."
                    }
                    else -> info.text = "정확히 찾지 못했어요 (제미나이 답: $found). 원문에서 직접 선택해 주세요."
                }
            }
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

        // 이 줄 다시 번역
        if (id >= 0 && zh.isNotEmpty()) {
            try {
                startService(
                    Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_RETRANSLATE)
                        .putExtra("id", id)
                        .putExtra("zh", zh)
                )
            } catch (_: Exception) {}
        }
        toast("용어집에 저장했어요: $line")
        finish()
    }
}
