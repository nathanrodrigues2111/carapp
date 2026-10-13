package com.lazyneil.lazycar.media

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lazyneil.lazycar.Accent
import com.lazyneil.lazycar.R
import com.lazyneil.lazycar.Ui
import org.json.JSONArray
import org.json.JSONObject

/** Tablet: browse the phone's music (the player's own library, or the phone's audio files) and pick what plays. */
class LibraryActivity : AppCompatActivity() {
    private val stack = mutableListOf<Pair<String, String>>()   // id to title, per tab
    private var files = false
    private var items = JSONArray()
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var search: TextInputEditText
    private var grid = false   // browsable items that all have art show as a grid of art cards
    private var accent = 0
    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.length()
        override fun getItem(i: Int): JSONObject = items.getJSONObject(i)
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = convert as? LinearLayout ?: if (grid) card() else row()
            val it = getItem(i)
            (row.getChildAt(0) as ImageView).apply {
                val b = it.optString("icon").takeIf { s -> s.isNotEmpty() }?.let(PhoneMedia::decode)
                if (b != null) { setImageBitmap(b); clearColorFilter(); setPadding(0, 0, 0, 0) }
                else { setImageResource(if (it.optBoolean("browse")) R.drawable.ic_library else R.drawable.ic_play); setColorFilter(Ui.attr(context, R.attr.carTextDim)); setPadding(dp(18), dp(18), dp(18), dp(18)) }
            }
            val text = row.getChildAt(1) as LinearLayout
            val playing = it.optString("title").isNotEmpty() && it.optString("title") == PhoneMedia.np.optString("title")
            (text.getChildAt(0) as TextView).apply { this.text = it.optString("title"); setTextColor(if (playing) accent else Ui.attr(context, R.attr.carText)) }
            (text.getChildAt(1) as TextView).apply { this.text = it.optString("sub"); visibility = if (this.text.isEmpty()) View.GONE else View.VISIBLE }
            row.isActivated = playing
            return row
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun texts(center: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@LibraryActivity).apply { setTextAppearance(R.style.T_Body); paint.isFakeBoldText = true; if (center) gravity = Gravity.CENTER })
        addView(TextView(this@LibraryActivity).apply { setTextAppearance(R.style.T_Label); if (center) gravity = Gravity.CENTER })
    }

    private fun thumb() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.bg_thumb); clipToOutline = true }

    private fun row() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(80); setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundResource(R.drawable.bg_lib_row)
        addView(thumb(), LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(texts(false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(12)); setBackgroundResource(R.drawable.bg_lib_row)
        addView(thumb(), LinearLayout.LayoutParams(-1, dp(CARD - 16)))
        addView(texts(true), LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        accent = Accent.forArt(this, PhoneMedia.art)
        val frame = FrameLayout(this).apply { setBackgroundColor(Ui.attr(context, R.attr.carBg)) }
        frame.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.6f; setImageBitmap(PhoneMedia.art); Ui.blur(this)
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply { setBackgroundResource(R.drawable.bg_scrim) }, FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left + dp(16), b.top, b.right + dp(16), b.bottom); insets
        }
        // Top bar like the player's: back, screen title, source tabs.
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72) }
        bar.addView(ImageButton(this, null, 0, R.style.CarIconBtn).apply {
            setImageResource(R.drawable.ic_arrow_back); contentDescription = "Back"; setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        title = TextView(this).apply { setTextAppearance(R.style.T_Title) }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        root.addView(bar)
        val tabs = TabLayout(this).apply {
            addTab(newTab().setText("Player library")); addTab(newTab().setText("Phone files"))
            tabMode = TabLayout.MODE_FIXED; tabGravity = TabLayout.GRAVITY_FILL; setBackgroundColor(0)
            setSelectedTabIndicatorColor(accent); setTabTextColors(Ui.attr(context, R.attr.carTextDim), Ui.attr(context, R.attr.carText))
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(56)))
        val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Search" }
        search = TextInputEditText(box.context).apply {
            setTextAppearance(R.style.T_Body); setTextColor(Ui.attr(context, R.attr.carText)); isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> load(); true }
        }
        box.addView(search)
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        status = TextView(this).apply { setTextAppearance(R.style.T_Label); setPadding(dp(4), dp(8), 0, dp(8)) }
        root.addView(status)
        list = GridView(this).apply {
            verticalSpacing = dp(4); horizontalSpacing = dp(12); selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, i, _ -> open(items.getJSONObject(i)) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(frame)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { files = t.position == 1; stack.clear(); search.setText(""); load() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        load()
    }

    private lateinit var list: GridView
    private val CARD = 176

    /** Grid of art cards when every item is a browsable folder with art, else one column of rows. */
    private fun show(a: JSONArray) {
        items = a
        grid = a.length() > 0 && (0 until a.length()).all { a.getJSONObject(it).let { o -> o.optBoolean("browse") && o.optString("icon").isNotEmpty() } }
        list.numColumns = if (grid) GridView.AUTO_FIT else 1
        list.columnWidth = dp(CARD)
        list.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        list.adapter = adapter
    }

    private fun back() {
        if (search.text?.isNotEmpty() == true) { search.setText(""); load() }
        else if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); load() } else finish()
    }

    private fun open(it: JSONObject) {
        if (it.optBoolean("play")) {
            if (files) PhoneMedia.cmd("playFile") { put("song", it.optLong("song")) }
            else PhoneMedia.cmd("playId") { put("id", it.optString("id")) }
            status.text = "Playing ${it.optString("title")} on the phone"
            if (!it.optBoolean("browse")) return
        }
        if (it.optBoolean("browse")) { stack += it.optString("id") to it.optString("title"); search.setText(""); load() }
    }

    private fun load() {
        val q = search.text?.toString()?.trim() ?: ""
        val id = stack.lastOrNull()?.first ?: ""
        val app = PhoneMedia.np.optString("app").ifEmpty { "Player" }
        title.text = stack.lastOrNull()?.second ?: if (files) "Phone files" else app
        status.text = "Loading"
        show(JSONArray())
        PhoneMedia.request(if (files) "files" else "browse", id, q) { r ->
            if (isFinishing) return@request
            show(r.optJSONArray("items") ?: JSONArray())
            status.text = r.optString("err").ifEmpty { if (items.length() == 0) (if (files) "No music files found, or the phone needs the music library permission" else "Empty") else "${items.length()} items" }
        }
    }
}
