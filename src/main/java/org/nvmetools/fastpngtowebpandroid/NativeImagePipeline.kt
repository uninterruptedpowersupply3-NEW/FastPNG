package org.nvmetools.fastpngtowebpandroid

import android.os.Build

object NativeImagePipeline {
    private const val TARGET_FORMAT_JPEG = 0
    private const val TARGET_FORMAT_WEBP_LOSSY = 1

    const val RESULT_SUCCESS = 0
    const val RESULT_NOT_AVAILABLE = -1
    const val RESULT_SOURCE_OPEN_FAILED = -2
    const val RESULT_DECODER_CREATE_FAILED = -3
    const val RESULT_DECODER_CONFIG_FAILED = -4
    const val RESULT_DECODE_FAILED = -5
    const val RESULT_OUTPUT_OPEN_FAILED = -6
    const val RESULT_ENCODE_FAILED = -7
    const val RESULT_UNSUPPORTED = -8

    private val loaded: Boolean = runCatching {
        System.loadLibrary("fastpngnative")
        true
    }.getOrDefault(false)

    fun isAvailable(): Boolean = loaded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun shouldUseFor(sourceFormat: SourceFormat): Boolean {
        if (!isAvailable()) {
            return false
        }
        return when (sourceFormat) {
            SourceFormat.JPEG,
            SourceFormat.PNG,
            SourceFormat.BMP,
            SourceFormat.WEBP,
            SourceFormat.HEIF -> true
            else -> false
        }
    }

    fun transcodeFile(
        sourcePath: String,
        outputPath: String,
        sourceFormat: SourceFormat,
        targetFormat: TargetFormat,
        quality: Int,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
    ): Int {
        if (!shouldUseFor(sourceFormat)) {
            return RESULT_NOT_AVAILABLE
        }

        return nativeTranscodeFile(
            sourcePath = sourcePath,
            outputPath = outputPath,
            targetFormat = when (targetFormat) {
                TargetFormat.JPEG -> TARGET_FORMAT_JPEG
                TargetFormat.WEBP -> TARGET_FORMAT_WEBP_LOSSY
            },
            quality = quality,
            rotationDegrees = rotationDegrees,
            flipHorizontal = flipHorizontal,
        )
    }

    fun resultLabel(code: Int): String {
        return when (code) {
            RESULT_SUCCESS -> "success"
            RESULT_NOT_AVAILABLE -> "native path unavailable"
            RESULT_SOURCE_OPEN_FAILED -> "source open failed"
            RESULT_DECODER_CREATE_FAILED -> "decoder creation failed"
            RESULT_DECODER_CONFIG_FAILED -> "decoder configuration failed"
            RESULT_DECODE_FAILED -> "decode failed"
            RESULT_OUTPUT_OPEN_FAILED -> "output open failed"
            RESULT_ENCODE_FAILED -> "encode failed"
            RESULT_UNSUPPORTED -> "unsupported input"
            else -> "native error $code"
        }
    }

    private external fun nativeTranscodeFile(
        sourcePath: String,
        outputPath: String,
        targetFormat: Int,
        quality: Int,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
    ): Int
}
