package com.paradisemc.nexus.plugin.todo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun pcm16Le(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val safeRate = sampleRate.coerceAtLeast(8000)
        val safeChannels = channels.coerceIn(1, 2)
        val bitsPerSample = 16
        val byteRate = safeRate * safeChannels * bitsPerSample / 8
        val blockAlign = safeChannels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun text(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        fun le16(value: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        text("RIFF"); le32(36 + pcm.size); text("WAVE")
        text("fmt "); le32(16); le16(1); le16(safeChannels); le32(safeRate); le32(byteRate); le16(blockAlign); le16(bitsPerSample)
        text("data"); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
