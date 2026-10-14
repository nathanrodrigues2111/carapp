package com.lazyneil.lazycar.media

import android.os.Bundle
import android.os.SystemClock
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
import com.google.android.material.button.MaterialButton
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
                // Album art fills the tile; the top level (categories) and missing art get the same accent icon tile.
                val b = if (stack.isEmpty()) null else thumbs.get(it.optString("id")) ?: it.optString("icon").takeIf { s -> s.isNotEmpty() }
                    ?.let(PhoneMedia::decode)?.also { b -> thumbs.put(it.optString("id"), b) }
                if (b != null) { setImageBitmap(b); clearColorFilter(); setPadding(0, 0, 0, 0) }
                else { setImageResource(if (it.optBoolean("browse")) R.drawable.ic_library else R.drawable.ic_play); setColorFilter(accent); setPadding(dp(18), dp(18), dp(18), dp(18)) }
            }
            val text = row.getChildAt(1) as LinearLayout
            val playing = isPlaying(it)
            (text.getChildAt(0) as TextView).apply { this.text = it.optString("title"); setTextColor(if (playing) accent else Ui.attr(context, R.attr.carText)) }
            (text.getChildAt(1) as TextView).apply { this.text = it.optString("sub"); visibility = if (this.text.isEmpty()) View.GONE else View.VISIBLE }
            row.isActivated = playing
            // Trailing bars on the playing row (list rows only).
            (row.getChildAt(2) as? ImageView)?.apply {
                visibility = if (playing) View.VISIBLE else View.GONE
                if (playing) (drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.let { d ->
                    d.setTint(accent); if (PhoneMedia.playing) d.start() else d.stop() }
            }
            return row
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun texts(center: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@LibraryActivity).apply { setTextAppearance(R.style.T_Body); paint.isFakeBoldText = true; if (center) gravity = Gravity.CENTER })
        addView(TextView(this@LibraryActivity).apply { setTextAppearance(R.style.T_Label); if (center) gravity = Gravity.CENTER })
    }

    private fun thumb() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.bg_tile); clipToOutline = true }

    private fun row() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(80); setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundResource(R.drawable.bg_lib_row)
        addView(thumb(), LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(texts(false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        addView(ImageView(this@LibraryActivity).apply {
            setImageDrawable(getDrawable(R.drawable.avd_now_playing)?.mutate()); contentDescription = "Now playing"; visibility = View.GONE
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        Ui.press(this)
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
        Ui.edge(this, root, dp(16))   // backdrop stays full window
        // Top bar like the player's: back, screen title, source tabs.
        val bar = layoutInflater.inflate(R.layout.view_top_bar, root, false)
        bar.findViewById<View>(R.id.backBtn).setOnClickListener { back() }
        title = bar.findViewById(R.id.topTitle)
        root.addView(bar)
        val tabs = TabLayout(this).apply {
            addTab(newTab().setText("Player library")); addTab(newTab().setText("Phone files"))
            tabMode = TabLayout.MODE_FIXED; tabGravity = TabLayout.GRAVITY_FILL; setBackgroundColor(0)
            setSelectedTabIndicatorColor(accent); setTabTextColors(Ui.attr(context, R.attr.carTextDim), Ui.attr(context, R.attr.carText))
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(56)))
        val box = layoutInflater.inflate(R.layout.view_search, root, false) as TextInputLayout
        search = (box.findViewById<TextInputEditText>(R.id.searchField)).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> load(); true }
        }
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        list = GridView(this).apply {
            verticalSpacing = dp(4); horizontalSpacing = dp(12); selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, i, _ -> open(items.getJSONObject(i)) }
        }
        // List, the shared loader and the empty / error line share one area.
        val area = FrameLayout(this)
        area.addView(list, FrameLayout.LayoutParams(-1, -1))
        spinner = Ui.loader(this); area.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        status = TextView(this).apply {
            setTextAppearance(R.style.T_Label); gravity = Gravity.CENTER; maxLines = 3; visibility = View.GONE
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, getDrawable(R.drawable.ic_library)?.mutate()?.apply { setTint(Ui.attr(context, R.attr.carTextDim)) }, null, null)
            compoundDrawablePadding = dp(8)
        }
        val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        empty.addView(status)
        retry = Ui.pillButton(this, "Retry", R.drawable.ic_refresh) { load() }.apply { visibility = View.GONE; }
        empty.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        area.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { marginStart = dp(32); marginEnd = dp(32) })
        root.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        // Slim now playing bar pinned at the bottom; tap returns to the player.
        val nb = layoutInflater.inflate(R.layout.view_np_bar, root, false)
        nb.setOnClickListener { finish() }
        nb.findViewById<View>(R.id.barPlay).setOnClickListener { PhoneMedia.cmd("toggle") }
        Ui.press(nb.findViewById(R.id.barPlay))
        root.addView(nb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        npBar = nb
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
    private lateinit var spinner: View
    private lateinit var npBar: View
    private var barPlaying: Boolean? = null

    // Live while visible: the playing row and the bar follow the phone. onStart / onStop, not pause,
    // because a focus change in the split pauses us and must not drop the listener.
    private val npListener: () -> Unit = { onNowPlaying() }
    override fun onStart() { super.onStart(); PhoneMedia.listeners += npListener; renderBar() }
    override fun onStop() { super.onStop(); PhoneMedia.listeners -= npListener }

    private fun norm(s: String) = s.trim().lowercase()
    /** Same track: media id first, else title plus artist, trimmed and case-insensitive. */
    private fun same(item: JSONObject, np: JSONObject): Boolean {
        val id = item.optString("id"); val npId = np.optString("mediaId")
        if (id.isNotEmpty() && npId.isNotEmpty()) return id == npId
        val t = norm(item.optString("title")); if (t.isEmpty() || t != norm(np.optString("title"))) return false
        val sub = norm(item.optString("sub")); val artist = norm(np.optString("artist"))
        return sub.isEmpty() || artist.isEmpty() || sub.contains(artist) || artist.contains(sub)
    }

    // Optimistic: the tapped row shows as playing at once; Now Playing from the phone reconciles it.
    private var tapped: JSONObject? = null
    private var tappedAt = 0L
    private fun isPlaying(item: JSONObject) = tapped?.let { it === item || it.optString("id") == item.optString("id") && item.optString("id").isNotEmpty() }
        ?: same(item, PhoneMedia.np)

    private fun onNowPlaying() {
        val t = tapped
        if (t != null && same(t, PhoneMedia.np)) {
            android.util.Log.i("LazyCar", "library: phone confirmed the track ${SystemClock.elapsedRealtime() - tappedAt} ms after the tap")
            tapped = null
        } else if (t != null && SystemClock.elapsedRealtime() - tappedAt > 5000) tapped = null   // phone played something else
        refreshRows(); renderBar()
    }

    /** Rebind only the visible rows (the old and new playing row are among them). */
    private fun refreshRows() {
        for (i in 0 until list.childCount) {
            val pos = list.firstVisiblePosition + i
            if (pos < adapter.count) adapter.getView(pos, list.getChildAt(i), list)
        }
    }

    private fun renderBar() {
        val np = PhoneMedia.np
        npBar.visibility = if (PhoneMedia.hasTrack) View.VISIBLE else View.GONE
        npBar.findViewById<ImageView>(R.id.barArt).setImageBitmap(PhoneMedia.art)
        npBar.findViewById<TextView>(R.id.barTitle).text = np.optString("title")
        npBar.findViewById<TextView>(R.id.barArtist).text = listOf(np.optString("artist"), np.optString("album")).filter { it.isNotEmpty() }.joinToString(" \u00b7 ")
        val play = npBar.findViewById<ImageButton>(R.id.barPlay)
        play.backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.attr(this, R.attr.carText))
        if (barPlaying != PhoneMedia.playing) { Ui.morph(play, PhoneMedia.playing, barPlaying != null); barPlaying = PhoneMedia.playing }
    }
    private lateinit var retry: MaterialButton
    /** Decoded thumbnails by item id, so scrolling never decodes twice. */
    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(200)
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
            if (!it.optBoolean("browse")) {
                tapped = it; tappedAt = SystemClock.elapsedRealtime(); refreshRows()
                list.post { android.util.Log.i("LazyCar", "library: indicator shown ${SystemClock.elapsedRealtime() - tappedAt} ms after the tap") }
            }
            if (!it.optBoolean("browse")) return
        }
        if (it.optBoolean("browse")) { stack += it.optString("id") to it.optString("title"); search.setText(""); load() }
    }

    private var loadSeq = 0

    /** Cached list at once (no spinner), then the phone's answer replaces it; errors keep the cache. */
    private fun load() {
        val q = search.text?.toString()?.trim() ?: ""
        val id = stack.lastOrNull()?.first ?: ""
        val c = if (files) "files" else "browse"
        val app = PhoneMedia.np.optString("app").ifEmpty { "Player" }
        title.text = stack.lastOrNull()?.second ?: if (files) "Phone files" else app
        val seq = ++loadSeq; val t0 = SystemClock.elapsedRealtime()
        val hit = PhoneMedia.cached(c, id, q)
        retry.visibility = View.GONE; status.visibility = View.GONE
        show(hit?.optJSONArray("items") ?: JSONArray())
        if (hit != null) android.util.Log.i("LazyCar", "library: cached ${items.length()} items in ${SystemClock.elapsedRealtime() - t0} ms")
        Ui.showLoader(spinner, hit == null)
        PhoneMedia.request(c, id, q) { r ->
            if (isFinishing || seq != loadSeq) return@request
            android.util.Log.i("LazyCar", "library: phone answered in ${SystemClock.elapsedRealtime() - t0} ms err=${r.optString("err")}")
            Ui.showLoader(spinner, false)
            val err = r.optString("err")
            if (err.isEmpty()) { retry.visibility = View.GONE; status.visibility = View.GONE }
            if (err.isEmpty()) show(r.optJSONArray("items") ?: JSONArray()) else if (hit != null) return@request
            status.text = err.ifEmpty { if (items.length() == 0) (if (files) "No music files here. The phone may need the music library permission" else "Nothing here") else "" }
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            retry.visibility = if (err.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }
}
