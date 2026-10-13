package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

/**
 * The clips live in app-private storage so no gallery app can see them, this
 * page is how you watch, share and delete them.
 */
class RecordingsActivity : AppCompatActivity() {

    /** Thumbnails and durations come off a MediaMetadataRetriever, which is far
     *  too slow to touch on the main thread while inflating a list. */
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var listHost: LinearLayout
    private lateinit var summary: TextView
    private lateinit var emptyView: View
    private lateinit var deleteAll: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyNightMode(this)
        super.onCreate(savedInstanceState)
        Theming.applyToActivity(this)
        setContentView(R.layout.activity_recordings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        listHost = findViewById(R.id.recordings_list)
        summary = findViewById(R.id.txt_summary)
        emptyView = findViewById(R.id.empty)
        deleteAll = findViewById(R.id.btn_delete_all)

        deleteAll.setOnClickListener { confirmDeleteAll() }
        findViewById<TextView>(R.id.txt_location).text =
            "Saved in ${Recordings.dir(this).absolutePath}"
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt rather than diffed: a new clip may have landed while we were
        // away, and the whole list is a handful of rows.
        rebuild()
    }

    /** Every way out of this page ends up in finish(); see RearSettingsActivity. */
    override fun finish() {
        super.finish()
        Nav.close(this)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun rebuild() {
        val clips = Recordings.list(this)
        listHost.removeAllViews()
        emptyView.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        deleteAll.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
        summary.text = if (clips.isEmpty()) {
            "Free space: ${Recordings.formatSize(Recordings.freeBytes(this))}"
        } else {
            "${clips.size} clip${if (clips.size == 1) "" else "s"} · " +
                "${Recordings.formatSize(Recordings.totalBytes(this))} used · " +
                "${Recordings.formatSize(Recordings.freeBytes(this))} free"
        }
        clips.forEach { listHost.addView(rowFor(it)) }
    }

    private fun rowFor(file: File): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_recording, listHost, false)
        val thumb = row.findViewById<ImageView>(R.id.thumb)
        val title = row.findViewById<TextView>(R.id.title)
        val meta = row.findViewById<TextView>(R.id.meta)

        title.text = Recordings.displayName(file)
        meta.text = Recordings.formatSize(file.length())
        loadDetails(file, thumb, meta)

        row.setOnClickListener { play(file) }
        row.findViewById<ImageButton>(R.id.btn_share).setOnClickListener { share(file) }
        row.findViewById<ImageButton>(R.id.btn_delete).setOnClickListener { confirmDelete(file) }
        return row
    }

    /** Frame + duration on the io thread, applied only if the row is still the
     *  one we started for (the list can be rebuilt underneath us). */
    private fun loadDetails(file: File, thumb: ImageView, meta: TextView) {
        io.execute {
            val retriever = MediaMetadataRetriever()
            var frame: Bitmap? = null
            var duration: Long? = null
            runCatching {
                retriever.setDataSource(file.absolutePath)
                duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                frame = retriever.getFrameAtTime(0)
            }
            runCatching { retriever.release() }
            main.post {
                if (thumb.windowToken == null) return@post
                frame?.let { thumb.setImageBitmap(it) }
                val size = Recordings.formatSize(file.length())
                meta.text = duration
                    ?.let { "${Recordings.formatDuration(it)} · $size" }
                    // Unreadable container: the muxer never finished this one.
                    ?: "$size · damaged"
            }
        }
    }

    private fun play(file: File) {
        try {
            startActivity(Recordings.viewIntent(this, file))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this tablet can play mp4 files", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun share(file: File) {
        startActivity(
            android.content.Intent.createChooser(Recordings.shareIntent(this, file), "Share clip")
        )
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this clip?")
            .setMessage(Recordings.displayName(file))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                rebuild()
            }
            .show()
    }

    private fun confirmDeleteAll() {
        val clips = Recordings.list(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete all ${clips.size} clips?")
            .setMessage("This frees ${Recordings.formatSize(Recordings.totalBytes(this))}. It cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                clips.forEach { it.delete() }
                rebuild()
            }
            .show()
    }
}
