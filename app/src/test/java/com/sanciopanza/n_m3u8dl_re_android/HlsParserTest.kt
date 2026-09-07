package com.sanciopanza.n_m3u8dl_re_android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sanciopanza.n_m3u8dl_re_android.downloader.HlsParser

class HlsParserTest {

    @Test
    fun masterPlaylist_selectsBestVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            hi/index.m3u8
        """.trimIndent()
        val pl = HlsParser.parse(master, "https://example.com/master.m3u8")
        assertTrue(pl.isMaster)
        assertEquals(2, pl.variants.size)
        val best = HlsParser.selectBestVariant(pl.variants)
        assertEquals(5000000L, best!!.bandwidth)
        assertEquals("https://example.com/hi/index.m3u8", best.uri)
    }

    @Test
    fun mediaPlaylist_withAesKey_parsesSegments() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=AES-128,URI="key.key",IV=0x00000000000000000000000000000001
            #EXTINF:9.0,
            seg0.ts
            #EXTINF:9.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val pl = HlsParser.parse(media, "https://cdn.example.com/hls/index.m3u8")
        assertFalse(pl.isMaster)
        assertFalse(pl.isLive)
        assertEquals(2, pl.segments.size)
        assertEquals("https://cdn.example.com/hls/seg0.ts", pl.segments[0].uri)
        assertEquals("https://cdn.example.com/hls/key.key", pl.segments[0].keyUri)
    }

    @Test
    fun liveWithoutEndList_isLive() {
        val live = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            a.ts
        """.trimIndent()
        val pl = HlsParser.parse(live, "https://example.com/live.m3u8")
        assertTrue(pl.isLive)
    }

    @Test
    fun masterWithMediaGroups_parsesAudioAndSubtitles() {
        // Struttura tipica dei flussi vixcloud: video + audio separato + subs.
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="it",NAME="Italiano",DEFAULT=YES,URI="audio_it.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="en",NAME="English",URI="audio_en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="it",NAME="Italiano",DEFAULT=YES,URI="subs_it.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio",SUBTITLES="subs"
            video.m3u8
        """.trimIndent()
        val pl = HlsParser.parse(master, "https://vixcloud.example/playlist/x?token=abc")
        assertTrue(pl.isMaster)
        assertEquals(2, pl.audioTracks.size)
        assertEquals(1, pl.subtitleTracks.size)
        val audio = HlsParser.selectAudioTrack(pl.audioTracks, "audio")
        assertEquals("it", audio!!.language)
        assertEquals("https://vixcloud.example/playlist/audio_it.m3u8", audio.uri)
        val subs = HlsParser.selectSubtitleTrack(pl.subtitleTracks, "subs", "it")
        assertEquals("https://vixcloud.example/playlist/subs_it.m3u8", subs!!.uri)
    }
}
