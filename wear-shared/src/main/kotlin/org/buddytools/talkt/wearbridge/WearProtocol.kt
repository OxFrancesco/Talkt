package org.buddytools.talkt.wearbridge

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object WearProtocol {
    const val ROOT = "/talkt/wear/v1"
    const val ACCOUNT = "$ROOT/account"
    const val REQUEST = "$ROOT/request"
    const val RECORDING = "$ROOT/recording/"
    const val RECEIPT = "$ROOT/receipt/"
    const val MAX_AUDIO_BYTES = 16_000 * 2 * 60 + 44
    val recordingId = Regex("[0-9]{13}-[a-f0-9-]{36}")

    fun ownerToken(userId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }

    fun wavHeader(bytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(bytes + 36); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes)
    }.array()

    fun validateWav(file: File) {
        require(file.length() in 46..MAX_AUDIO_BYTES.toLong() && file.length() % 2 == 0L) { "Invalid recording size" }
        val header = file.inputStream().use { input -> ByteArray(44).also { require(input.read(it) == 44) } }
        require(header.contentEquals(wavHeader((file.length() - 44).toInt()))) { "Expected 16 kHz mono PCM audio" }
    }
}
