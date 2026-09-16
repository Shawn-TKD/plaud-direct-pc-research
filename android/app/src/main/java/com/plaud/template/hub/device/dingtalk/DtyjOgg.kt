package com.plaud.template.hub.device.dingtalk

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts A1's fixed-frame DTYJ/BABA Opus container into a standard playable Ogg/Opus file. */
object DtyjOgg {
    /**
     * Streaming converter used for device downloads. A long A1 recording can be hundreds of
     * megabytes, so neither the DTYJ container nor the generated Ogg file is retained in memory.
     */
    fun convert(container: File, destination: File, serial: Int): File {
        RandomAccessFile(container, "r").use { input ->
            require(input.length() >= 32) { "DTYJ 文件过短" }
            require(input.readAscii(0, 4) == "BABA" && input.readAscii(8, 4) == "DTYJ") {
                "不是 DTYJ 文件"
            }
            require(input.readLeInt(4).toLong() == input.length() - 8) { "DTYJ 文件大小异常" }

            val fmt = input.findAscii("fmt ", 12)
            val data = input.findAscii("data", fmt + 4)
            require(fmt >= 0 && data >= 0) { "DTYJ 缺少音频块" }
            val sampleRate = input.readLeInt(fmt + 12)
            val recordSize = input.readLeShort(fmt + 24)
            val dataSize = input.readLeInt(data + 4)
            val start = data + 12L
            require(recordSize > 4 && dataSize >= 0 && dataSize % recordSize == 0)
            require(start + dataSize <= input.length()) { "DTYJ 音频块不完整" }

            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                writeHeaders(output, sampleRate, serial, "recorder-hub/dtyj")
                val packet = ByteArray(recordSize - 4)
                val packetCount = dataSize / recordSize
                input.seek(start)
                repeat(packetCount) { index ->
                    input.skipBytes(4) // opaque per-record flags
                    input.readFully(packet)
                    output.write(
                        page(
                            packet,
                            if (index == packetCount - 1) 4 else 0,
                            (index + 1L) * 960,
                            serial,
                            index + 2
                        )
                    )
                }
                output.fd.sync()
            }
        }
        return destination
    }

    fun convert(container: ByteArray, serial: Int): ByteArray {
        require(String(container, 0, 4, Charsets.US_ASCII) == "BABA" && String(container, 8, 4, Charsets.US_ASCII) == "DTYJ")
        val le = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN)
        require(le.getInt(4) == container.size - 8) { "DTYJ 文件大小异常" }
        val fmt = container.indexOf("fmt ".toByteArray(), 12)
        val data = container.indexOf("data".toByteArray(), fmt + 4)
        require(fmt >= 0 && data >= 0) { "DTYJ 缺少音频块" }
        val sampleRate = le.getInt(fmt + 12)
        val recordSize = le.getShort(fmt + 24).toInt() and 0xffff
        val dataSize = le.getInt(data + 4)
        val start = data + 12
        require(recordSize > 4 && dataSize % recordSize == 0 && start + dataSize <= container.size)
        val packets = (0 until dataSize step recordSize).map { offset ->
            val p = start + offset
            // The four-byte little-endian record prefix is an opaque flag field. Real samples use
            // 0x20, 0xA0, 0xC0 and 0x1A0, so the upper three bytes must not be assumed to be zero.
            container.copyOfRange(p + 4, p + recordSize)
        }
        return wrapOpusPackets(packets, sampleRate, serial, "recorder-hub/dtyj")
    }

    /** Wrap the A1's verified 84-byte, 20 ms live Opus packets in a standard Ogg stream. */
    fun wrapOpusPackets(
        packets: List<ByteArray>,
        sampleRate: Int,
        serial: Int,
        vendorName: String = "recorder-hub/a1-live"
    ): ByteArray {
        require(packets.isNotEmpty()) { "无法封装空的 A1 音频流" }
        require(sampleRate in 8_000..192_000) { "A1 音频采样率异常" }
        val out = ByteArrayOutputStream()
        writeHeaders(out, sampleRate, serial, vendorName)
        packets.forEachIndexed { index, packet -> out.write(page(packet, if (index == packets.lastIndex) 4 else 0, (index + 1L) * 960, serial, index + 2)) }
        return out.toByteArray()
    }

    private fun writeHeaders(
        out: java.io.OutputStream,
        sampleRate: Int,
        serial: Int,
        vendorName: String
    ) {
        require(sampleRate in 8_000..192_000) { "A1 音频采样率异常" }
        val head = ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray()); write(byteArrayOf(1, 1)); write(leShort(0)); write(leInt(sampleRate)); write(leShort(0)); write(0)
        }.toByteArray()
        val vendor = vendorName.toByteArray()
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray()); write(leInt(vendor.size)); write(vendor); write(leInt(0))
        }.toByteArray()
        out.write(page(head, 2, 0, serial, 0))
        out.write(page(tags, 0, 0, serial, 1))
    }

    private fun page(packet: ByteArray, type: Int, granule: Long, serial: Int, sequence: Int): ByteArray {
        val segments = mutableListOf<Int>(); var remaining = packet.size
        while (remaining >= 255) { segments += 255; remaining -= 255 }; segments += remaining
        val out = ByteBuffer.allocate(27 + segments.size + packet.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(granule).putInt(serial).putInt(sequence).putInt(0)
        out.put(segments.size.toByte()); segments.forEach { out.put(it.toByte()) }; out.put(packet)
        val bytes = out.array(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc(bytes)); return bytes
    }
    private fun crc(data: ByteArray): Int {
        var value = 0
        data.forEach { byte -> value = value xor ((byte.toInt() and 0xff) shl 24); repeat(8) { value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04C11DB7 else value shl 1 } }
        return value
    }
    private fun leInt(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun leShort(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
        for (i in from..size - needle.size) if (needle.indices.all { this[i + it] == needle[it] }) return i
        return -1
    }

    private fun RandomAccessFile.readAscii(offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        seek(offset)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readLeInt(offset: Long): Int {
        val bytes = ByteArray(4)
        seek(offset)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun RandomAccessFile.readLeShort(offset: Long): Int {
        val bytes = ByteArray(2)
        seek(offset)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }

    private fun RandomAccessFile.findAscii(value: String, from: Long): Long {
        val needle = value.toByteArray(Charsets.US_ASCII)
        val limit = minOf(length() - needle.size, from + 1024 * 1024)
        var offset = from
        while (offset <= limit) {
            if (readAscii(offset, needle.size) == value) return offset
            offset++
        }
        return -1
    }
}
