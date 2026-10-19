package app.cap.livetr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// 화면 위에 뜨는 자막 기록창
// · 새 문장은 맨 아래에 추가, 이전 문장은 위로 밀려 올라감 (스크롤로 다시 보기)
// · 맨 위 줄 아무 데나 끌면 이동, 오른쪽 아래 ◢ 로 크기 조절, 原 버튼을 톡 누르면 원문 켜기/끄기
// · 말하는 도중에는 맨 아래에 중국어를 미리 보여줌 (▸ 줄)
// · 말이 한동안 없으면 흐려짐
// · '위치 잠금'이면 터치가 전부 영상으로 통과
// onFix: 자막 줄을 길게 누르면 (줄 번호, 중국어, 한국어, 앞뒤 자막 중국어)로 불림 → 번역 고치기 화면
class Overlay(private val ctx: Context, private val onFix: ((Long, String, String, List<String>) -> Unit)? = null) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private val root = LinearLayout(ctx)
    private val header = LinearLayout(ctx)
    private val handle = TextView(ctx)
    private val statusView = TextView(ctx)
    private val origBtn = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val content = LinearLayout(ctx)
    private val list = LinearLayout(ctx)
    private val liveView = TextView(ctx)
    private val resizer = TextView(ctx)

    private class Entry(
        val id: Long,
        val box: LinearLayout,
        val orig: TextView,
        val tr: TextView,
        var origText: String,   // 화면에 보이는 원문 (↩구글 같은 표시 포함)
        var rawZh: String,      // 순수 중국어 원문
        var ko: String = "",    // 순수 번역문
    )
    private val entries = LinkedHashMap<Long, Entry>()
    private var nextId = 1L
    private var follow = true
    private var added = false
    private var errorText: String? = null
    private var lastSize = -1
    private var expanded = false
    private var normalH = 0
    private var lastTapAt = 0L

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )
    private val dimTask = Runnable { root.animate().alpha(0.35f).setDuration(400).start() }

    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        ui.post {
            if (added) return@post
            val dm = ctx.resources.displayMetrics
            root.orientation = LinearLayout.VERTICAL
            root.background = GradientDrawable().apply {
                setColor(Color.argb(180, 0, 0, 0))
                cornerRadius = dp(10f).toFloat()
            }

            header.orientation = LinearLayout.HORIZONTAL
            header.gravity = Gravity.CENTER_VERTICAL
            handle.text = "≡"
            handle.setTextColor(Color.WHITE)
            handle.textSize = 20f
            handle.setPadding(dp(12f), dp(6f), dp(8f), dp(6f))
            statusView.setTextColor(Color.rgb(170, 170, 170))
            statusView.textSize = 11f
            statusView.maxLines = 2
            origBtn.textSize = 14f
            origBtn.setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            header.minimumHeight = dp(36f)
            header.addView(handle)
            header.addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(origBtn)
            refreshOrigBtn()

            list.orientation = LinearLayout.VERTICAL
            list.setPadding(dp(12f), 0, dp(12f), 0)
            liveView.setTextColor(Color.rgb(140, 200, 255))
            liveView.setPadding(dp(12f), 0, dp(12f), dp(4f))
            liveView.visibility = View.GONE
            content.orientation = LinearLayout.VERTICAL
            content.addView(list)
            content.addView(liveView)
            scroll.addView(content)
            scroll.isVerticalScrollBarEnabled = true

            // 아래 테두리 전체가 크기 조절 손잡이 (위아래로 끌면 높이, 오른쪽 끝 ◢ 를 끌면 폭도)
            resizer.text = "━━━━━━              ◢"
            resizer.setTextColor(Color.rgb(140, 140, 140))
            resizer.textSize = 15f
            resizer.gravity = Gravity.CENTER
            resizer.setPadding(dp(12f), dp(4f), dp(8f), dp(6f))

            root.addView(header)
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(resizer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val p = Prefs.get(ctx)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.width = p.getInt("boxW", (dm.widthPixels * 0.94f).toInt())
            lp.height = p.getInt("boxH", dp(170f))
            lp.x = p.getInt("posX", (dm.widthPixels * 0.03f).toInt())
            lp.y = p.getInt("posY", (dm.heightPixels * 0.60f).toInt())
            clamp()
            applyLock()

            // 스크롤 위치 추적: 맨 아래를 보고 있을 때만 새 문장을 따라 내려감
            scroll.setOnScrollChangeListener { _, _, _, _, _ ->
                follow = atBottom()
                wake()
            }
            scroll.setOnTouchListener { _, _ -> wake(); false }

            // 맨 위 줄 전체로 끌어서 이동. 原 버튼은 톡 누르면 원문 켜기/끄기, 끌면 이동.
            val drag = object : View.OnTouchListener {
                var dx0 = 0f; var dy0 = 0f; var x0 = 0; var y0 = 0
                var moved = false
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            dx0 = e.rawX; dy0 = e.rawY; x0 = lp.x; y0 = lp.y; moved = false; wake()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val ddx = e.rawX - dx0
                            val ddy = e.rawY - dy0
                            if (!moved && (ddx * ddx + ddy * ddy) > dp(8f) * dp(8f)) moved = true
                            if (moved) {
                                lp.x = x0 + ddx.toInt()
                                lp.y = y0 + ddy.toInt()
                                clamp(); update()
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (moved) {
                                Prefs.get(ctx).edit().putInt("posX", lp.x).putInt("posY", lp.y).apply()
                            } else if (v === origBtn) {
                                val now = !Prefs.showOrig(ctx)
                                Prefs.get(ctx).edit().putBoolean("showOrig", now).apply()
                                refreshAll()
                            } else {
                                // 맨 위 줄 두 번 톡 → 펼치기/접기
                                val t = android.os.SystemClock.uptimeMillis()
                                if (t - lastTapAt < 350) {
                                    lastTapAt = 0
                                    toggleExpand()
                                } else {
                                    lastTapAt = t
                                }
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (moved) Prefs.get(ctx).edit().putInt("posX", lp.x).putInt("posY", lp.y).apply()
                        }
                    }
                    return true
                }
            }
            header.setOnTouchListener(drag)
            handle.setOnTouchListener(drag)
            statusView.setOnTouchListener(drag)
            origBtn.setOnTouchListener(drag)

            resizer.setOnTouchListener(object : View.OnTouchListener {
                var dx0 = 0f; var dy0 = 0f; var w0 = 0; var h0 = 0
                var widthToo = false
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            dx0 = e.rawX; dy0 = e.rawY; w0 = lp.width; h0 = lp.height
                            widthToo = e.x > v.width * 0.75f   // 오른쪽 끝을 잡았을 때만 폭도 바꿈
                            expanded = false
                            wake()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dm2 = ctx.resources.displayMetrics
                            if (widthToo) {
                                lp.width = (w0 + (e.rawX - dx0).toInt()).coerceIn(dm2.widthPixels * 4 / 10, dm2.widthPixels)
                            }
                            lp.height = (h0 + (e.rawY - dy0).toInt()).coerceIn(dp(80f), dm2.heightPixels * 8 / 10)
                            clamp(); update()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            Prefs.get(ctx).edit().putInt("boxW", lp.width).putInt("boxH", lp.height).apply()
                            if (follow) scrollToEnd()
                        }
                    }
                    return true
                }
            })

            wm.addView(root, lp)
            added = true
            statusView.text = "준비 중…"
            wake()
        }
    }

    // ── 상태줄 ──
    // 일반 상태 (오류가 떠 있으면 오류를 덮어쓰지 않음)
    fun info(msg: String) = ui.post {
        if (!added) return@post
        if (errorText == null) {
            statusView.setTextColor(Color.rgb(170, 170, 170))
            statusView.text = msg
        }
        applyLock()
    }

    // 오류: 다음에 정상 동작할 때까지 빨간 글씨로 남음
    fun error(msg: String) = ui.post {
        if (!added) return@post
        errorText = msg
        statusView.setTextColor(Color.rgb(255, 110, 110))
        statusView.text = msg
        wake()
    }

    fun clearError() = ui.post { errorText = null }

    // ── 말하는 도중 미리 보기 줄 ──
    // zh: 지금까지 들은 중국어, ko: 미리 번역 (없으면 빈 문자열)
    fun live(zh: String, ko: String = "") = ui.post {
        if (!added) return@post
        if (zh.isEmpty() && ko.isEmpty()) {
            liveView.visibility = View.GONE
            return@post
        }
        liveView.textSize = Prefs.textSize(ctx) * 0.8f
        val showZh = Prefs.showOrig(ctx) || ko.isEmpty()
        liveView.text = buildString {
            append("▸ ")
            if (showZh) append(zh)
            if (ko.isNotEmpty()) {
                if (showZh) append("\n   ")
                append(ko).append(" …")
            }
        }
        liveView.visibility = View.VISIBLE
        if (follow) scrollToEnd()
        wake()
    }

    // ── 자막 줄 ──
    // 원문을 먼저 넣고(번역 중 "…"), 번역이 오면 update로 채움
    fun add(orig: String): Long {
        val id = nextId++
        ui.post {
            if (!added) return@post
            errorText = null
            liveView.visibility = View.GONE
            val size = Prefs.textSize(ctx).toFloat()
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(3f), 0, dp(5f))
            }
            val o = TextView(ctx).apply {
                setTextColor(Color.rgb(190, 190, 190))
                textSize = size * 0.7f
            }
            val t = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = size
                text = "…"
            }
            box.addView(o)
            box.addView(t)
            val e = Entry(id, box, o, t, orig, orig)
            // 길게 누르면 이 줄의 번역 고치기
            box.isLongClickable = true
            box.setOnLongClickListener {
                if (e.ko.isNotEmpty() && e.ko != "…") onFix?.invoke(e.id, e.rawZh, e.ko, around(e.id))
                true
            }
            entries[id] = e
            fill(e)
            val wasFollowing = follow || entries.size <= 1
            list.addView(box)
            while (entries.size > 60) {
                val firstKey = entries.keys.first()
                entries.remove(firstKey)?.let { list.removeView(it.box) }
            }
            if (lastSize != size.toInt()) refreshAll()
            if (wasFollowing) scrollToEnd()
            wake()
        }
        return id
    }

    fun update(id: Long, tr: String, tag: String) = ui.post {
        val e = entries[id] ?: return@post
        e.ko = tr
        e.tr.text = splitForReading(tr)
        if (tag.isNotEmpty()) e.origText = "${e.origText}   $tag"
        fill(e)
        if (follow) scrollToEnd()
        wake()
    }

    // 원문 줄만 바꾸기 (제미나이 통역 모드에서 원문이 조금씩 들어올 때)
    fun setOrig(id: Long, orig: String) = ui.post {
        val e = entries[id] ?: return@post
        e.origText = orig
        e.rawZh = orig
        fill(e)
        if (follow) scrollToEnd()
    }

    companion object {
        // 넷플릭스 한국어 자막 기준: 한 줄 16자 × 2줄 ≈ 32자.
        // 이보다 긴 번역은 문장부호나 띄어쓰기 자리에서 나눠 줄바꿈해서 읽기 쉽게.
        const val MAX_CHUNK = 32

        fun splitForReading(text: String): String {
            val t = text.trim()
            if (t.length <= MAX_CHUNK) return t
            val out = ArrayList<String>()
            var rest = t
            while (rest.length > MAX_CHUNK) {
                val window = rest.substring(0, MAX_CHUNK + 1)
                var cut = -1
                // 1순위: 문장 끝 / 2순위: 쉼표 / 3순위: 띄어쓰기 (너무 앞에서 자르진 않음)
                for (marks in listOf(".?!。？！…", ",，、;；", " ")) {
                    for (i in window.length - 1 downTo MAX_CHUNK / 3) {
                        if (marks.indexOf(window[i]) >= 0) { cut = i + 1; break }
                    }
                    if (cut > 0) break
                }
                if (cut <= 0) cut = MAX_CHUNK
                out.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out.joinToString("\n")
        }
    }

    private fun fill(e: Entry) {
        e.orig.text = e.origText
        e.orig.visibility = if (Prefs.showOrig(ctx) && e.origText.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshAll() {
        val size = Prefs.textSize(ctx).toFloat()
        lastSize = size.toInt()
        for (e in entries.values) {
            e.tr.textSize = size
            e.orig.textSize = size * 0.7f
            fill(e)
        }
        refreshOrigBtn()
        if (follow) scrollToEnd()
    }

    private fun refreshOrigBtn() {
        val on = Prefs.showOrig(ctx)
        origBtn.text = if (on) "原 켬" else "原 끔"
        origBtn.setTextColor(if (on) Color.WHITE else Color.rgb(120, 120, 120))
    }

    // 펼치기: 화면의 60% 높이로 (지난 번역을 많이 보기). 다시 두 번 톡 하면 원래 크기로.
    private fun toggleExpand() {
        val dm = ctx.resources.displayMetrics
        if (expanded) {
            lp.height = if (normalH > 0) normalH else dp(170f)
            expanded = false
        } else {
            normalH = lp.height
            lp.height = (dm.heightPixels * 0.6f).toInt()
            expanded = true
            // 펼친 창이 화면 아래로 넘치지 않게 위로 올림
            if (lp.y + lp.height > dm.heightPixels) lp.y = maxOf(0, dm.heightPixels - lp.height)
        }
        clamp(); update()
        if (follow) scrollToEnd()
        wake()
    }

    // 번역 고치기에 넘길 앞뒤 자막 (중국어, 앞 3줄 + 뒤 2줄)
    private fun around(id: Long): List<String> {
        val keys = entries.keys.toList()
        val i = keys.indexOf(id)
        if (i < 0) return emptyList()
        return (maxOf(0, i - 3) until minOf(keys.size, i + 3))
            .filter { it != i }
            .mapNotNull { entries[keys[it]]?.rawZh }
            .filter { it.isNotEmpty() }
    }

    private fun atBottom(): Boolean {
        val child = content
        return scroll.scrollY + scroll.height >= child.height - dp(24f)
    }

    private fun scrollToEnd() {
        scroll.post { scroll.scrollTo(0, content.height); follow = true }
    }

    private fun wake() {
        root.animate().cancel()
        root.alpha = 1f
        ui.removeCallbacks(dimTask)
        ui.postDelayed(dimTask, 8000)
    }

    private fun applyLock() {
        val locked = Prefs.locked(ctx)
        val f = if (locked) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (f != lp.flags) {
            lp.flags = f
            update()
        }
        handle.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        resizer.visibility = if (locked) View.INVISIBLE else View.VISIBLE
    }

    private fun clamp() {
        val dm = ctx.resources.displayMetrics
        if (lp.width > dm.widthPixels) lp.width = dm.widthPixels
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - dp(60f)))
    }

    private fun update() {
        if (added) {
            try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
    }

    fun remove() = ui.post {
        if (added) {
            ui.removeCallbacks(dimTask)
            try { wm.removeView(root) } catch (_: Exception) {}
            added = false
        }
    }
}
