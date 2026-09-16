package com.plaud.template.hub.integrations

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.ceil
import kotlin.math.max

data class AudioChunk(
    val file: File,
    val startSeconds: Double,
    val durationSeconds: Double
)

class PreparedAudio internal constructor(
    val chunks: List<AudioChunk>,
    private val temporaryDirectory: File?
) : AutoCloseable {
    override fun close() {
        temporaryDirectory?.deleteRecursively()
    }
}

/**
 * Splits MP3 on MPEG frame boundaries without decoding or changing the original recording.
 * Each generated file therefore remains a valid MP3 stream and can be sent independently to ASR.
 */
object Mp3AudioChunker {
    fun prepare(
        source: File,
        cacheDirectory: File,
        knownDurationSeconds: Long,
        maxDurationSeconds: Long,
        maxBytes: Long
    ): PreparedAudio {
        require(source.isFile) { "本地录音文件不存在" }
        val needsSplit = knownDurationSeconds > maxDurationSeconds || source.length() > maxBytes
        if (!needsSplit) {
            return PreparedAudio(
                listOf(AudioChunk(source, 0.0, knownDurationSeconds.coerceAtLeast(0).toDouble())),
                null
            )
        }
        require(source.extension.equals("mp3", ignoreCase = true)) {
            "这条长录音是 ${source.extension.ifBlank { "未知" }.uppercase()} 格式；当前版本可自动分段 MP3，请先转成 MP3 后重试"
        }

        val stats = scan(source)
        require(stats.frameCount > 0 && stats.durationUs > 0) { "没有在录音中找到有效的 MP3 音频帧" }
        val durationChunkCount = ceil(stats.durationUs / 1_000_000.0 / maxDurationSeconds).toInt()
        val sizeChunkCount = ceil(stats.audioBytes.toDouble() / maxBytes).toInt()
        val chunkCount = max(1, max(durationChunkCount, sizeChunkCount))
        if (chunkCount == 1) {
            return PreparedAudio(
                listOf(AudioChunk(source, 0.0, stats.durationUs / 1_000_000.0)),
                null
            )
        }

        val root = File(cacheDirectory, "ai_chunks")
        root.mkdirs()
        val safeName = source.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val jobDirectory = File(root, "${safeName.ifBlank { "audio" }}-${System.currentTimeMillis()}")
        check(jobDirectory.mkdirs()) { "无法创建音频分段缓存" }

        return try {
            val chunks = writeChunks(source, stats, chunkCount, maxDurationSeconds, maxBytes, jobDirectory)
            PreparedAudio(chunks, jobDirectory)
        } catch (error: Throwable) {
            jobDirectory.deleteRecursively()
            throw error
        }
    }

    private data class ScanStats(
        val firstFrameOffset: Long,
        val frameCount: Long,
        val audioBytes: Long,
        val durationUs: Long
    )

    private data class FrameHeader(val length: Int, val durationUs: Long)

    private fun scan(source: File): ScanStats {
        RandomAccessFile(source, "r").use { input ->
            val first = findFirstFrame(input, id3v2End(input))
            require(first >= 0) { "没有在录音中找到有效的 MP3 音频帧" }
            var offset = first
            var count = 0L
            var bytes = 0L
            var duration = 0L
            while (offset + 4 <= input.length()) {
                val header = readHeader(input, offset) ?: break
                if (offset + header.length > input.length()) break
                count++
                bytes += header.length
                duration += header.durationUs
                offset += header.length
            }
            return ScanStats(first, count, bytes, duration)
        }
    }

    private fun writeChunks(
        source: File,
        stats: ScanStats,
        chunkCount: Int,
        hardMaxDurationSeconds: Long,
        hardMaxBytes: Long,
        outputDirectory: File
    ): List<AudioChunk> {
        val result = ArrayList<AudioChunk>(chunkCount)
        RandomAccessFile(source, "r").use { input ->
            var offset = stats.firstFrameOffset
            var processedFrames = 0L
            var processedBytes = 0L
            var processedDurationUs = 0L
            var part = 0
            var output: BufferedOutputStream? = null
            var chunkStartUs = 0L
            var chunkBytes = 0L
            var chunkDurationUs = 0L
            var targetBytes = 0L
            var targetDurationUs = 0L

            fun openChunk() {
                val chunksRemaining = chunkCount - part
                val bytesRemaining = stats.audioBytes - processedBytes
                val durationRemaining = stats.durationUs - processedDurationUs
                targetBytes = ceilDiv(bytesRemaining, chunksRemaining.toLong()).coerceAtMost(hardMaxBytes)
                targetDurationUs = ceilDiv(durationRemaining, chunksRemaining.toLong())
                    .coerceAtMost(hardMaxDurationSeconds * 1_000_000L)
                chunkStartUs = processedDurationUs
                chunkBytes = 0
                chunkDurationUs = 0
                output = BufferedOutputStream(
                    FileOutputStream(File(outputDirectory, "part-${(part + 1).toString().padStart(2, '0')}.mp3")),
                    64 * 1024
                )
            }

            fun closeChunk() {
                val current = output ?: return
                current.close()
                val file = File(outputDirectory, "part-${(part + 1).toString().padStart(2, '0')}.mp3")
                result += AudioChunk(file, chunkStartUs / 1_000_000.0, chunkDurationUs / 1_000_000.0)
                output = null
                part++
            }

            openChunk()
            while (processedFrames < stats.frameCount) {
                val header = readHeader(input, offset) ?: error("MP3 分段时在偏移 $offset 遇到损坏的音频帧")
                val framesRemaining = stats.frameCount - processedFrames
                val chunksRemainingAfterCurrent = chunkCount - part - 1
                val canCloseCurrent = chunkBytes > 0 && framesRemaining > chunksRemainingAfterCurrent
                val wouldExceedTarget = chunkDurationUs + header.durationUs > targetDurationUs ||
                    chunkBytes + header.length > targetBytes
                val wouldExceedHardLimit = chunkDurationUs + header.durationUs > hardMaxDurationSeconds * 1_000_000L ||
                    chunkBytes + header.length > hardMaxBytes
                if (canCloseCurrent && part < chunkCount - 1 && (wouldExceedTarget || wouldExceedHardLimit)) {
                    closeChunk()
                    openChunk()
                }

                val frame = ByteArray(header.length)
                input.seek(offset)
                input.readFully(frame)
                output!!.write(frame)
                offset += header.length
                processedFrames++
                processedBytes += header.length
                processedDurationUs += header.durationUs
                chunkBytes += header.length
                chunkDurationUs += header.durationUs
            }
            closeChunk()
        }
        check(result.size == chunkCount) { "音频分段不完整：预计 $chunkCount 段，实际 ${result.size} 段" }
        return result
    }

    private fun id3v2End(input: RandomAccessFile): Long {
        if (input.length() < 10) return 0
        input.seek(0)
        val header = ByteArray(10)
        input.readFully(header)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return 0
        val size = ((header[6].toInt() and 0x7f) shl 21) or
            ((header[7].toInt() and 0x7f) shl 14) or
            ((header[8].toInt() and 0x7f) shl 7) or
            (header[9].toInt() and 0x7f)
        val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
        return (10L + size + footer).coerceAtMost(input.length())
    }

    private fun findFirstFrame(input: RandomAccessFile, start: Long): Long {
        val last = (start + 1024 * 1024).coerceAtMost(input.length() - 4)
        var offset = start
        while (offset <= last) {
            val header = readHeader(input, offset)
            if (header != null && offset + header.length <= input.length()) {
                val next = offset + header.length
                if (next + 4 > input.length() || readHeader(input, next) != null) return offset
            }
            offset++
        }
        return -1
    }

    private fun readHeader(input: RandomAccessFile, offset: Long): FrameHeader? {
        if (offset < 0 || offset + 4 > input.length()) return null
        input.seek(offset)
        val value = input.readInt()
        if (value ushr 21 and 0x7ff != 0x7ff) return null
        val versionBits = value ushr 19 and 0x3
        if (versionBits == 1) return null
        val layerBits = value ushr 17 and 0x3
        if (layerBits == 0) return null
        val bitrateIndex = value ushr 12 and 0xf
        val sampleRateIndex = value ushr 10 and 0x3
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
        val padding = value ushr 9 and 0x1

        val mpeg1 = versionBits == 3
        val sampleRate = when (versionBits) {
            3 -> intArrayOf(44_100, 48_000, 32_000)[sampleRateIndex]
            2 -> intArrayOf(22_050, 24_000, 16_000)[sampleRateIndex]
            else -> intArrayOf(11_025, 12_000, 8_000)[sampleRateIndex]
        }
        val layer = when (layerBits) {
            3 -> 1
            2 -> 2
            else -> 3
        }
        val bitrate = bitrateKbps(mpeg1, layer, bitrateIndex) ?: return null
        val frameLength = when (layer) {
            1 -> ((12L * bitrate * 1000 / sampleRate) + padding).toInt() * 4
            2 -> (144L * bitrate * 1000 / sampleRate + padding).toInt()
            else -> (((if (mpeg1) 144L else 72L) * bitrate * 1000 / sampleRate) + padding).toInt()
        }
        val samplesPerFrame = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (mpeg1) 1152 else 576
        }
        if (frameLength < 24) return null
        return FrameHeader(frameLength, samplesPerFrame * 1_000_000L / sampleRate)
    }

    private fun bitrateKbps(mpeg1: Boolean, layer: Int, index: Int): Int? {
        val values = when {
            mpeg1 && layer == 1 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
            mpeg1 && layer == 2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
            mpeg1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            layer == 1 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
            else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        }
        return values.getOrNull(index)?.takeIf { it > 0 }
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor
}
