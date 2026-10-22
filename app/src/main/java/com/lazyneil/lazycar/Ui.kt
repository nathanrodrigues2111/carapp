package com.lazyneil.lazycar

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.materialswitch.MaterialSwitch

/** Themes, layouts, motion and system screen jumps. */
object Ui {
    /** A grant screen CarApp opened: when the grant lands ([backFromGrant]) CarApp comes back, no Back press. */
    @Volatile private var grantUntil = 0L
    fun grantOpened() { grantUntil = android.os.SystemClock.uptimeMillis() + 300_000 }
    fun backFromGrant(c: Context) {
        if (android.os.SystemClock.uptimeMillis() > grantUntil) return; grantUntil = 0
        val home = if (Role.isTablet(c)) DashboardActivity::class.java else MainActivity::class.java
        try { c.startActivity(android.content.Intent(c, home).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
    }

    /** The optional helper ([SplitService]): offered once as a setup line, never again after that tap. */
    fun helperOn(c: Context) = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.contains("${c.packageName}/") == true
    fun offerHelper(c: Context) = !helperOn(c) && !sp(c).getBoolean("helperAsked", false)
    fun openHelper(a: Activity) {
        sp(a).edit().putBoolean("helperAsked", true).apply(); grantOpened()
        val cn = android.content.ComponentName(a, SplitService::class.java).flattenToString()
        openSettings(a, android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, cn),
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Every jump to a system screen goes through here: the first intent that starts wins, then (with [fallback])
     *  CarApp's app info, then Settings. Never throws. */
    fun openSettings(c: Context, vararg tries: android.content.Intent, fallback: Boolean = true): Boolean {
        val all = tries.toList() + if (!fallback) emptyList() else listOf(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + c.packageName)),
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        for (i in all) try {
            // Own task always: left in CarApp's task, a Settings page came back on the next CarApp icon tap.
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i); return true
        } catch (e: Exception) { android.util.Log.e("LazyCar", "open ${i.action}: $e") }
        return false
    }

    /** Before super.onCreate. One theme: Midnight AMOLED. The phone stays portrait. */
    fun apply(a: AppCompatActivity) {
        a.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        a.theme.applyStyle(R.style.CarTheme_Midnight, true)
        if (!Role.isTablet(a)) a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun sp(c: Context) = c.getSharedPreferences("lazycar", Context.MODE_PRIVATE)

    fun attr(c: Context, id: Int): Int {
        val ta = c.obtainStyledAttributes(intArrayOf(id)); val v = ta.getColor(0, Color.WHITE); ta.recycle(); return v
    }
    /** The accent on every accent widget under [root]: switches, tabs, progress, text fields. Views tagged with a Runnable repaint themselves. */
    fun paintAccent(root: View, c: Int) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val sw = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(c, attr(root.context, R.attr.carSurfaceHigh)))
        val thumb = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Accent.onColor(c), attr(root.context, R.attr.carTextDim)))
        fun walk(v: View) {
            when (v) {
                is MaterialSwitch -> { v.trackTintList = sw; v.thumbTintList = thumb; v.trackDecorationTintList = ColorStateList.valueOf(Color.TRANSPARENT) }
                is com.google.android.material.tabs.TabLayout -> v.setSelectedTabIndicatorColor(c)
                is com.google.android.material.progressindicator.BaseProgressIndicator<*> -> v.setIndicatorColor(c)
                is com.google.android.material.textfield.TextInputLayout -> v.boxStrokeColor = c
            }
            (v.tag as? Runnable)?.run()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** The shared pill button (@style/CarButton): 44dp, 20dp side padding, centered label, optional icon. */
    fun pillButton(c: Context, label: String, icon: Int = 0, onClick: () -> Unit) =
        (android.view.LayoutInflater.from(c).inflate(R.layout.view_pill_button, null) as com.google.android.material.button.MaterialButton).apply {
            text = label; if (icon != 0) setIconResource(icon); setOnClickListener { onClick() }; press(this)
        }

    /** Edge to edge: the window (backdrop) draws under the status bar, split caption and gesture bar;
     *  only [content] is padded by those insets, plus [extra] px on the sides and bottom. */
    fun edge(a: Activity, content: View, extra: Int = 0, bottomExtra: Int = 0) {
        a.window.isNavigationBarContrastEnforced = false; a.window.isStatusBarContrastEnforced = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                androidx.core.view.WindowInsetsCompat.Type.captionBar() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left + extra, b.top, b.right + extra, b.bottom + bottomExtra); insets
        }
    }

    /** Status subtitle: a colored dot, then one of Connected / Searching / Off (or a short detail). */
    fun status(tv: TextView, text: String, color: Int) {
        tv.text = android.text.SpannableString("\u25CF  $text").apply { setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0) }
    }

    /** Spring press: shrink on touch, overshoot back, tick haptic. Never consumes the touch. */
    fun press(vararg views: View?) = views.filterNotNull().forEach { v ->
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(150).start()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(200).start()
            }
            false
        }
    }

    /** Status dot, steady: an infinite pulse redraws the whole window at the display rate (main + RenderThread ~15% each). */
    @Suppress("UNUSED_PARAMETER")
    fun dot(v: View?, color: Int, live: Boolean) {
        v ?: return
        if (v.backgroundTintList?.defaultColor != color) v.backgroundTintList = ColorStateList.valueOf(color)
    }

    val GREEN = 0xFF34D399.toInt()   // tokens: success
    val AMBER = 0xFFFBBF24.toInt()   // tokens: warning
}
