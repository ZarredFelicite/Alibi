package app.myzel394.alibi.services

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MediaRecorder.OnErrorListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import app.myzel394.alibi.NotificationHelper
import app.myzel394.alibi.db.RecordingInformation
import app.myzel394.alibi.enums.RecorderState
import app.myzel394.alibi.helpers.AudioBatchesFolder
import app.myzel394.alibi.helpers.BatchesFolder
import app.myzel394.alibi.ui.utils.MicrophoneInfo
import java.io.OutputStream

class AudioRecorderService :
    IntervalRecorderService<RecordingInformation, AudioBatchesFolder>() {
    override var batchesFolder = AudioBatchesFolder.viaInternalFolder(this)

    private val handler = Handler(Looper.getMainLooper())
    private val amplitudeUpdateRunnable = Runnable { updateAmplitude() }
    @Volatile
    private var amplitudeUpdatesEnabled = false
    private val ramAmplitudeLock = Any()
    private var ramCapture: RamAudioCapture? = null
    private var ramBuffer: EncodedAudioFrameRingBuffer? = null
    private var ramMode = false
    private var ramNextPresentationTimeUs = 0L
    @Volatile
    private var ramAmplitude = 0

    override val usesIntervalBatches: Boolean
        get() = !ramMode

    var amplitudes = mutableListOf<Int>()
        private set
    var amplitudesAmount = 1000

    var selectedMicrophone: MicrophoneInfo? = null

    var recorder: MediaRecorder? = null
        private set

    // Callbacks
    var onSelectedMicrophoneChange: (MicrophoneInfo?) -> Unit = {}
    var onMicrophoneDisconnected: () -> Unit = {}
    var onMicrophoneReconnected: () -> Unit = {}
    var onAmplitudeChange: ((List<Int>) -> Unit)? = null

    override fun startNewCycle() {
        if (ramMode) return

        super.startNewCycle()

        val newRecorder = createRecorder().also {
            it.prepare()
        }

        resetRecorder()
        startAudioDevice()

        try {
            recorder = newRecorder
            newRecorder.start()
        } catch (error: RuntimeException) {
            onError()
        }
    }

    override fun start() {
        ramBuffer = null
        ramNextPresentationTimeUs = 0L
        ramMode = tryStartRamCapture()
        try {
            super.start()
        } catch (error: RuntimeException) {
            if (ramMode) {
                stopRamCapture()
                ramMode = false
            }
            throw error
        }

        registerMicrophoneListener()
    }

    override fun pause() {
        super.pause()

        stopAmplitudeUpdates()
        if (ramMode) stopRamCapture() else resetRecorder()
    }

    override suspend fun stop() {
        stopAmplitudeUpdates()
        synchronized(ramAmplitudeLock) {
            amplitudeUpdatesEnabled = false
            ramAmplitude = 0
        }
        if (ramMode) stopRamCapture() else resetRecorder()
        unregisterMicrophoneListener()

        super.stop()
    }

    override fun resume() {
        if (ramMode && !tryStartRamCapture()) {
            ramMode = false
        }
        super.resume()
        if (!ramMode) {
            scheduleAmplitudeUpdate()
        }
    }

    override fun startForegroundService() {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
            getNotificationHelper().buildStartingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    // ==== Amplitude related ====
    private fun getAmplitudeAmount(): Int = amplitudesAmount

    private fun getAmplitude(): Int {
        if (ramMode) return ramAmplitude

        return try {
            recorder!!.maxAmplitude
        } catch (error: IllegalStateException) {
            0
        } catch (error: RuntimeException) {
            0
        }
    }

    private fun updateAmplitude() {
        if (!amplitudeUpdatesEnabled || state !== RecorderState.RECORDING) {
            return
        }

        amplitudes.add(getAmplitude())
        onAmplitudeChange?.invoke(amplitudes)

        // Delete old amplitudes
        if (amplitudes.size > getAmplitudeAmount()) {
            // Should be more efficient than dropping the elements, getting a new list
            // clearing old list and adding new elements to it
            repeat(amplitudes.size - getAmplitudeAmount()) {
                amplitudes.removeAt(0)
            }
        }

        scheduleAmplitudeUpdate()
    }

    /** Enable polling only while a visible visualizer is observing the recording. */
    fun setAmplitudeUpdatesEnabled(enabled: Boolean) {
        synchronized(ramAmplitudeLock) {
            amplitudeUpdatesEnabled = enabled
            ramAmplitude = 0
        }
        stopAmplitudeUpdates()
        if (enabled) {
            scheduleAmplitudeUpdate()
        }
    }

    private fun stopAmplitudeUpdates() {
        handler.removeCallbacks(amplitudeUpdateRunnable)
    }

    private fun scheduleAmplitudeUpdate() {
        if (amplitudeUpdatesEnabled && state === RecorderState.RECORDING) {
            handler.postDelayed(amplitudeUpdateRunnable, 100)
        }
    }

    // ==== Encoded RAM buffer ====
    private fun tryStartRamCapture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val audioSettings = settings.audioRecorderSettings
        if (!audioSettings.experimentalRamBuffer ||
            audioSettings.getEncoder() != MediaRecorder.AudioEncoder.AAC ||
            audioSettings.getOutputFormat() != MediaRecorder.OutputFormat.AAC_ADTS
        ) {
            return false
        }

        val estimatedBytes = audioSettings.getEffectiveBitRate().toLong() *
                settings.maxDuration * 5 / 32_000L
        if (estimatedBytes > MAX_RAM_BUFFER_BYTES) return false

        val buffer = ramBuffer ?: EncodedAudioFrameRingBuffer(
            maxDurationUs = settings.maxDuration * 1000L,
            maxBytes = estimatedBytes.coerceAtLeast(1).toInt(),
        )
        return runCatching {
            if (selectedMicrophone != null) startAudioDevice()
            RamAudioCapture(
                audioSettings = audioSettings,
                buffer = buffer,
                shouldReportAmplitude = { amplitudeUpdatesEnabled },
                onAmplitude = {
                    synchronized(ramAmplitudeLock) {
                        if (amplitudeUpdatesEnabled) ramAmplitude = it
                    }
                },
                onError = { handleRamCaptureError() },
                startPresentationTimeUs = ramNextPresentationTimeUs,
            ).also {
                ramCapture = it
                ramBuffer = buffer
                it.start()
            }
            true
        }.getOrElse {
            ramCapture = null
            ramBuffer = null
            if (selectedMicrophone != null) clearAudioDevice()
            false
        }
    }

    private fun handleRamCaptureError() {
        if (!ramMode || state == RecorderState.STOPPED) return

        // The capture has already released its resources before this callback. Mark the
        // service stopped before handing control to the existing save/error flow.
        changeState(RecorderState.STOPPED)
        onError()
    }

    private fun stopRamCapture() {
        val capture = ramCapture
        capture?.stop()
        if (capture != null) {
            ramNextPresentationTimeUs = capture.nextPresentationTimeUs
        }
        ramCapture = null
        if (selectedMicrophone != null) {
            runCatching { clearAudioDevice() }
        }
    }

    private fun materializeRamSnapshot(): Long? {
        val buffer = ramBuffer ?: return null
        val data = buffer.snapshotAsAdtsAac(
            sampleRate = settings.audioRecorderSettings.getEffectiveSamplingRate(),
            channelCount = 1,
        )
        if (data.isEmpty()) return null

        val fileName = if (batchesFolder.type == BatchesFolder.BatchType.MEDIA) {
            "${batchesFolder.mediaPrefix}0.aac"
        } else {
            "0.aac"
        }
        val output: OutputStream = when (batchesFolder.type) {
            BatchesFolder.BatchType.INTERNAL ->
                batchesFolder.asInternalGetFile(0, fileName.substringAfterLast('.')).outputStream()

            BatchesFolder.BatchType.CUSTOM -> {
                val file = batchesFolder.getCustomDefinedFolder().findFile(fileName)
                    ?: batchesFolder.getCustomDefinedFolder().createFile("audio/aac", fileName)!!
                contentResolver.openOutputStream(file.uri, "wt")!!
            }

            BatchesFolder.BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = batchesFolder.getOrCreateMediaFile(
                        name = fileName,
                        mimeType = "audio/aac",
                        relativePath = AudioBatchesFolder.SCOPED_STORAGE_RELATIVE_PATH,
                    )
                    contentResolver.openOutputStream(uri, "wt")!!
                } else {
                    batchesFolder.asMediaGetLegacyFile(fileName).outputStream()
                }
            }
        }
        output.use { it.write(data) }
        return buffer.durationUs() / 1000L
    }

    // ==== Audio device related ====

    /// Tell Android to use the correct bluetooth microphone, if any selected
    private fun startAudioDevice() {
        if (selectedMicrophone == null) {
            return
        }

        val audioManger = getSystemService(AUDIO_SERVICE)!! as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManger.setCommunicationDevice(selectedMicrophone!!.deviceInfo)
        } else {
            audioManger.startBluetoothSco()
        }
    }

    private fun clearAudioDevice() {
        val audioManger = getSystemService(AUDIO_SERVICE)!! as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManger.clearCommunicationDevice()
        } else {
            audioManger.stopBluetoothSco()
        }
    }

    private fun getNameForMediaFile() =
        "${batchesFolder.mediaPrefix}$counter.${settings.audioRecorderSettings.fileExtension}"

    // ==== Actual recording related ====
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }.apply {
            val audioSettings = settings.audioRecorderSettings

            // Audio Source is kinda strange, here are my experimental findings using a Pixel 7 Pro
            // and Redmi Buds 3 Pro:
            // - MIC: Uses the bottom microphone of the phone (17)
            // - CAMCORDER: Uses the top microphone of the phone (2)
            // - VOICE_COMMUNICATION: Uses the bottom microphone of the phone (17)
            // - DEFAULT: Uses the bottom microphone of the phone (17)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setAudioChannels(1)

            when (batchesFolder.type) {
                BatchesFolder.BatchType.INTERNAL -> {
                    setOutputFile(
                        batchesFolder.asInternalGetFile(
                            counter,
                            audioSettings.fileExtension
                        ).absolutePath
                    )
                }

                BatchesFolder.BatchType.CUSTOM -> {
                    setOutputFile(
                        batchesFolder.asCustomGetFileDescriptor(
                            counter,
                            audioSettings.fileExtension
                        )
                    )
                }

                BatchesFolder.BatchType.MEDIA -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setOutputFile(
                            batchesFolder.asMediaGetScopedStorageFileDescriptor(
                                getNameForMediaFile(),
                                "audio/${audioSettings.fileExtension}"
                            )
                        )
                    } else {
                        val name = getNameForMediaFile()
                        val file = batchesFolder.asMediaGetLegacyFile(name)

                        setOutputFile(file.absolutePath)
                    }
                }
            }

            setOutputFormat(audioSettings.getOutputFormat())

            setAudioEncoder(audioSettings.getEncoder())
            setAudioEncodingBitRate(audioSettings.getEffectiveBitRate())
            setAudioSamplingRate(audioSettings.getEffectiveSamplingRate())
            setOnErrorListener(OnErrorListener { _, _, _ ->
                onError()
            })
        }
    }

    // ==== Microphone related ====
    private fun resetRecorder() {
        runCatching {
            recorder?.apply {
                stop()
                reset()
                release()
            }
            clearAudioDevice()
            batchesFolder.cleanup()
        }
    }

    fun changeMicrophone(microphone: MicrophoneInfo?) {
        selectedMicrophone = microphone
        onSelectedMicrophoneChange(microphone)

        if (state == RecorderState.RECORDING) {
            if (ramMode) {
                stopRamCapture()
                if (!tryStartRamCapture()) {
                    ramMode = false
                    startNewCycle()
                }
            } else {
                startNewCycle()
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesAdded(addedDevices)

            if (selectedMicrophone == null) {
                return
            }

            // We can't compare the ID, as it seems to be changing on each reconnect
            val newDevice = addedDevices?.find {
                it.productName == selectedMicrophone!!.deviceInfo.productName &&
                        it.isSink == selectedMicrophone!!.deviceInfo.isSink &&
                        it.type == selectedMicrophone!!.deviceInfo.type && (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            it.address == selectedMicrophone!!.deviceInfo.address
                        } else true
                        )
            }
            if (newDevice != null) {
                changeMicrophone(MicrophoneInfo.fromDeviceInfo(newDevice))

                onMicrophoneReconnected()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesRemoved(removedDevices)

            if (selectedMicrophone == null) {
                return
            }

            if (removedDevices?.find { it.id == selectedMicrophone!!.deviceInfo.id } != null) {
                onMicrophoneDisconnected()
            }
        }
    }

    private fun registerMicrophoneListener() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE)!! as AudioManager

        audioManager.registerAudioDeviceCallback(
            audioDeviceCallback,
            Handler(Looper.getMainLooper())
        )
    }

    private fun unregisterMicrophoneListener() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE)!! as AudioManager

        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    // ==== Settings ====
    override fun getRecordingInformation(): RecordingInformation {
        val duration = if (ramMode) materializeRamSnapshot() else null
        return RecordingInformation(
            folderPath = batchesFolder.exportFolderForSettings(),
            recordingStart = recordingStart,
            maxDuration = settings.maxDuration,
            batchesAmount = if (ramMode && duration != null) {
                1
            } else {
                batchesFolder.getBatchesForFFmpeg().size
            },
            fileExtension = settings.audioRecorderSettings.fileExtension,
            intervalDuration = settings.intervalDuration,
            type = RecordingInformation.Type.AUDIO,
            duration = duration,
        )
    }

    companion object {
        private const val MAX_RAM_BUFFER_BYTES = 32L * 1024L * 1024L
    }
}