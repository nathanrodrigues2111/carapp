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
            "Start plays CarApp's player on the speaker and the tablet, and shares the phone GPS with the tablet. In Phone mode it connects the JBL first; if Bluetooth is off, Android asks you to allow it. Keep the phone hotspot on, the tablet joins it by itself.",
        "What does Shut down do?" to
            "Shut down puts back what was on before you tapped Start and stops everything CarApp runs on both devices. Only what Start switched on gets turned off. If Start turned Bluetooth on, Android asks before turning it off.",
        "How do I set it up the first time?" to
            "1. Pair the JBL speaker in Android's Bluetooth settings.\n2. Open CarApp on the phone and the tablet and follow the setup line.\n3. On the tablet, save a Maps + CarApp pair once on the launcher: long press Maps, Split screen, pick CarApp, save.\n4. Add the Start widget on the phone.",
        "How does the tablet open?" to
            "Tap the Maps + CarApp pair icon on the tablet home screen: Maps on one side, the player on the other. CarApp reminds you when it opens on its own. Turn the reminder off in Settings, Drive, Open with Maps.",
        "How do I use the player?" to
            "Tap the art to play or pause, swipe it for the next or previous song. Drag the bar to seek. The library icon opens the music to pick what plays.",
        "Why does the color change?" to
            "The accent follows the album art, the same color on the phone and the tablet.",
        "Why does the tablet speaker ask to share the screen?" to
            "In Phone mode with another player (Poweramp, YouTube Music), Android only lets CarApp copy the music to the tablet after you tap Start in its screen share dialog. Spotify blocks the copy, so it plays on the JBL only. CarApp's own player needs no dialog.",
        "Can I set the volume?" to
            "Settings, Audio has a slider for the JBL speaker and one for the tablet speaker. The volume buttons keep working too.",
        "Does CarApp send any data?" to
            "No. GPS and music only travel between your phone and tablet over the hotspot."
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
