package com.bnyro.recorder.ui.models

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.canvas_overlay.CanvasOverlay
import com.bnyro.recorder.enums.AudioDeviceSource
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.services.AudioRecorderService
import com.bnyro.recorder.services.LosslessRecorderService
import com.bnyro.recorder.services.RecorderService
import com.bnyro.recorder.services.ScreenRecorderService
import com.bnyro.recorder.obj.AudioFormat
import com.bnyro.recorder.util.PermissionHelper
import com.bnyro.recorder.util.Preferences

class RecorderModel : ViewModel() {
    private val supportsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    var recorderState by mutableStateOf(RecorderState.IDLE)
    var recordedTime by mutableStateOf<Long?>(null)
    var countdownRemaining by mutableStateOf<Int?>(null)
    val recordedAmplitudes = mutableStateListOf<Int>()
    private var activityResult: ActivityResult? = null
    private var canvasOverlay: CanvasOverlay? = null
    private var boundContext: java.lang.ref.WeakReference<Context>? = null

    private var startRealtime = 0L
    private var pausedDuration = 0L
    private var pauseTimestamp = 0L
    private var countdownRunnable: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("StaticFieldLeak")
    private var recorderService: RecorderService? = null

    private val timeRunnable = object : Runnable {
        override fun run() {
            if (recorderState == RecorderState.ACTIVE) {
                val elapsed = (android.os.SystemClock.elapsedRealtime() - startRealtime - pausedDuration) / 100
                recordedTime = elapsed
                handler.postDelayed(this, 100)
            }
        }
    }

    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (recorderState == RecorderState.ACTIVE) {
                recorderService?.getCurrentAmplitude()?.let {
                    if (recordedAmplitudes.size >= 90) recordedAmplitudes.removeAt(0)
                    recordedAmplitudes.add(it)
                }
                handler.postDelayed(this, 100)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            recorderService = (service as RecorderService.LocalBinder).getService()
            recorderService?.onRecorderStateChanged = {
                recorderState = it
            }
            activityResult?.let { (recorderService as? ScreenRecorderService)?.prepare(it) }
            if (supportsOverlay) canvasOverlay?.show()
            recorderService?.start()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            recorderService = null
        }
    }

    fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownRemaining = null
    }

    private fun startCountdown(seconds: Int, onComplete: () -> Unit) {
        cancelCountdown()
        countdownRemaining = seconds
        val step = object : Runnable {
            override fun run() {
                val current = countdownRemaining ?: return
                if (current <= 1) {
                    countdownRemaining = null
                    countdownRunnable = null
                    onComplete()
                } else {
                    countdownRemaining = current - 1
                    handler.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = step
        handler.postDelayed(step, 1000)
    }

    fun startVideoRecorder(context: Context, result: ActivityResult) {
        val delay = Preferences.prefs.getInt(Preferences.countdownSecondsKey, 0)
        if (delay > 0) {
            startCountdown(delay) {
                executeStartVideoRecorder(context, result)
            }
        } else {
            executeStartVideoRecorder(context, result)
        }
    }

    private fun executeStartVideoRecorder(context: Context, result: ActivityResult) {
        activityResult = result
        val serviceIntent = Intent(context, ScreenRecorderService::class.java)
        startRecorderService(context, serviceIntent)
        val showOverlayAnnotation =
            Preferences.prefs.getBoolean(Preferences.showOverlayAnnotationToolKey, false)
        if (supportsOverlay && showOverlayAnnotation) {
            canvasOverlay = CanvasOverlay(context)
        }
    }

    @SuppressLint("NewApi")
    fun startAudioRecorder(context: Context) {
        val audioPermission = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioPermission.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val internalAudio = Preferences.prefs.getInt(
            Preferences.audioDeviceSourceKey,
            0
        ) == AudioDeviceSource.REMOTE_SUBMIX.value
        if (internalAudio) {
            audioPermission.add(Manifest.permission.CAPTURE_AUDIO_OUTPUT)
        }

        if (!PermissionHelper.checkPermissions(context, audioPermission.toTypedArray())) {
            Toast.makeText(
                context,
                context.getString(R.string.no_sufficient_permissions), Toast.LENGTH_SHORT
            )
                .show()
            return
        }

        val delay = Preferences.prefs.getInt(Preferences.countdownSecondsKey, 0)
        if (delay > 0) {
            startCountdown(delay) {
                executeStartAudioRecorder(context)
            }
        } else {
            executeStartAudioRecorder(context)
        }
    }

    private fun executeStartAudioRecorder(context: Context) {
        val serviceIntent =
            if (Preferences.prefs.getBoolean(Preferences.losslessRecorderKey, false) ||
                AudioFormat.getCurrent().extension == "mp3") {
                Intent(context, LosslessRecorderService::class.java)
            } else {
                Intent(context, AudioRecorderService::class.java)
            }

        startRecorderService(context, serviceIntent)
    }

    private fun startRecorderService(context: Context, intent: Intent) {
        boundContext = java.lang.ref.WeakReference(context.applicationContext)
        runCatching {
            context.unbindService(connection)
        }

        listOfNotNull(
            AudioRecorderService::class.java,
            ScreenRecorderService::class.java,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) LosslessRecorderService::class.java else null
        ).forEach {
            runCatching {
                context.stopService(Intent(context, it))
            }
        }
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        startElapsedTimeCounter()
        handler.postDelayed(amplitudeRunnable, 100)
    }

    fun stopRecording() {
        cancelCountdown()
        recorderService?.stopRecording()
        recorderService = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvasOverlay?.remove()
        canvasOverlay = null
        handler.removeCallbacks(timeRunnable)
        handler.removeCallbacks(amplitudeRunnable)
        recordedTime = null
        recordedAmplitudes.clear()
        recorderState = RecorderState.IDLE
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun pauseRecording() {
        pauseTimestamp = android.os.SystemClock.elapsedRealtime()
        recorderService?.pause()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun resumeRecording() {
        if (pauseTimestamp > 0L) {
            pausedDuration += (android.os.SystemClock.elapsedRealtime() - pauseTimestamp)
            pauseTimestamp = 0L
        }
        recorderService?.resume()
        handler.postDelayed(timeRunnable, 100)
        if (recorderService is AudioRecorderService) {
            handler.postDelayed(amplitudeRunnable, 100)
        }
    }

    private fun startElapsedTimeCounter() {
        startRealtime = android.os.SystemClock.elapsedRealtime()
        pausedDuration = 0L
        pauseTimestamp = 0L
        recordedTime = 0L
        handler.removeCallbacks(timeRunnable)
        handler.postDelayed(timeRunnable, 100)
    }

    override fun onCleared() {
        super.onCleared()
        cancelCountdown()
        handler.removeCallbacksAndMessages(null)
        boundContext?.get()?.runCatching {
            unbindService(connection)
        }
    }

    @SuppressLint("NewApi")
    fun hasScreenRecordingPermissions(context: Context): Boolean {
        val requiredPermissions = arrayListOf<String>()

        val recordAudio =
            Preferences.prefs.getInt(Preferences.audioSourceKey, 0) == AudioSource.MICROPHONE.value

        if (recordAudio) requiredPermissions.add(Manifest.permission.RECORD_AUDIO)

        val internalAudio = Preferences.prefs.getInt(
            Preferences.audioDeviceSourceKey,
            0
        ) == AudioDeviceSource.REMOTE_SUBMIX.value
        if (internalAudio) requiredPermissions.add(Manifest.permission.CAPTURE_AUDIO_OUTPUT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (requiredPermissions.isEmpty()) return true

        val granted = PermissionHelper.checkPermissions(context, requiredPermissions.toTypedArray())
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.no_sufficient_permissions), Toast.LENGTH_SHORT
            )
                .show()
        }
        return granted
    }
}
