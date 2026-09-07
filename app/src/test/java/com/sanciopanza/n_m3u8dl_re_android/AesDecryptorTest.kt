package com.sanciopanza.n_m3u8dl_re_android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.sanciopanza.n_m3u8dl_re_android.downloader.AesDecryptor

class AesDecryptorTest {

    @Test
    fun roundTrip_aes128Cbc() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (15 - it).toByte() }
        val plain = "hello-chromebook-plus".toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val enc = cipher.doFinal(plain)
        val dec = AesDecryptor.decryptAes128Cbc(enc, key, iv)
        assertArrayEquals(plain, dec)
    }

    @Test
    fun defaultIv_usesSegmentIndex() {
        val iv = AesDecryptor.defaultIvForSegment(1)
        assertEquals(16, iv.size)
        assertEquals(1.toByte(), iv[15])
    }
}
