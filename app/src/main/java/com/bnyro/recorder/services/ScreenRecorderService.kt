package com.bnyro.recorder.services

import android.app.Activity
import android.content.Context
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import com.bnyro.recorder.App
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioDeviceSource
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.VideoFormat
import com.bnyro.recorder.obj.VideoResolution
import com.bnyro.recorder.util.PlayerHelper
import com.bnyro.recorder.util.Preferences

class ScreenRecorderService : RecorderService() {
    override val notificationTitle: String
        get() = getString(R.string.recording_screen)

    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var activityResult: ActivityResult? = null
    private var displayManager: DisplayManager? = null
    private var touchesEnabled = false
    private var previousShowTouches = 0
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val newRes = getScreenResolution()
            virtualDisplay?.resize(newRes.width, newRes.height, newRes.density)
        }
    }

    override val fgServiceType: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            null
        }

    fun prepare(data: ActivityResult) {
        this.activityResult = data
        initMediaProjection()
    }

    private fun initMediaProjection() {
        val mProjectionManager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
        val intentData = activityResult?.data ?: run {
            Log.e("ScreenRecorderService", "No ActivityResult data provided")
            stopRecording()
            return
        }
        try {
            mediaProjection = mProjectionManager.getMediaProjection(
                Activity.RESULT_OK,
                intentData
            )
        } catch (e: SecurityException) {
            Log.e("ScreenRecorderService", "MediaProjection security exception", e)
            stopRecording()
            return
        } catch (e: IllegalStateException) {
            Log.e("ScreenRecorderService", "MediaProjection invalid state", e)
            stopRecording()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, null)
        }
    }

    override fun start() {
        val showTouches = Preferences.prefs.getBoolean(Preferences.showTouchesKey, false)
        if (showTouches) {
            previousShowTouches = runCatching {
                Settings.System.getInt(contentResolver, "show_touches", 0)
            }.getOrDefault(0)
            if (trySetShowTouches(1)) {
                touchesEnabled = true
            }
        }

        val audioSource = AudioSource.fromInt(
            Preferences.prefs.getInt(Preferences.audioSourceKey, 0)
        )
        val resolution = getScreenResolution()
        val videoFormat = VideoFormat.getCurrent()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)

        recorder = PlayerHelper.newRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            if (audioSource == AudioSource.MICROPHONE) {
                Preferences.prefs.getInt(
                    Preferences.audioDeviceSourceKey,
                    AudioDeviceSource.DEFAULT.value
                ).let {
                    setAudioSource(it)
                }

                Preferences.prefs.getInt(Preferences.audioSampleRateKey, -1).takeIf {
                    it > 0
                }?.let {
                    setAudioSamplingRate(it)
                    setAudioEncodingBitRate(it * 32 * 2)
                }

                Preferences.prefs.getInt(Preferences.audioBitrateKey, -1).takeIf { it > 0 }?.let {
                    setAudioEncodingBitRate(it)
                }
                Preferences.prefs.getInt(Preferences.audioChannelsKey, AudioChannels.MONO.value).let {
                    setAudioChannels(it)
                }
            }

            setOutputFormat(videoFormat.format)
            setVideoFrameRate(resolution.frameRate)
            setVideoEncoder(videoFormat.codec)

            val bitratePref = Preferences.prefs.getInt(Preferences.videoBitrateKey, -1)
            val autoBitrate = (BPP * resolution.frameRate * resolution.width * resolution.height).toInt()
            setVideoEncodingBitRate(bitratePref.takeIf { it > 0 } ?: autoBitrate)

            if (audioSource == AudioSource.MICROPHONE) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }

            setVideoSize(resolution.width, resolution.height)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                getString(R.string.app_name),
                resolution.width,
                resolution.height,
                resolution.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                null,
                null
            )

            recordingExtension = videoFormat.extension
            val tempFile = (application as App).fileRepository.getTempOutputFile(recordingExtension)
            tempOutputFile = tempFile
            setOutputFile(tempFile.absolutePath)

            var prepareSuccess = runCatching { prepare() }.isSuccess
            if (!prepareSuccess && videoFormat.codec != MediaRecorder.VideoEncoder.H264) {
                Log.w("ScreenRecorderService", "Format ${videoFormat.name} prepare failed, falling back to H.264")
                reset()
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                if (audioSource == AudioSource.MICROPHONE) {
                    val audioDev = Preferences.prefs.getInt(
                        Preferences.audioDeviceSourceKey,
                        AudioDeviceSource.DEFAULT.value
                    )
                    setAudioSource(audioDev)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoFrameRate(resolution.frameRate)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(bitratePref.takeIf { it > 0 } ?: autoBitrate)
                if (audioSource == AudioSource.MICROPHONE) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setVideoSize(resolution.width, resolution.height)
                recordingExtension = "mp4"
                setOutputFile(tempFile.absolutePath)
                prepareSuccess = runCatching { prepare() }.isSuccess
            }

            if (!prepareSuccess) {
                Log.e("ScreenRecorderService", "Failed to prepare MediaRecorder")
                release()
                recorder = null
                stopRecording()
                return
            }

            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.w("ScreenRecorderService", "MediaRecorder max file size or duration reached")
                    stopRecording()
                }
            }

            start()

            virtualDisplay?.surface = surface
        }

        super.start()
    }

    private fun getScreenResolution(): VideoResolution {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val refreshRate = display?.refreshRate?.toInt() ?: 60

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.maximumWindowMetrics.bounds
            val density = resources.configuration.densityDpi
            return VideoResolution(
                bounds.width(),
                bounds.height(),
                density,
                refreshRate
            )
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            return VideoResolution(
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                refreshRate
            )
        }
    }

    override fun stopRecording() {
        if (touchesEnabled) {
            trySetShowTouches(previousShowTouches)
            touchesEnabled = false
        }
        displayManager?.unregisterDisplayListener(displayListener)
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        super.stopRecording()
    }

    override fun onDestroy() {
        if (touchesEnabled) {
            trySetShowTouches(previousShowTouches)
            touchesEnabled = false
        }
        super.onDestroy()
    }

    private fun trySetShowTouches(value: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put system show_touches $value"))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.w("ScreenRecorderService", "Root settings put failed: ${e.message}")
            false
        }
    }

    override fun getCurrentAmplitude() = recorder?.maxAmplitude

    companion object {
        private const val BPP = 0.25f
    }
}
