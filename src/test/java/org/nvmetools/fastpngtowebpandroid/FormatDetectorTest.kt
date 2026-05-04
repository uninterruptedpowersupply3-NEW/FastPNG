package org.nvmetools.fastpngtowebpandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FormatDetectorTest {
    @Test
    fun detectsJpegHeader() {
        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertEquals(SourceFormat.JPEG, FormatDetector.detectFromHeaderBytes(header).sourceFormat)
    }

    @Test
    fun blocksAvifToJpeg() {
        val reason = FormatDetector.shouldBlockTranscode(SourceFormat.AVIF, TargetFormat.JPEG)
        assertNotNull(reason)
    }

    @Test
    fun buildsOutputName() {
        assertEquals("photo.webp", ImageTranscoder.buildTargetName("photo.png", TargetFormat.WEBP))
    }
}
