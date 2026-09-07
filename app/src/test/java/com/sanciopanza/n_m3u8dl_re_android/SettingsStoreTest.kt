package com.sanciopanza.n_m3u8dl_re_android

import org.junit.Assert.assertTrue
import org.junit.Test
import com.sanciopanza.n_m3u8dl_re_android.downloader.SettingsStore

class SettingsStoreTest {

    @Test
    fun parseHeaders_supportsSemicolonAndNewlines() {
        val m = SettingsStore.parseHeaders("Cookie: abc=1; User-Agent: Test\nX-A: b")
        assertTrue(m["Cookie"] == "abc=1")
        assertTrue(m["User-Agent"] == "Test")
        assertTrue(m["X-A"] == "b")
    }
}
