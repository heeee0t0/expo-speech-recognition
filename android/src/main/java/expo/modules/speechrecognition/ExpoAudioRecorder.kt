package expo.modules.speechrecognition

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

interface AudioRecorder {
    fun start()

    fun stop()
}

/**
 * ExpoAudioRecorder allows us to record to a 16hz pcm stream for use in SpeechRecognition
 *
 * Once stopped, the recording stream is written to a wav file for external use
 */
class ExpoAudioRecorder(
    private val context: Context,
    // Optional output file path
    private val outputFilePath: String?,
) : AudioRecorder {
    private var audioRecorder: AudioRecord? = null
    

    var outputFile: File? = null
    var outputFileUri = "file://$outputFilePath"

    /** The file where the mic stream is being output to */
    private val tempPcmFile: File = createTempPcmFile()
    val recordingParcel: ParcelFileDescriptor
    private var outputStream: AutoCloseOutputStream?
    
    private val bufferQueue = mutableListOf<Pair<Long, ByteArray>>() // buffer
    var beginningOfSpeechTime: Long? = null
    var recordingStartTime: Long = 0L

    init {
        // tempPcmFile = createTempPcmFile()
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            recordingParcel = pipe[0]
            outputStream = AutoCloseOutputStream(pipe[1])
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create pipe", e)
            e.printStackTrace()
            throw e
        }
    }

    val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    private var recordingThread: Thread? = null
    private var isRecordingAudio = false

    companion object {
        private const val TAG = "ExpoAudioRecorder"

        private fun shortReverseBytes(s: Short): Int =
            java.lang.Short
                .reverseBytes(s)
                .toInt()

        fun appendWavHeader(
            outputFilePath: String,
            pcmFile: File,
            sampleRateInHz: Int,
        ): File {
            val outputFile = File(outputFilePath)
            val audioDataLength = pcmFile.length()
            val numChannels = 1
            val bitsPerSample = 16

            DataOutputStream(FileOutputStream(outputFile)).use { out ->
                val totalDataLen = 36 + audioDataLength
                val byteRate = sampleRateInHz * numChannels * bitsPerSample / 8
                val blockAlign = numChannels * bitsPerSample / 8

                // Write the RIFF chunk descriptor
                out.writeBytes("RIFF") // ChunkID
                out.writeInt(Integer.reverseBytes(totalDataLen.toInt())) // ChunkSize
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                out.writeInt(Integer.reverseBytes(16)) // Subchunk1Size (16 for PCM)
                out.writeShort(shortReverseBytes(1)) // AudioFormat (1 for PCM)
                out.writeShort(shortReverseBytes(numChannels.toShort())) // NumChannels
                out.writeInt(Integer.reverseBytes(sampleRateInHz)) // SampleRate
                out.writeInt(Integer.reverseBytes(byteRate)) // ByteRate
                out.writeShort(shortReverseBytes(blockAlign.toShort())) // BlockAlign
                out.writeShort(shortReverseBytes(bitsPerSample.toShort())) // BitsPerSample

                // Write the data sub-chunk
                out.writeBytes("data")
                out.writeInt(Integer.reverseBytes(audioDataLength.toInt()))

                try {
                    val pcmData = pcmFile.readBytes()
                    out.write(pcmData)
                    // pcmFile.delete() // pcmFile deleted another sequence
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to read PCM file", e)
                    e.printStackTrace()
                }
            }

            return outputFile
        }
    }

    private fun createTempPcmFile(): File {
        val file = File(context.cacheDir, "temp_${UUID.randomUUID()}.pcm")
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return file
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            sampleRateInHz,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes,
        )

    override fun start() {
        createRecorder().apply {
            audioRecorder = this
            recordingStartTime = System.currentTimeMillis()

            // First check whether the above object actually initialized
            if (this.state != AudioRecord.STATE_INITIALIZED) {
                return
            }

            this.startRecording()
            isRecordingAudio = true

            // Start thread
            recordingThread =
                thread {
                    streamAudioToPipe()
                }
        }
        Log.d(TAG, "🎙️ [AudioRecorder] Started recording to pipe and file: $tempPcmFile")
    }

    override fun stop() {
        isRecordingAudio = false
        audioRecorder?.stop()
        audioRecorder?.release()
        audioRecorder = null
        recordingThread = null
        if (outputFilePath != null) {
            try {
                outputFile =
                    appendWavHeader(
                        outputFilePath,
                        tempPcmFile,
                        sampleRateInHz,
                    )
            } catch (e: IOException) {
                Log.e(TAG, "Failed to append WAV header", e)
                e.printStackTrace()
            }
        }
        // Close the ParcelFileDescriptor
        try {
            recordingParcel.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        // And the output stream
        try {
            outputStream?.close()
            outputStream = null
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun saveWavSegment(fromTime: Long, toTime: Long, customFilePath: String): File? { // save wav file
        // 1. 시간 범위에 해당하는 버퍼 추출
        val selectedChunks = synchronized(bufferQueue) {
            bufferQueue.filter { it.first in fromTime..toTime }
                .map { it.second }
        }

        if (selectedChunks.isEmpty()) {
            Log.w(TAG, "❗ 선택된 PCM 청크가 없습니다. 저장하지 않음")
            return null
        }

        // 2. ByteArrayOutputStream으로 병합
        val outputStream = java.io.ByteArrayOutputStream()
        Log.d(TAG, "saveWavSegment: $selectedChunks")
        try {
            selectedChunks.forEach { chunk ->
                outputStream.write(chunk)
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ PCM 병합 실패", e)
            return null
        }

        val pcmBytes = outputStream.toByteArray()

        // 3. 임시 PCM 파일에 저장
        val pcmFile = File(context.cacheDir, "seg_${UUID.randomUUID()}.pcm")
        try {
            pcmFile.writeBytes(pcmBytes)
        } catch (e: IOException) {
            Log.e(TAG, "❌ PCM 파일 저장 실패", e)
            return null
        }

        // 4. WAV로 변환
        return try {
            val parentDir = File(customFilePath).parentFile
            if (parentDir != null && !parentDir.exists()) {
                val created = parentDir.mkdirs()
                Log.d(TAG, "📁 상위 디렉토리 생성됨: ${parentDir.absolutePath}, 성공 여부: $created")
            }
            val wavFile = appendWavHeader(customFilePath, pcmFile, sampleRateInHz)
            Log.i(TAG, "✅ saveWavSegment 성공: ${wavFile.absolutePath}, size=${wavFile.length()} bytes")
            // 🧹 저장 후 .pcm 삭제
            val deleted = pcmFile.delete()
            Log.d(TAG, "🧹 임시 PCM 삭제됨: ${pcmFile.absolutePath}, 성공 여부: $deleted")

            wavFile
        } catch (e: IOException) {
            Log.e(TAG, "❌ saveWavSegment: WAV 저장 실패", e)
            null
        }
    }

    private fun streamAudioToPipe() {
        // val tempFileOutputStream = FileOutputStream(tempPcmFile)
        val data = ByteArray(bufferSizeInBytes / 2)

        while (isRecordingAudio) {
            val read = audioRecorder!!.read(data, 0, data.size)
            val currentTime = System.currentTimeMillis()
            val chunk = data.copyOf(read)
            synchronized(bufferQueue) {
                bufferQueue.add(currentTime to chunk)
            }
            try {
                outputStream?.write(data, 0, read)
                outputStream?.flush()

                // // Write to the temp PCM file
                // if (outputFilePath != null) {
                //     tempFileOutputStream.write(data, 0, read)
                //     tempFileOutputStream.flush()
                // }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write to output stream", e)
                e.printStackTrace()
            }
        }
        // tempFileOutputStream.close()
    }

    fun saveFullWav(toTime: Long, customFilePath: String): File? {
        val fromTime = recordingStartTime
        return saveWavSegment(fromTime, toTime, customFilePath)
    }
}
