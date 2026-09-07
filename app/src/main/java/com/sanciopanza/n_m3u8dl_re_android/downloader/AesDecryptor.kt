package com.sanciopanza.n_m3u8dl_re_android.downloader

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decifratura HLS AES-128 (come --custom-hls-method AES_128 di N_m3u8DL-RE).
 * SAMPLE-AES/CENC reali richiedono MediaDrm: qui best-effort con fallback chiaro.
 */
object AesDecryptor {

    fun decryptAes128Cbc(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        require(iv.size == 16) { "IV must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        require(clean.length % 2 == 0) { "Invalid hex string" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun defaultIvForSegment(index: Int): ByteArray {
        // RFC HLS: se IV assente, usa sequence number come IV a 16 byte big-endian.
        val iv = ByteArray(16)
        iv[12] = ((index shr 24) and 0xFF).toByte()
        iv[13] = ((index shr 16) and 0xFF).toByte()
        iv[14] = ((index shr 8) and 0xFF).toByte()
        iv[15] = (index and 0xFF).toByte()
        return iv
    }
}
