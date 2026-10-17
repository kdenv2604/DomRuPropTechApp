package ru.domru.technics.data

import org.junit.Assert.*
import org.junit.Test

class CameraStreamFormatTest {
    @Test fun preservesSignedServiceUrlWithoutRewritingCodecOrProtocol() {
        val url = "https://camera.example.test/live?token=a%2Fb&codec=h264"
        assertEquals(url, CameraStreamFormat.validUrl(url))
        assertEquals("video/x-flv", CameraStreamFormat.mimeType(url, "video/x-flv"))
        assertNull(CameraStreamFormat.mimeType(url, "h264"))
        assertEquals("http://camera.example.test/live", CameraStreamFormat.validUrl("http://camera.example.test/live"))
    }
    @Test fun supportsServerHlsAndMp4AndRejectsInvalidUrl() {
        assertEquals("application/x-mpegURL", CameraStreamFormat.mimeType("https://camera.example.test/live", "application/vnd.apple.mpegurl"))
        assertEquals("application/x-mpegURL", CameraStreamFormat.mimeType("https://camera.example.test/live.m3u8?token=x", ""))
        assertEquals("video/mp4", CameraStreamFormat.mimeType("https://camera.example.test/live", "video/mp4; codecs=avc1"))
        assertNull(CameraStreamFormat.validUrl("https://"))
        assertNull(CameraStreamFormat.validUrl("file:///private.mp4"))
        assertNull(CameraStreamFormat.validUrl("javascript:alert(1)"))
    }
}
