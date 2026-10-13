package com.lazyneil.lazycar.media

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
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
import com.lazyneil.lazycar.Prefs
import com.lazyneil.lazycar.R
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
    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.length()
        override fun getItem(i: Int): JSONObject = items.getJSONObject(i)
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = convert as? LinearLayout ?: row()
            val it = getItem(i)
            (row.getChildAt(0) as ImageView).apply {
                val b = it.optString("icon").takeIf { s -> s.isNotEmpty() }?.let(PhoneMedia::decode)
                if (b != null) { setImageBitmap(b); clearColorFilter(); setPadding(0, 0, 0, 0) }
                else { setImageResource(if (it.optBoolean("browse")) R.drawable.ic_library else R.drawable.ic_play); setColorFilter(getColor(R.color.secondaryText)); setPadding(dp(14), dp(14), dp(14), dp(14)) }
            }
            val text = row.getChildAt(1) as LinearLayout
            (text.getChildAt(0) as TextView).text = it.optString("title")
            (text.getChildAt(1) as TextView).apply { this.text = it.optString("sub"); visibility = if (this.text.isEmpty()) View.GONE else View.VISIBLE }
            return row
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun row() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(88); setPadding(dp(16), dp(8), dp(16), dp(8))
        addView(ImageView(this@LibraryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.bg_thumb); clipToOutline = true }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(LinearLayout(this@LibraryActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@LibraryActivity).apply { textSize = 22f; setTextColor(getColor(R.color.white)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(TextView(this@LibraryActivity).apply { textSize = 16f; setTextColor(getColor(R.color.secondaryText)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(20) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val p = Prefs(this)
        Accent.apply(this)
        if (p.amoled) theme.applyStyle(R.style.ThemeOverlay_LazyCar_Amoled, true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left + dp(16), b.top, b.right + dp(16), b.bottom); insets
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72) }
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_arrow_back); iconSize = dp(32); contentDescription = "Back"; setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        title = TextView(this).apply { textSize = 26f; setTextColor(getColor(R.color.white)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        val tabs = TabLayout(this).apply {
            addTab(newTab().setText("Player library")); addTab(newTab().setText("Phone files"))
            tabMode = TabLayout.MODE_FIXED; tabGravity = TabLayout.GRAVITY_START; setBackgroundColor(0)
        }
        bar.addView(tabs, LinearLayout.LayoutParams(dp(420), dp(64)))
        root.addView(bar)
        val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Search" }
        search = TextInputEditText(box.context).apply {
            textSize = 20f; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> load(); true }
        }
        box.addView(search)
        root.addView(box)
        status = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.secondaryText)); setPadding(dp(4), dp(8), 0, dp(8)) }
        root.addView(status)
        val list = ListView(this).apply {
            adapter = this@LibraryActivity.adapter; divider = null
            setOnItemClickListener { _, _, i, _ -> open(items.getJSONObject(i)) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { files = t.position == 1; stack.clear(); search.setText(""); load() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        load()
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
        items = JSONArray(); adapter.notifyDataSetChanged()
        PhoneMedia.request(if (files) "files" else "browse", id, q) { r ->
            if (isFinishing) return@request
            items = r.optJSONArray("items") ?: JSONArray(); adapter.notifyDataSetChanged()
            status.text = r.optString("err").ifEmpty { if (items.length() == 0) (if (files) "No music files found, or the phone needs the music library permission" else "Empty") else "${items.length()} items" }
        }
    }
}
