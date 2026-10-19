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
// · 위쪽 ≡ 손잡이로 이동, 오른쪽 아래 ◢ 로 크기 조절, 原 버튼으로 원문 켜기/끄기
// · 말이 한동안 없으면 흐려짐
// · '위치 잠금'이면 터치가 전부 영상으로 통과
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private val root = LinearLayout(ctx)
    private val header = LinearLayout(ctx)
    private val handle = TextView(ctx)
    private val statusView = TextView(ctx)
    private val origBtn = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val resizer = TextView(ctx)

    private class Entry(val box: LinearLayout, val orig: TextView, val tr: TextView, var origText: String)
    private val entries = LinkedHashMap<Long, Entry>()
    private var nextId = 1L
    private var follow = true
    private var added = false
    private var errorText: String? = null
    private var lastSize = -1

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
            handle.setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
            statusView.setTextColor(Color.rgb(170, 170, 170))
            statusView.textSize = 11f
            statusView.maxLines = 2
            origBtn.textSize = 14f
            origBtn.setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
            header.addView(handle)
            header.addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(origBtn)
            refreshOrigBtn()

            list.orientation = LinearLayout.VERTICAL
            list.setPadding(dp(12f), 0, dp(12f), dp(4f))
            scroll.addView(list)
            scroll.isVerticalScrollBarEnabled = true

            resizer.text = "◢"
            resizer.setTextColor(Color.rgb(150, 150, 150))
            resizer.textSize = 14f
            resizer.gravity = Gravity.END
            resizer.setPadding(dp(12f), 0, dp(6f), dp(2f))

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

            origBtn.setOnClickListener {
                val now = !Prefs.showOrig(ctx)
                Prefs.get(ctx).edit().putBoolean("showOrig", now).apply()
                refreshAll()
                wake()
            }

            handle.setOnTouchListener(object : View.OnTouchListener {
                var dx0 = 0f; var dy0 = 0f; var x0 = 0; var y0 = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; x0 = lp.x; y0 = lp.y; wake() }
                        MotionEvent.ACTION_MOVE -> {
                            lp.x = x0 + (e.rawX - dx0).toInt()
                            lp.y = y0 + (e.rawY - dy0).toInt()
                            clamp(); update()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            Prefs.get(ctx).edit().putInt("posX", lp.x).putInt("posY", lp.y).apply()
                    }
                    return true
                }
            })

            resizer.setOnTouchListener(object : View.OnTouchListener {
                var dx0 = 0f; var dy0 = 0f; var w0 = 0; var h0 = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; w0 = lp.width; h0 = lp.height; wake() }
                        MotionEvent.ACTION_MOVE -> {
                            val dm2 = ctx.resources.displayMetrics
                            lp.width = (w0 + (e.rawX - dx0).toInt()).coerceIn(dm2.widthPixels * 4 / 10, dm2.widthPixels)
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

    // ── 자막 줄 ──
    // 원문을 먼저 넣고(번역 중 "…"), 번역이 오면 update로 채움
    fun add(orig: String): Long {
        val id = nextId++
        ui.post {
            if (!added) return@post
            errorText = null
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
            val e = Entry(box, o, t, orig)
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
        e.tr.text = tr
        if (tag.isNotEmpty()) e.origText = "${e.origText}   $tag"
        fill(e)
        if (follow) scrollToEnd()
        wake()
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

    private fun atBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= child.height - dp(24f)
    }

    private fun scrollToEnd() {
        scroll.post { scroll.scrollTo(0, list.height); follow = true }
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
