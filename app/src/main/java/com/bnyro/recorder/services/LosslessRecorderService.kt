package com.bnyro.recorder.services

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.bnyro.recorder.App
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioDeviceSource
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.util.PcmConverter
import com.bnyro.recorder.util.Preferences
import de.sciss.jump3r.Main as Jump3rMain
import java.io.File
import kotlin.concurrent.thread
import kotlin.experimental.and
import kotlin.experimental.or

@RequiresApi(Build.VERSION_CODES.M)
class LosslessRecorderService : RecorderService() {
    override val notificationTitle: String
        get() = getString(R.string.recording_audio)

    override val fgServiceType: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            null
        }

    private var audioRecorder: AudioRecord? = null
    private var recorderThread: Thread? = null
    private var pcmConverter: PcmConverter? = null
    private var currentMaxAmplitude: Int? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        super.start()

        val audioSource = Preferences.prefs.getInt(
            Preferences.audioDeviceSourceKey,
            AudioDeviceSource.DEFAULT.value
        )
        val channelPref = Preferences.prefs.getInt(
            Preferences.audioChannelsKey,
            AudioChannels.STEREO.value
        )
        val channelMask = if (channelPref == AudioChannels.MONO.value) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val sampleRatePref = Preferences.prefs.getInt(
            Preferences.audioSampleRateKey,
            SAMPLING_RATE
        ).takeIf { it > 0 } ?: SAMPLING_RATE

        val audioFormat: AudioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRatePref)
            .setChannelMask(channelMask)
            .setEncoding(FORMAT)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(sampleRatePref, channelMask, FORMAT)
        val bufferSize = (2 * minBuf).coerceAtLeast(4096)

        audioRecorder = AudioRecord(
            audioSource,
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding,
            bufferSize
        )

        pcmConverter = PcmConverter(
            audioFormat.sampleRate.toLong(),
            audioFormat.channelCount,
            2 * 8
        )

        audioRecorder?.startRecording()

        outputFile = DocumentFile.fromFile(
            File(filesDir, "temp.pcm").also {
                it.createNewFile()
            }
        )

        recorderThread = thread(true) {
            writeAudioDataToFile()
        }
    }

    private fun writeAudioDataToFile() {
        val data = ByteArray(BUFFER_SIZE_IN_BYTES / 2)
        outputFile?.uri?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                while (recorderState != RecorderState.IDLE) {
                    audioRecorder?.read(data, 0, data.size)?.let {
                        if (recorderState == RecorderState.ACTIVE) {
                            outputStream.write(data)
                            currentMaxAmplitude = getAmplitudesFromBytes(data).max()
                        }
                    }
                }
            }
        }
    }

    private fun getAmplitudesFromBytes(bytes: ByteArray): IntArray {
        val amps = IntArray(bytes.size / 2)
        var i = 0
        while (i < bytes.size) {
            var buff = bytes[i + 1].toShort()
            var buff2 = bytes[i].toShort()

            buff = (buff.toInt() and 0xFF shl 8).toShort()
            buff2 = (buff2 and 0xFF)

            val res = (buff or buff2)
            amps[if (i == 0) 0 else i / 2] = res.toInt()
            i += 2
        }
        return amps
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun pause() {
        super.pause()
        audioRecorder?.stop()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun resume() {
        super.resume()
        audioRecorder?.startRecording()
    }

    private fun convertToWav() {
        val inputStream = contentResolver.openInputStream(outputFile?.uri ?: return) ?: return
        val outputStream = (application as App).fileRepository
            .getOutputFile(FILE_NAME_EXTENSION_WAV)
            ?.let {
                contentResolver.openOutputStream(it.uri)
            }

        if (outputStream == null) {
            Toast.makeText(this, R.string.cant_access_selected_folder, Toast.LENGTH_LONG).show()
            return
        }

        pcmConverter?.convertToWave(inputStream, outputStream, BUFFER_SIZE_IN_BYTES)
        outputFile?.delete()
    }

    private fun convertToMp3() {
        val pcmFile = File(filesDir, "temp.pcm")
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            outputFile?.delete()
            return
        }
        val tempWav = File(cacheDir, "temp_${System.currentTimeMillis()}.wav")
        val tempMp3 = File(cacheDir, "temp_${System.currentTimeMillis()}.mp3")
        try {
            pcmFile.inputStream().use { input ->
                tempWav.outputStream().use { output ->
                    pcmConverter?.convertToWave(input, output, BUFFER_SIZE_IN_BYTES)
                }
            }
            val bitrate = Preferences.prefs.getInt(Preferences.audioBitrateKey, 192_000).takeIf { it > 0 } ?: 192_000
            val bitrateKbps = (bitrate / 1000).coerceIn(32, 320)
            val sampleRatePref = Preferences.prefs.getInt(Preferences.audioSampleRateKey, SAMPLING_RATE).takeIf { it > 0 } ?: SAMPLING_RATE
            val args = arrayOf(
                "-b", bitrateKbps.toString(),
                "-s", (sampleRatePref / 1000.0).toString(),
                tempWav.absolutePath,
                tempMp3.absolutePath
            )
            Jump3rMain().run(args)
            if (tempMp3.exists() && tempMp3.length() > 0L) {
                (application as App).fileRepository.commitOutputFile(tempMp3, FILE_NAME_EXTENSION_MP3)
            } else {
                android.util.Log.e("LosslessRecorderService", "MP3 encoding produced empty file")
            }
        } catch (e: Exception) {
            android.util.Log.e("LosslessRecorderService", "Error encoding MP3", e)
        } finally {
            tempWav.delete()
            tempMp3.delete()
            outputFile?.delete()
        }
    }

    override fun stopRecording() {
        if (recorderState == RecorderState.IDLE) return
        audioRecorder?.stop()
        audioRecorder?.release()
        audioRecorder = null
        recorderThread = null

        val isLosslessWav = Preferences.prefs.getBoolean(Preferences.losslessRecorderKey, false)
        if (isLosslessWav) {
            convertToWav()
        } else {
            convertToMp3()
        }

        super.stopRecording()
    }

    override fun getCurrentAmplitude() = currentMaxAmplitude

    companion object {
        private const val FILE_NAME_EXTENSION_WAV = "wav"
        private const val FILE_NAME_EXTENSION_MP3 = "mp3"
        private const val SAMPLING_RATE = 44100
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE_IN_BYTES = 2 * AudioRecord.getMinBufferSize(
            SAMPLING_RATE,
            CHANNEL_IN,
            FORMAT
        )
    }
}
