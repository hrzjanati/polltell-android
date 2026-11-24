package app.tether.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.tether.R

/** Small view-building helpers shared by the screens: sizes in dp/sp, the brand gradient, cards, labels and buttons. */
class Ui(private val ctx: Context) {
    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun color(id: Int) = ctx.getColor(id)
    fun color(tone: Tone) = color(when (tone) {
        Tone.OK -> R.color.ok; Tone.WARN -> R.color.warn; Tone.BAD -> R.color.bad; Tone.MUTED -> R.color.muted; Tone.TEXT -> R.color.text
    })

    /** Right-to-left scripts join their letters: no tracking, and no upper-casing, may touch them. */
    val isRtl get() = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** The brand gradient, light purple to deep purple, left to right. */
    fun brandGradient(radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(color(R.color.brand_start), color(R.color.brand_mid), color(R.color.brand_end)),
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    fun card(title: String): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(color(R.color.surface)); setStroke(dp(1), color(R.color.surface_stroke)); cornerRadius = dp(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }
        c.addView(label(if (isRtl) title else title.uppercase(), R.color.accent, 12f).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (!isRtl) letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(6))
        })
        return c
    }

    fun label(t: String, colorId: Int, size: Float = 15f) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color(colorId)); setPadding(0, dp(4), 0, dp(4))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setLineSpacing(0f, 1.15f)
    }

    /** A filled brand-gradient button, or (secondary) a purple outline; 48 dp tall, the touch-target minimum. */
    fun button(t: String, secondary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null
        setTextColor(if (secondary) color(R.color.accent) else color(R.color.on_brand))
        val shape = if (secondary) GradientDrawable().apply {
            cornerRadius = dp(24).toFloat(); setStroke(dp(1), color(R.color.accent)); setColor(0)
        } else brandGradient(24)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        minHeight = dp(48); minimumHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(8); marginEnd = dp(8) }
        setPadding(dp(20), 0, dp(20), 0)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}

/** Keeps content clear of the status bar, the cut-out and the navigation bar: Android 15 draws apps edge to edge. */
fun View.padForSystemBars(left: Int, top: Int, right: Int, bottom: Int) {
    setPadding(left, top, right, bottom)
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(left + b.left, top + b.top, right + b.right, bottom + b.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom)
        }
        insets
    }
    requestApplyInsets()
}
