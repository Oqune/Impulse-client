package com.example.impulse.util

/** Hex-encode a byte array using proper unsigned formatting. */
fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Decode a hex string to a byte array. Handles odd-length strings by padding. */
fun hexToBytes(hex: String): ByteArray {
    val padded = if (hex.length % 2 != 0) "0$hex" else hex
    val bytes = ByteArray(padded.length / 2)
    for (i in bytes.indices) {
        bytes[i] = Integer.parseInt(padded.substring(i * 2, i * 2 + 2), 16).toByte()
    }
    return bytes
}
