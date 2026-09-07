package com.bnyro.recorder.services

import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import com.bnyro.recorder.App
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioDeviceSource
import com.bnyro.recorder.obj.AudioFormat
import com.bnyro.recorder.util.PlayerHelper
import com.bnyro.recorder.util.Preferences

class AudioRecorderService : RecorderService() {
    override val notificationTitle: String
        get() = getString(R.string.recording_audio)

    override val fgServiceType: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            null
        }

    override fun start() {
        val audioFormat = AudioFormat.getCurrent()

        recorder = PlayerHelper.newRecorder(this).apply {
            val audioSource = Preferences.prefs.getInt(
                Preferences.audioDeviceSourceKey,
                AudioDeviceSource.DEFAULT.value
            )
            setAudioSource(audioSource)

            val sampleRatePref = Preferences.prefs.getInt(Preferences.audioSampleRateKey, DEFAULT_AUDIO_SAMPLE_RATE).takeIf { it > 0 } ?: DEFAULT_AUDIO_SAMPLE_RATE
            val audioBitrate = Preferences.prefs.getInt(Preferences.audioBitrateKey, DEFAULT_AUDIO_BITRATE).takeIf { it > 0 } ?: DEFAULT_AUDIO_BITRATE
            if (audioFormat.codec != MediaRecorder.AudioEncoder.OPUS || sampleRatePref in opusSampleRates) {
                setAudioSamplingRate(sampleRatePref)
            }
            setAudioEncodingBitRate(audioBitrate)

            val channels = Preferences.prefs.getInt(Preferences.audioChannelsKey, AudioChannels.STEREO.value)
            setAudioChannels(channels)

            setOutputFormat(audioFormat.format)
            setAudioEncoder(audioFormat.codec)

            recordingExtension = audioFormat.extension
            val tempFile = (application as App).fileRepository.getTempOutputFile(recordingExtension)
            tempOutputFile = tempFile
            setOutputFile(tempFile.absolutePath)

            val prepResult = runCatching {
                prepare()
            }
            if (prepResult.isFailure) {
                android.util.Log.e("AudioRecorderService", "MediaRecorder prepare failed", prepResult.exceptionOrNull())
                release()
                recorder = null
                stopRecording()
                return
            }

            setOnErrorListener { _, what, extra ->
                android.util.Log.e("AudioRecorderService", "MediaRecorder error: what=$what, extra=$extra")
                stopRecording()
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    android.util.Log.w("AudioRecorderService", "MediaRecorder max file size or duration reached")
                    stopRecording()
                }
            }

            start()
        }

        super.start()
    }

    override fun getCurrentAmplitude() = recorder?.maxAmplitude

    companion object {
        private const val DEFAULT_AUDIO_SAMPLE_RATE = 48000
        private const val DEFAULT_AUDIO_BITRATE = 192000
        private val opusSampleRates = listOf(8000, 12000, 16000, 24000, 48000)
    }
}
