package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where clips live and how they are named, in one place.
 *
 * App-private external storage (Android/data/…/files/Movies) rather than the
 * public DCIM: it needs no storage permission on any Android version, which
 * matters for a sideloaded app that already has to beg for Camera and
 * Microphone. The trade-off is that clips are not in the gallery, so
 * [RecordingsActivity] is the way to watch them, plus Share, which hands other
 * apps a FileProvider uri.
 */
object Recordings {

    private const val PREFIX = "RV_"
    private const val EXT = ".mp4"

    /** Below this, refuse to start: a truncated clip is worse than none. */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    /** Safety net for a recording nobody stopped (auto-record with the camera
     *  left powered). Long enough to never cut a real reversing manoeuvre. */
    const val MAX_CLIP_MS = 15 * 60 * 1000L

    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val displayStamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    /** External if we have it (visible over MTP, survives a cache wipe),
     *  internal as a fallback so recording never simply fails. */
    fun dir(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val dir = external ?: File(context.filesDir, "Movies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Path for a new clip WITHOUT the extension, AUSBC's Mp4Muxer appends
     * ".mp4" to whatever it is given, so passing a full filename would produce
     * "clip.mp4.mp4".
     */
    fun newBasePath(context: Context): String =
        File(dir(context), PREFIX + fileStamp.format(Date())).absolutePath

    fun fileFor(basePath: String) = File(basePath + EXT)

    /** Newest first, the clip you just took is the one you want to check. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun totalBytes(context: Context): Long = list(context).sumOf { it.length() }

    /**
     * Litter from a run that was cut short: half-finished [stripAudio] output
     * (the clip it came from is still there untouched), and empty clips from a
     * recording the tablet lost power in the middle of.
     */
    fun sweepTemporaries(context: Context) {
        dir(context).listFiles { f ->
            f.isFile && (f.name.startsWith("tmp_") || f.length() == 0L)
        }?.forEach { it.delete() }
    }

    fun freeBytes(context: Context): Long = dir(context).usableSpace

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun viewIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** "30 Jul 2026, 14:32", from the name so it survives a file copy. */
    fun displayName(file: File): String {
        val stamp = file.name.removePrefix(PREFIX).removeSuffix(EXT)
        val parsed = runCatching { fileStamp.parse(stamp) }.getOrNull()
        return displayStamp.format(parsed ?: Date(file.lastModified()))
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
    }

    /** mm:ss, or h:mm:ss past an hour. */
    fun formatDuration(millis: Long): String {
        val total = millis / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /**
     * Rewrite [file] in place with the audio track dropped.
     *
     * There is no way to record silently: AUSBC's muxer only starts once it has
     * both a video and an audio track, so a clip is always written with sound
     * and the track is removed afterwards. Pure remux, the video is copied
     * sample-for-sample, not re-encoded.
     *
     * Returns false and leaves the original untouched on any failure: a clip
     * with unwanted audio beats no clip at all.
     */
    fun stripAudio(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val temp = File(file.parentFile, "tmp_" + file.name)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val ok = runCatching {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return false
            val format = extractor.getTrackFormat(videoTrack)

            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer!!.addTrack(format)
            muxer!!.start()

            extractor.selectTrack(videoTrack)
            // Big enough for a 1080p keyframe; the format usually says so itself.
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocate(maxInput.coerceAtLeast(64 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer!!.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            true
        }.getOrDefault(false)

        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { extractor.release() }

        if (!ok || temp.length() == 0L) {
            temp.delete()
            return false
        }
        // Keep the original's timestamp so the list order and displayed name
        // (which falls back to lastModified) do not jump.
        val stamp = file.lastModified()
        if (!file.delete() || !temp.renameTo(file)) {
            temp.delete()
            return false
        }
        file.setLastModified(stamp)
        return true
    }

    /** Container duration in ms, or null if the file is unreadable, which is
     *  exactly what a clip whose muxer never finished looks like. */
    fun durationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        }.getOrNull().also { runCatching { retriever.release() } }
    }
}
