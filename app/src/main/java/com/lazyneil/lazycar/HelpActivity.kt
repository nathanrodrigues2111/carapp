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
        "What does Start do?" to
            "On the phone Start turns on the hotspot, mobile data and location when they are off, and shares the phone GPS with the tablet. The tablet wakes, turns on Wi-Fi and Bluetooth, connects the car stereo and opens your saved app pair. With the CarApp helper off, CarApp opens each system screen for you instead.",
        "What does Shut down do?" to
            "Shut down puts back what was on before you tapped Start and stops everything CarApp runs on both devices. Only what Start switched on gets turned off.",
        "How do I set it up the first time?" to
            "1. Open CarApp on the phone and the tablet and follow the setup line.\n2. On the tablet, pair the car stereo in Android's Bluetooth settings and pick it in CarApp Settings.\n3. Add the Start widget on the phone.",
        "How does the tablet open?" to
            "With the CarApp helper on, the phone's Start wakes the tablet and opens the app pair chosen in Settings, Drive, Open on GO. Save a pair on the tablet home screen first (long press an app, Split screen, save), then show the home screen once so CarApp sees it.",
        "Does CarApp send any data?" to
            "No. GPS and settings only travel between your phone and tablet over the hotspot."
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        Ui.edge(this, findViewById(R.id.root))
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topTitle).text = "Help"

        val list = findViewById<LinearLayout>(R.id.helpList)
        qa.forEachIndexed { i, (q, a) ->
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isClickable = true; isFocusable = true
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val question = TextView(this).apply {
                text = q; setTextAppearance(R.style.SetTitle); maxLines = 3
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
                setPadding(dp(20), 0, dp(20), dp(16))
            }
            answer.addView(TextView(this).apply {
                text = a; setTextAppearance(R.style.T_Body); maxLines = 20
                setLineSpacing(dp(4).toFloat(), 1f)
            })

            header.setOnClickListener {
                val open = answer.visibility != View.VISIBLE
                answer.visibility = if (open) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (open) 90f else 0f).setDuration(150).start()
            }
            list.addView(header); list.addView(answer)
        }
    }

}
