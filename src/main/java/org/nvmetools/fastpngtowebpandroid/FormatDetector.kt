package org.nvmetools.fastpngtowebpandroid

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

enum class TargetFormat(val extension: String, val mimeType: String) {
    WEBP(".webp", "image/webp"),
    JPEG(".jpg", "image/jpeg"),
}

enum class SourceFormat {
    JPEG,
    PNG,
    GIF,
    BMP,
    WEBP,
    AVIF,
    HEIF,
    UNKNOWN,
}

data class HeaderCheck(
    val sourceFormat: SourceFormat,
    val warning: String? = null,
)

object FormatDetector {
    private val supportedExtensions = setOf(
        ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".avif", ".heic", ".heif"
    )

    fun looksLikeImageName(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return supportedExtensions.any { lower.endsWith(it) }
    }

    fun detectFromUri(contentResolver: ContentResolver, uri: Uri): HeaderCheck {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                detectFromHeaderBytes(readPrefix(input, 32))
            } ?: HeaderCheck(SourceFormat.UNKNOWN, "Could not open source stream.")
        } catch (error: Exception) {
            HeaderCheck(SourceFormat.UNKNOWN, error.message ?: "Header read failed.")
        }
    }

    fun detectFromFile(file: File): HeaderCheck {
        return try {
            FileInputStream(file).use { input ->
                detectFromHeaderBytes(readPrefix(input, 32))
            }
        } catch (error: Exception) {
            HeaderCheck(SourceFormat.UNKNOWN, error.message ?: "Header read failed.")
        }
    }

    internal fun detectFromHeaderBytes(header: ByteArray): HeaderCheck {
        if (header.size < 12) {
            return HeaderCheck(SourceFormat.UNKNOWN, "Header is too short.")
        }

        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return HeaderCheck(SourceFormat.JPEG)
        }

        if (header.copyOfRange(0, 8).contentEquals(byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            ))
        ) {
            return HeaderCheck(SourceFormat.PNG)
        }

        if (header.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
            header.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
        ) {
            return HeaderCheck(SourceFormat.GIF, "GIF is skipped because this build only guarantees still-image output.")
        }

        if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
            return HeaderCheck(SourceFormat.BMP)
        }

        if (header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        ) {
            return HeaderCheck(SourceFormat.WEBP)
        }

        if (header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) {
            val brand = String(header.copyOfRange(8, 12)).lowercase()
            return when (brand) {
                "avif", "avis" -> HeaderCheck(SourceFormat.AVIF)
                "heic", "heix", "hevc", "hevx", "mif1" -> HeaderCheck(SourceFormat.HEIF)
                else -> HeaderCheck(SourceFormat.UNKNOWN, "Unsupported ISO BMFF image brand: $brand")
            }
        }

        return HeaderCheck(SourceFormat.UNKNOWN, "Unsupported image header.")
    }

    fun shouldBlockTranscode(sourceFormat: SourceFormat, targetFormat: TargetFormat): String? {
        return when {
            sourceFormat == SourceFormat.AVIF && targetFormat == TargetFormat.JPEG ->
                "Blocked AVIF to JPEG. Android's guidance calls AVIF a newer format that improves quality for the same file size compared to older formats like JPEG."
            sourceFormat == SourceFormat.AVIF && targetFormat == TargetFormat.WEBP ->
                "Blocked AVIF to WebP. This app treats AVIF as the higher-efficiency source and will not transcode it down by default."
            sourceFormat == SourceFormat.GIF ->
                "Blocked GIF. Animated still-image conversion is not guaranteed in this build."
            else -> null
        }
    }

    fun mayHaveExif(sourceFormat: SourceFormat): Boolean {
        return sourceFormat == SourceFormat.JPEG || sourceFormat == SourceFormat.HEIF || sourceFormat == SourceFormat.WEBP
    }

    private fun readPrefix(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        val bytesRead = input.read(buffer)
        return if (bytesRead <= 0) {
            ByteArray(0)
        } else {
            buffer.copyOf(bytesRead)
        }
    }
}
