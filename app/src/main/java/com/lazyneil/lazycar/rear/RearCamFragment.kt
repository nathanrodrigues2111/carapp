package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

class RearCamFragment : CameraFragment() {

    companion object {
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val DIAG_TIMEOUT_MS = 8000L
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var gotFrame = false

    /** Whether the camera has ever delivered a frame, the activity uses this
     *  to tell a real detach from a webcam re-enumerating. */
    val hasFrames get() = gotFrame

    /** Base path (no extension) of the clip being written, if any. */
    private var recordingBase: String? = null
    val isRecording get() = recordingBase != null

    private var cameraView: AspectRatioTextureView? = null
    private var guidelinesView: View? = null

    private val diagnose = Runnable {
        if (!gotFrame) {
            (activity as? RearActivity)?.cameraStalled()
        }
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = inflater.inflate(R.layout.fragment_rear_cam, container, false)
        guidelinesView = root.findViewById(R.id.guidelines)
        applyPrefs()
        return root
    }

    /** Re-read prefs (lines, mirror). The controls that change them live on the
     *  activity, which calls this after every toggle and on return from
     *  Settings. */
    fun applyPrefs() {
        val prefs = context?.getSharedPreferences("rearview", 0) ?: return
        guidelinesView?.visibility =
            if (prefs.getBoolean("lines", false)) View.VISIBLE else View.GONE
        cameraView?.scaleX = if (prefs.getBoolean("mirror", true)) -1f else 1f
    }

    override fun getCameraView(): IAspectRatio {
        val mirror = requireContext()
            .getSharedPreferences("rearview", 0).getBoolean("mirror", true)
        return AspectRatioTextureView(requireContext()).also {
            it.scaleX = if (mirror) -1f else 1f
            cameraView = it
        }
    }

    override fun getCameraViewContainer(): ViewGroup {
        return requireView().findViewById(R.id.camera_container)
    }

    override fun getCameraClient(): CameraClient {
        return CameraClient.newBuilder(requireContext())
            .setEnableGLES(true)
            .setCameraStrategy(CameraUvcStrategy(UsbContext(requireActivity())))
            .setCameraRequest(
                CameraRequest.Builder()
                    .setPreviewWidth(PREVIEW_WIDTH)
                    .setPreviewHeight(PREVIEW_HEIGHT)
                    .create()
            )
            .build()
    }

    override fun initData() {
        super.initData()
        addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
                if (!gotFrame) {
                    gotFrame = true
                    (activity as? RearActivity)?.videoStarted()
                }
            }
        })
        handler.postDelayed(diagnose, DIAG_TIMEOUT_MS)
    }

    /**
     * Start writing an mp4. Returns the file it will end up in, or null if the
     * camera is not actually streaming yet.
     *
     * AUSBC's Mp4Muxer only starts once BOTH a video and an audio track have
     * been added, so this needs a working microphone (RECORD_AUDIO) even though
     * nobody wants the audio, hence the activity's watchdog on [ICaptureCallBack.onBegin].
     */
    fun startRecording(): java.io.File? {
        if (isRecording || !isCameraOpened()) return null
        val base = Recordings.newBasePath(requireContext())
        recordingBase = base
        val activity = activity as? RearActivity
        // Duration 0: one continuous file. A non-zero value makes the muxer
        // rotate to _1, _2, … forever, which on a dash means filling the disk.
        captureVideoStart(object : ICaptureCallBack {
            override fun onBegin() {
                activity?.recordingStarted()
            }

            override fun onError(error: String?) {
                recordingBase = null
                activity?.recordingFailed(error ?: "unknown error")
            }

            /** Only fires after the library's MediaStore insert succeeds, which
             *  it need not for an app-private path, so the activity treats
             *  stop, not this, as the end of a clip. */
            override fun onComplete(path: String?) = Unit
        }, base, 0L)
        return Recordings.fileFor(base)
    }

    /** Returns the file that was being written, or null if not recording. */
    fun stopRecording(): java.io.File? {
        val base = recordingBase ?: return null
        recordingBase = null
        captureVideoStop()
        return Recordings.fileFor(base)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(diagnose)
        // Without this the mp4 keeps its header but never gets its moov atom
        // written, and the clip is unplayable.
        stopRecording()
        super.onDestroyView()
    }
}

/**
 * AUSBC 3.2.7 calls the two-argument registerReceiver for its USB permission
 * action, which throws on targetSdk 34 and up. This wrapper adds the export
 * flag. It returns itself as the application context because the library
 * stores getApplicationContext(), and it forwards the activity lifecycle so
 * the library still sees onDestroy.
 */
private class UsbContext(private val act: androidx.fragment.app.FragmentActivity) :
    android.content.ContextWrapper(act), androidx.lifecycle.LifecycleOwner {
    override val lifecycle get() = act.lifecycle
    override fun getApplicationContext() = this
    override fun registerReceiver(r: android.content.BroadcastReceiver?, f: android.content.IntentFilter?) =
        super.registerReceiver(r, f, android.content.Context.RECEIVER_EXPORTED)
}
