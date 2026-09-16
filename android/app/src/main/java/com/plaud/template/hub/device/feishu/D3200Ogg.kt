package com.plaud.template.hub.device.feishu

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Wraps the D3200's decrypted fixed-size Opus frames in a standard Ogg/Opus stream. */
object D3200Ogg {
    private const val FRAME_SIZE = 160
    private const val SAMPLES_PER_FRAME = 960L
    private const val SAMPLE_RATE = 48_000
    private const val SERIAL = 0x41524b52

    fun mux(raw: ByteArray): ByteArray {
        require(raw.isNotEmpty()) { "录音数据为空" }
        val frames = raw.asList().chunked(FRAME_SIZE).map { values ->
            val bytes = values.toByteArray()
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte()) end--
            if (end == 0) byteArrayOf(0) else bytes.copyOf(end)
        }

        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(1)
            putShort(3840.toShort())
            putInt(SAMPLE_RATE)
            putShort(0)
            put(0)
        }.array()
        val vendor = "Recorder Hub D3200".toByteArray(Charsets.UTF_8)
        val tags = ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.US_ASCII))
            putInt(vendor.size)
            put(vendor)
            putInt(0)
        }.array()

        val output = ByteArrayOutputStream()
        output.write(page(listOf(head), 0, 0, 0x02))
        output.write(page(listOf(tags), 1, 0, 0))
        var granule = 0L
        var sequence = 2
        frames.chunked(50).forEachIndexed { index, batch ->
            granule += batch.size * SAMPLES_PER_FRAME
            val last = index == (frames.size - 1) / 50
            output.write(page(batch, sequence++, granule, if (last) 0x04 else 0))
        }
        return output.toByteArray()
    }

    private fun page(packets: List<ByteArray>, sequence: Int, granule: Long, flags: Int): ByteArray {
        val lacing = ArrayList<Int>()
        packets.forEach { packet ->
            var remaining = packet.size
            while (remaining >= 255) {
                lacing += 255
                remaining -= 255
            }
            lacing += remaining
        }
        val header = ByteBuffer.allocate(27 + lacing.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OggS".toByteArray(Charsets.US_ASCII))
            put(0)
            put(flags.toByte())
            putLong(granule)
            putInt(SERIAL)
            putInt(sequence)
            putInt(0)
            put(lacing.size.toByte())
            lacing.forEach { put(it.toByte()) }
        }.array()
        val page = ByteArrayOutputStream().apply {
            write(header)
            packets.forEach(::write)
        }.toByteArray()
        val checksum = oggCrc(page)
        ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(checksum)
        return page
    }

    /** Ogg uses the non-reflected 0x04C11DB7 polynomial rather than java.util.zip.CRC32. */
    private fun oggCrc(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEach { value ->
            crc = crc xor ((value.toInt() and 0xff) shl 24)
            repeat(8) {
                crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
            }
        }
        return crc
    }
}
