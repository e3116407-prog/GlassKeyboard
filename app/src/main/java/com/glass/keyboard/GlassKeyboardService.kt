package com.glass.keyboard

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

class GlassKeyboardService : InputMethodService() {

    private var arabic = false
    private var symbols = false
    private var shift = 0 // 0 off, 1 once, 2 caps lock
    private var lastShiftTap = 0L
    private var info: EditorInfo? = null
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    // ---------- Layouts ----------
    private val enRows = listOf(
        "qwertyuiop".map { it.toString() },
        "asdfghjkl".map { it.toString() },
        "zxcvbnm".map { it.toString() }
    )
    private val arRows = listOf(
        listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
        listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "د"),
        listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ")
    )
    // Arabic shift layer: diacritics on row 1, extra letters on row 3
    private val arShiftRows = listOf(
        listOf("\u064E", "\u064B", "\u064F", "\u064C", "\u0650", "\u064D", "\u0651", "\u0652", "\u0640", "؟", "،"),
        arRows[1],
        listOf("ذ", "ط", "إ", "أ", "آ", "ئ", "ء", "ؤ", "ى", "ز")
    )
    private val enSym = listOf(
        "1234567890".map { it.toString() },
        listOf("-", "/", ":", ";", "(", ")", "\$", "&", "@", "\""),
        listOf(".", ",", "?", "!", "'")
    )
    private val arSym = listOf(
        "1234567890".map { it.toString() },
        listOf("-", "/", ":", "؛", "(", ")", "\$", "&", "@", "\""),
        listOf("،", ".", "؟", "!", "'")
    )

    // ---------- Lifecycle ----------
    override fun onCreate() {
        super.onCreate()
        arabic = getSharedPreferences("glass", MODE_PRIVATE).getBoolean("arabic", false)
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        applyGlassWindow()
        build()
        return root
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        info = attribute
        symbols = false
        shift = 0
        autoCap()
        applyGlassWindow()
        build()
    }

    // ---------- Glass / blur ----------
    private fun applyGlassWindow() {
        val w = window?.window ?: return
        val blurOk = Build.VERSION.SDK_INT >= 31 &&
            (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
        val r = dp(28).toFloat()
        val bg = GradientDrawable().apply {
            setColor(if (blurOk) Color.argb(140, 28, 28, 30) else Color.argb(240, 28, 28, 30))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        w.setBackgroundDrawable(bg)
        if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(if (blurOk) dp(45) else 0)
    }

    // ---------- UI building ----------
    private fun build() {
        if (!::root.isInitialized) return
        root.removeAllViews()
        root.addView(topBar())
        val rows = when {
            symbols -> if (arabic) arSym else enSym
            arabic -> if (shift != 0) arShiftRows else arRows
            else -> enRows
        }
        root.addView(letterRow(rows[0], 1f, null, null, 0f))
        val sidePad = if (!arabic && !symbols) 0.5f else 0f
        root.addView(letterRow(rows[1], 1f, null, null, sidePad))

        // Row 3: [shift] letters [delete]
        val r3 = newRow()
        if (symbols) r3.addView(spacer(1.3f)) else r3.addView(shiftKey())
        val w3 = if (symbols) 1.6f else 1f
        rows[2].forEach { r3.addView(charKey(it, w3)) }
        r3.addView(key("⌫", 1.3f, 1, repeat = true) { deleteOne() })
        root.addView(r3)

        // Bottom row
        val r4 = newRow()
        r4.addView(key(if (symbols) (if (arabic) "أبج" else "ABC") else "123", 1.5f, 1) {
            symbols = !symbols; shift = 0; build()
        })
        r4.addView(charKey(if (arabic) "،" else ",", 1f))
        val space = key(if (arabic) "العربية" else "English", 4.2f, 0) { commit(" ") }
        space.setTextColor(Color.argb(170, 255, 255, 255))
        (space as TextView).textSize = 15f
        r4.addView(space)
        r4.addView(key(if (arabic) "EN ⇄ ع" else "ع ⇄ EN", 1.5f, 1, onLong = {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }) { toggleLanguage() }.also { (it as TextView).textSize = 14f })
        val label = enterLabel()
        r4.addView(key(label, 2f, if (label == "↵") 1 else 2) { enter() }
            .also { if (label != "↵") (it as TextView).textSize = 16f })
        root.addView(r4)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }
        bar.addView(spacer(1f))
        val chev = key("⌄", 0f, 1) { requestHideSelf(0) } as TextView
        chev.layoutParams = LinearLayout.LayoutParams(dp(48), dp(34)).apply { setMargins(dp(6), dp(2), dp(10), dp(2)) }
        bar.addView(chev)
        return bar
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
    }

    private fun spacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
    }

    private fun letterRow(keys: List<String>, w: Float, a: Any?, b: Any?, side: Float): View {
        val row = newRow()
        if (side > 0f) row.addView(spacer(side))
        keys.forEach { row.addView(charKey(it, w)) }
        if (side > 0f) row.addView(spacer(side))
        return row
    }

    private fun shiftKey(): View {
        val lbl = when (shift) { 2 -> "⇪"; else -> "⇧" }
        val style = if (shift != 0) 3 else 1
        return key(lbl, 1.3f, style) {
            val now = SystemClock.uptimeMillis()
            shift = if (now - lastShiftTap < 350) 2 else if (shift == 0) 1 else 0
            lastShiftTap = now
            build()
        }
    }

    private fun charKey(k: String, weight: Float): View {
        val out = if (!arabic && shift != 0 && !symbols) k.uppercase() else k
        val c = k[0]
        val shown = if (k.length == 1 && (c in '\u064B'..'\u0652')) "\u25CC" + k else out
        return key(shown, weight, 0) {
            commit(out)
            afterCommit()
        }
    }

    private fun key(
        label: String, weight: Float, style: Int,
        repeat: Boolean = false, onLong: (() -> Unit)? = null, action: () -> Unit
    ): View {
        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (label.length > 1) 15f else 22f
            background = keyBg(style)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                .apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
        }
        attach(tv, repeat, onLong, action)
        return tv
    }

    private fun keyBg(style: Int): Drawable {
        fun shape(c: Int) = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(c)
            setStroke(dp(1), Color.argb(34, 255, 255, 255))
        }
        val (n, p) = when (style) {
            1 -> Color.argb(95, 0, 0, 0) to Color.argb(130, 255, 255, 255)
            2 -> Color.rgb(58, 123, 213) to Color.rgb(90, 150, 230)
            3 -> Color.argb(170, 255, 255, 255) to Color.argb(210, 255, 255, 255)
            else -> Color.argb(66, 255, 255, 255) to Color.argb(130, 255, 255, 255)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(p))
            addState(intArrayOf(), shape(n))
        }
    }

    private fun attach(v: View, repeat: Boolean, onLong: (() -> Unit)?, action: () -> Unit) {
        var longFired = false
        val repeater = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 45) }
        }
        val longRun = Runnable { longFired = true; onLong?.invoke() }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    longFired = false
                    if (repeat) { action(); handler.postDelayed(repeater, 400) }
                    else if (onLong != null) handler.postDelayed(longRun, 450)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeater); handler.removeCallbacks(longRun)
                    if (!repeat && !longFired) action()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeater); handler.removeCallbacks(longRun)
                }
            }
            true
        }
    }

    // ---------- Actions ----------
    private fun commit(t: String) { currentInputConnection?.commitText(t, 1) }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (shift == 0) { autoCap(); }
    }

    private fun afterCommit() {
        val old = shift
        if (shift == 1) shift = 0
        if (shift == 0) autoCap()
        if (old != shift) build()
    }

    private fun autoCap() {
        if (arabic || symbols || shift != 0) return
        val ic = currentInputConnection ?: return
        val t = info?.inputType ?: 0
        if (t and EditorInfo.TYPE_MASK_CLASS == EditorInfo.TYPE_NULL) return
        if (ic.getCursorCapsMode(t) != 0) shift = 1
    }

    private fun toggleLanguage() {
        arabic = !arabic
        shift = 0
        getSharedPreferences("glass", MODE_PRIVATE).edit().putBoolean("arabic", arabic).apply()
        autoCap()
        build()
    }

    private fun enter() {
        val ei = info
        val opts = ei?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnter = opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun enterLabel(): String {
        val opts = info?.imeOptions ?: 0
        if (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        return when (opts and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> if (arabic) "بحث" else "Search"
            EditorInfo.IME_ACTION_GO -> if (arabic) "انتقال" else "Go"
            EditorInfo.IME_ACTION_SEND -> if (arabic) "إرسال" else "Send"
            EditorInfo.IME_ACTION_DONE -> if (arabic) "تم" else "Done"
            EditorInfo.IME_ACTION_NEXT -> if (arabic) "التالي" else "Next"
            else -> "↵"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
