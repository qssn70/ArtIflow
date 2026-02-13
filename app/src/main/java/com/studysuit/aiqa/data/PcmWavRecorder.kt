package com.studysuit.aiqa.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class PcmWavRecorder(
  private val cacheDir: File
) {
  fun start(): Result<Session> {
    val minBuffer = AudioRecord.getMinBufferSize(
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBuffer <= 0) {
      return Result.failure(IllegalStateException("不支持当前录音参数"))
    }

    val bufferSize = minBuffer.coerceAtLeast(4096)
    val audioRecord = createAudioRecord(bufferSize).getOrElse { error ->
      return Result.failure(error)
    }

    val file = File(cacheDir, "voice-${System.currentTimeMillis()}-${UUID.randomUUID()}.wav")

    return runCatching {
      val output = FileOutputStream(file)
      output.write(ByteArray(WAV_HEADER_BYTES))

      val keepWriting = AtomicBoolean(true)
      val totalAudioBytes = AtomicLong(0L)
      val readErrorCode = AtomicInteger(0)

      val startedAtMs = System.currentTimeMillis()
      try {
        audioRecord.startRecording()
      } catch (securityException: SecurityException) {
        runCatching { output.close() }
        throw IllegalStateException("缺少麦克风权限", securityException)
      } catch (illegalStateException: IllegalStateException) {
        runCatching { output.close() }
        throw IllegalStateException("麦克风启动失败", illegalStateException)
      }

      if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        runCatching { output.close() }
        throw IllegalStateException("麦克风未进入录音状态")
      }

      val writer = thread(start = true, name = "pcm-wav-recorder") {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        try {
          while (keepWriting.get()) {
            val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (count > 0) {
              output.write(buffer, 0, count)
              totalAudioBytes.addAndGet(count.toLong())
            } else if (count < 0) {
              readErrorCode.compareAndSet(0, count)
              break
            }
          }
        } finally {
          runCatching { output.flush() }
          runCatching { output.close() }
        }
      }

      Session(
        audioRecord = audioRecord,
        file = file,
        writer = writer,
        keepWriting = keepWriting,
        totalAudioBytes = totalAudioBytes,
        readErrorCode = readErrorCode,
        startedAtMs = startedAtMs
      )
    }.onFailure {
      runCatching { audioRecord.stop() }
      audioRecord.release()
      runCatching { file.delete() }
    }
  }

  class Session internal constructor(
    private val audioRecord: AudioRecord,
    private val file: File,
    private val writer: Thread,
    private val keepWriting: AtomicBoolean,
    private val totalAudioBytes: AtomicLong,
    private val readErrorCode: AtomicInteger,
    private val startedAtMs: Long
  ) {
    @Volatile
    private var closed = false

    fun stop(discard: Boolean): Result<ByteArray> {
      if (closed) {
        return Result.failure(IllegalStateException("录音会话已结束"))
      }
      closed = true

      return runCatching {
        keepWriting.set(false)
        runCatching { audioRecord.stop() }
        runCatching { writer.join(1200) }

        var released = false
        if (writer.isAlive) {
          runCatching {
            audioRecord.release()
            released = true
          }
          runCatching { writer.join(1200) }
        }

        if (!released) {
          runCatching {
            audioRecord.release()
            released = true
          }
        }

        if (writer.isAlive) {
          runCatching { file.delete() }
          throw IllegalStateException("录音结束超时，请重试")
        }

        if (discard) {
          runCatching { file.delete() }
          throw IllegalStateException("录音已取消")
        }

        val payloadSize = totalAudioBytes.get()
        if (payloadSize <= 0L) {
          runCatching { file.delete() }
          val recordedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
          val errorHint = readErrorCode.get().takeIf { it != 0 }?.let { code ->
            describeReadError(code)
          }
          val message = when {
            recordedMs in 0..250 -> "录音时间过短，请多按住一会儿"
            !errorHint.isNullOrBlank() -> "录音读取失败：$errorHint"
            else -> "未采集到录音数据"
          }
          throw IllegalStateException(message)
        }

        writeWaveHeader(file = file, pcmPayloadSize = payloadSize)
        val bytes = file.readBytes()
        runCatching { file.delete() }
        bytes
      }.onFailure {
        runCatching { file.delete() }
      }
    }
  }

  companion object {
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_COUNT = 1
    private const val BITS_PER_SAMPLE = 16
    private const val WAV_HEADER_BYTES = 44
    private const val READ_CHUNK_BYTES = 1024

    private fun describeReadError(code: Int): String {
      return when (code) {
        AudioRecord.ERROR_BAD_VALUE -> "参数错误"
        AudioRecord.ERROR_INVALID_OPERATION -> "录音状态异常"
        AudioRecord.ERROR_DEAD_OBJECT -> "音频服务不可用"
        else -> "错误码 $code"
      }
    }

    private fun createAudioRecord(bufferSize: Int): Result<AudioRecord> {
      val sources = intArrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC
      )

      sources.forEach { source ->
        val record = try {
          AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
          )
        } catch (securityException: SecurityException) {
          return Result.failure(IllegalStateException("缺少麦克风权限", securityException))
        } catch (_: IllegalArgumentException) {
          null
        } catch (_: UnsupportedOperationException) {
          null
        }

        if (record != null) {
          if (record.state == AudioRecord.STATE_INITIALIZED) {
            return Result.success(record)
          }
          record.release()
        }
      }

      return Result.failure(IllegalStateException("麦克风初始化失败"))
    }

    private fun writeWaveHeader(file: File, pcmPayloadSize: Long) {
      val totalDataSize = pcmPayloadSize + 36
      val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8

      RandomAccessFile(file, "rw").use { raf ->
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.write(intToLittleEndian(totalDataSize.toInt()))
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.write(intToLittleEndian(16))
        raf.write(shortToLittleEndian(1))
        raf.write(shortToLittleEndian(CHANNEL_COUNT.toShort()))
        raf.write(intToLittleEndian(SAMPLE_RATE))
        raf.write(intToLittleEndian(byteRate))
        raf.write(shortToLittleEndian((CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort()))
        raf.write(shortToLittleEndian(BITS_PER_SAMPLE.toShort()))
        raf.writeBytes("data")
        raf.write(intToLittleEndian(pcmPayloadSize.toInt()))
      }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
      return byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
      )
    }

    private fun shortToLittleEndian(value: Short): ByteArray {
      return byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        (value.toInt() shr 8 and 0xFF).toByte()
      )
    }
  }
}
