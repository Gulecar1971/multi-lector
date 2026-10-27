package com.gustavo.multilector

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Pal {
    val primary = Color.parseColor("#1E3A5F")
    val accent = Color.parseColor("#2E7D32")
    val orange = Color.parseColor("#D35400")
    val purple = Color.parseColor("#6A1B9A")
    val teal = Color.parseColor("#00796B")
    val pink = Color.parseColor("#AD1457")
    val slate = Color.parseColor("#455A64")
    val red = Color.parseColor("#C62828")
    val bg = Color.parseColor("#F3F5F8")
    val card = Color.WHITE
    val text = Color.parseColor("#1B1B1B")
    val muted = Color.parseColor("#6B717B")
    val line = Color.parseColor("#DDE1E6")
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun pressable(color: Int, radius: Float): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), rounded(color, radius), null)

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(text: String, size: Float = 16f, color: Int = Pal.text, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

/** Botón grande de la pantalla de inicio: título + explicación. */
fun Context.bigButton(title: String, sub: String?, color: Int, onClick: () -> Unit): View {
    val box = vbox()
    box.background = pressable(color, dpf(16f))
    box.setPadding(dp(18), dp(14), dp(18), dp(14))
    box.addView(label(title, 19f, Color.WHITE, true))
    if (sub != null) box.addView(label(sub, 13.5f, Color.argb(230, 255, 255, 255)).apply { setPadding(0, dp(2), 0, 0) })
    box.isClickable = true
    box.isFocusable = true
    box.setOnClickListener { onClick() }
    box.layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(10) }
    return box
}

fun Context.smallButton(text: String, color: Int = Pal.primary, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14.5f; setTextColor(Color.WHITE)
        background = pressable(color, dpf(12f))
        setPadding(dp(14), dp(4), dp(14), dp(4))
        minHeight = dp(46); minimumHeight = dp(46); minWidth = 0; minimumWidth = 0
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Activity.choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this).setTitle(title)
        .setItems(options.toTypedArray()) { _, i -> onPick(i) }
        .setNegativeButton("Cancelar", null).show()
}

fun Activity.alert(title: String, msg: String) {
    if (isFinishing) return
    AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
}

/** Barra superior con botón atrás opcional. Devuelve la barra y el TextView del título. */
fun Activity.topBar(title: String, back: Boolean): Pair<LinearLayout, TextView> {
    val bar = hbox()
    bar.setBackgroundColor(Pal.primary)
    bar.setPadding(dp(8), dp(10), dp(12), dp(10))
    if (back) {
        val b = label("←", 24f, Color.WHITE, true)
        b.setPadding(dp(10), 0, dp(14), 0)
        b.setOnClickListener { finish() }
        bar.addView(b)
    }
    val t = label(title, 18f, Color.WHITE, true)
    t.maxLines = 2
    t.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    bar.addView(t, lp(0, WRAP, 1f))
    window.statusBarColor = Pal.primary
    return bar to t
}
