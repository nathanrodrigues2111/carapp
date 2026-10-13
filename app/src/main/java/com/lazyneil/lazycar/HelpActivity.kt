package com.lazyneil.lazycar

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/** Plain-English Q&A. Each question expands to its answer on tap. */
class HelpActivity : AppCompatActivity() {

    private val qa = listOf(
        "What does GO do?" to
            "GO turns on Bluetooth, connects your chosen outputs, turns on the hotspot and mobile data if you enabled them, opens your music player, ticks both outputs in Audio sharing, and presses play, all from one tap.",
        "What does STOP do?" to
            "STOP restores exactly what was on before you tapped GO. Anything already on stays on; only what GO switched on gets turned back off.",
        "How do I set it up the first time?" to
            "1. Pair your outputs in Android's Bluetooth settings.\n2. Enable OnePlus Audio sharing once in Bluetooth settings.\n3. Turn on CarApp in Accessibility (button below).\n4. Add the CarApp widget to your home screen.",
        "Why does CarApp need Accessibility?" to
            "Android hides hotspot and mobile data controls from apps. CarApp uses Accessibility only to tap the system panel for you, nothing else, and no data is collected.",
        "Only one output gets ticked?" to
            "That usually means OnePlus Audio sharing isn't enabled. Open Bluetooth settings, turn Audio sharing on, then try GO again.",
        "Widget tap does nothing?" to
            "Check that CarApp is still enabled in Accessibility, because a system update can switch it off. Re-enable it and the widget works again.",
        "Bluetooth prompt keeps showing?" to
            "Android asks permission to turn Bluetooth on. Tap Allow once and CarApp can turn it on for you from then on.",
        "Can I use headphones or car stereo instead of speakers?" to
            "Yes. Any paired Bluetooth audio device works: headphones, earbuds or a car stereo can be picked as an output.",
        "Does CarApp send any data?" to
            "No. CarApp sends no data anywhere. Everything happens on your phone.",
        "How do I add the widget?" to
            "Long-press your home screen, tap Widgets, find CarApp, and drag the GO widget where you want it.",
        "Can I set the volume?" to
            "Turn on Start volume and pick a level. GO sets it before the music starts, so it never blasts off. Volume applies to both outputs together: OnePlus shares one level in Audio sharing, so both speakers get louder or quieter as one."
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        Accent.apply(this)
        if (Prefs(this).amoled) theme.applyStyle(R.style.ThemeOverlay_LazyCar_Amoled, true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.helpList)
        val white = getColor(R.color.white)
        val secondary = getColor(R.color.secondaryText)
        qa.forEachIndexed { i, (q, a) ->
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true; isFocusable = true
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val question = TextView(this).apply {
                text = q; setTextColor(white); textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val chevron = ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            header.addView(question); header.addView(chevron)

            val answer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(8), 0, dp(8), dp(12))
            }
            answer.addView(TextView(this).apply {
                text = a; setTextColor(secondary); textSize = 14f
                setLineSpacing(dp(4).toFloat(), 1f)
            })
            if (i == 2) answer.addView(MaterialButton(this).apply {
                text = "Open Accessibility"
                setOnClickListener { openAccessibilitySettings() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })

            header.setOnClickListener {
                val open = answer.visibility != View.VISIBLE
                answer.visibility = if (open) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (open) 90f else 0f).setDuration(150).start()
            }
            list.addView(header); list.addView(answer)
        }
    }

    private fun openAccessibilitySettings() {
        val cn = ComponentName(this, TapService::class.java).flattenToString()
        try {
            val args = Bundle().apply { putString(":settings:fragment_args_key", cn) }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", cn)
                .putExtra(":settings:show_fragment_args", args))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
