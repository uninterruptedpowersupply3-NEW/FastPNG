package org.nvmetools.fastpngtowebpandroid

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class JobConfig(
    val targetFormat: TargetFormat,
    val quality: Int,
    val deleteOriginals: Boolean,
)

enum class ResultKind {
    CONVERTED,
    SKIPPED,
    BLOCKED,
    FAILED,
}

data class TranscodeResult(
    val kind: ResultKind,
    val message: String,
    val bytesSaved: Long = 0,
)

data class BatchTranscodeResult(
    val converted: Long,
    val skipped: Long,
    val blocked: Long,
    val failed: Long,
    val bytesSaved: Long,
    val lastMessage: String,
    val logLines: List<String>,
) {
    companion object {
        fun fromResults(results: List<TranscodeResult>): BatchTranscodeResult {
            var converted = 0L
            var skipped = 0L
            var blocked = 0L
            var failed = 0L
            var savedBytes = 0L
            val logLines = ArrayList<String>(results.size.coerceAtMost(6))
            var lastMessage = "Batch finished."

            for (result in results) {
                when (result.kind) {
                    ResultKind.CONVERTED -> converted += 1
                    ResultKind.SKIPPED -> skipped += 1
                    ResultKind.BLOCKED -> blocked += 1
                    ResultKind.FAILED -> failed += 1
                }
                savedBytes += result.bytesSaved
                lastMessage = result.message
                if (logLines.size < 6 || result.kind == ResultKind.FAILED || result.kind == ResultKind.BLOCKED) {
                    logLines.add(result.message)
                }
            }

            return BatchTranscodeResult(
                converted = converted,
                skipped = skipped,
                blocked = blocked,
                failed = failed,
                bytesSaved = savedBytes,
                lastMessage = lastMessage,
                logLines = logLines.takeLast(12),
            )
        }

        fun fromResult(result: TranscodeResult): BatchTranscodeResult = fromResults(listOf(result))
    }
}

private data class ExifTransform(
    val rotationDegrees: Int = 0,
    val flipped: Boolean = false,
)

object ImageTranscoder {
    private const val ENCODE_BUFFER_BYTES = 256 * 1024

    fun processFastBatch(
        sourceFiles: List<File>,
        config: JobConfig,
    ): BatchTranscodeResult {
        val results = ArrayList<TranscodeResult>(sourceFiles.size)
        for (sourceFile in sourceFiles) {
            results.add(processFastFile(sourceFile, config))
        }
        return BatchTranscodeResult.fromResults(results)
    }

    fun processFastFile(
        sourceFile: File,
        config: JobConfig,
    ): TranscodeResult {
        val headerCheck = FormatDetector.detectFromFile(sourceFile)
        if (headerCheck.sourceFormat == SourceFormat.UNKNOWN) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip ${sourceFile.name}: ${headerCheck.warning ?: "unknown source format"}")
        }

        headerCheck.warning?.let {
            return TranscodeResult(ResultKind.BLOCKED, "Block ${sourceFile.name}: $it")
        }

        if (headerCheck.sourceFormat == SourceFormat.WEBP && config.targetFormat == TargetFormat.WEBP) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip ${sourceFile.name}: already WebP.")
        }
        if (headerCheck.sourceFormat == SourceFormat.JPEG && config.targetFormat == TargetFormat.JPEG) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip ${sourceFile.name}: already JPEG.")
        }

        FormatDetector.shouldBlockTranscode(headerCheck.sourceFormat, config.targetFormat)?.let { reason ->
            return TranscodeResult(ResultKind.BLOCKED, "Block ${sourceFile.name}: $reason")
        }

        val parentDirectory = sourceFile.parentFile
            ?: return TranscodeResult(ResultKind.FAILED, "Fail ${sourceFile.name}: missing parent directory.")
        val outputFile = File(parentDirectory, buildTargetName(sourceFile.name, config.targetFormat))
        if (outputFile.exists()) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip ${sourceFile.name}: ${outputFile.name} already exists.")
        }

        if (NativeImagePipeline.shouldUseFor(headerCheck.sourceFormat)) {
            val exifTransform = readExifTransform(sourceFile, headerCheck.sourceFormat)
            val nativeCode = NativeImagePipeline.transcodeFile(
                sourcePath = sourceFile.absolutePath,
                outputPath = outputFile.absolutePath,
                sourceFormat = headerCheck.sourceFormat,
                targetFormat = config.targetFormat,
                quality = config.quality,
                rotationDegrees = exifTransform.rotationDegrees,
                flipHorizontal = exifTransform.flipped,
            )
            if (nativeCode == NativeImagePipeline.RESULT_SUCCESS) {
                return finalizeFastFileResult(
                    sourceFile = sourceFile,
                    outputFile = outputFile,
                    config = config,
                    nativePipeline = true,
                )
            }
            outputFile.delete()
        }

        val decoded = try {
            decodeBitmap(sourceFile, headerCheck.sourceFormat)
        } catch (error: OutOfMemoryError) {
            return TranscodeResult(ResultKind.FAILED, "Fail ${sourceFile.name}: decode exceeded device memory.")
        } catch (error: Exception) {
            return TranscodeResult(ResultKind.FAILED, "Fail ${sourceFile.name}: ${error.message}")
        }

        val oriented = applyOrientation(sourceFile, headerCheck.sourceFormat, decoded)
        if (oriented !== decoded) {
            decoded.recycle()
        }

        val prepared = if (config.targetFormat == TargetFormat.JPEG && oriented.hasAlpha()) {
            flattenAlpha(oriented)
        } else {
            oriented
        }
        if (prepared !== oriented) {
            oriented.recycle()
        }

        val encoded = try {
            BufferedOutputStream(FileOutputStream(outputFile), ENCODE_BUFFER_BYTES).use { output ->
                prepared.compress(resolveCompressFormat(config.targetFormat), config.quality, output)
            }
        } catch (_: Exception) {
            false
        } finally {
            prepared.recycle()
        }

        if (!encoded) {
            outputFile.delete()
            return TranscodeResult(ResultKind.FAILED, "Fail ${sourceFile.name}: encode returned false.")
        }
        return finalizeFastFileResult(
            sourceFile = sourceFile,
            outputFile = outputFile,
            config = config,
            nativePipeline = false,
        )
    }

    fun processTreeItem(
        context: Context,
        item: TreeFileItem,
        config: JobConfig,
    ): TranscodeResult {
        val outputName = buildTargetName(item.displayName, config.targetFormat)
        if (!item.directoryState.siblingNames.add(outputName)) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip ${item.displayName}: $outputName already exists.")
        }

        val outputUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                item.directoryState.parentDocumentUri,
                config.targetFormat.mimeType,
                outputName,
            )
        } catch (error: Exception) {
            item.directoryState.siblingNames.remove(outputName)
            return TranscodeResult(ResultKind.FAILED, "Create failed for $outputName: ${error.message}")
        }

        if (outputUri == null) {
            item.directoryState.siblingNames.remove(outputName)
            return TranscodeResult(ResultKind.FAILED, "Create failed for $outputName: provider returned null.")
        }

        val result = transcodeUri(
            context = context,
            sourceUri = item.documentUri,
            sourceDisplayName = item.displayName,
            outputUri = outputUri,
            config = config,
            sourceSizeBytes = item.sourceSizeBytes,
            allowDeleteSource = config.deleteOriginals,
        )

        if (result.kind != ResultKind.CONVERTED) {
            safeDelete(context.contentResolver, outputUri)
            item.directoryState.siblingNames.remove(outputName)
        }

        return result
    }

    fun processTreeBatch(
        context: Context,
        items: List<TreeFileItem>,
        config: JobConfig,
    ): BatchTranscodeResult {
        val results = ArrayList<TranscodeResult>(items.size)
        for (item in items) {
            results.add(processTreeItem(context, item, config))
        }
        return BatchTranscodeResult.fromResults(results)
    }

    fun processSingleItem(
        context: Context,
        sourceUri: Uri,
        sourceDisplayName: String,
        outputUri: Uri,
        config: JobConfig,
    ): TranscodeResult {
        return transcodeUri(
            context = context,
            sourceUri = sourceUri,
            sourceDisplayName = sourceDisplayName,
            outputUri = outputUri,
            config = config.copy(deleteOriginals = false),
            sourceSizeBytes = querySize(context.contentResolver, sourceUri),
            allowDeleteSource = false,
        )
    }

    private fun transcodeUri(
        context: Context,
        sourceUri: Uri,
        sourceDisplayName: String,
        outputUri: Uri,
        config: JobConfig,
        sourceSizeBytes: Long,
        allowDeleteSource: Boolean,
    ): TranscodeResult {
        val headerCheck = FormatDetector.detectFromUri(context.contentResolver, sourceUri)
        if (headerCheck.sourceFormat == SourceFormat.UNKNOWN) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip $sourceDisplayName: ${headerCheck.warning ?: "unknown source format"}")
        }

        headerCheck.warning?.let {
            return TranscodeResult(ResultKind.BLOCKED, "Block $sourceDisplayName: $it")
        }

        if (headerCheck.sourceFormat == SourceFormat.WEBP && config.targetFormat == TargetFormat.WEBP) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip $sourceDisplayName: already WebP.")
        }
        if (headerCheck.sourceFormat == SourceFormat.JPEG && config.targetFormat == TargetFormat.JPEG) {
            return TranscodeResult(ResultKind.SKIPPED, "Skip $sourceDisplayName: already JPEG.")
        }

        FormatDetector.shouldBlockTranscode(headerCheck.sourceFormat, config.targetFormat)?.let { reason ->
            return TranscodeResult(ResultKind.BLOCKED, "Block $sourceDisplayName: $reason")
        }

        val decoded = try {
            decodeBitmap(context, sourceUri, headerCheck.sourceFormat)
        } catch (error: OutOfMemoryError) {
            return TranscodeResult(ResultKind.FAILED, "Fail $sourceDisplayName: decode exceeded device memory.")
        } catch (error: Exception) {
            return TranscodeResult(ResultKind.FAILED, "Fail $sourceDisplayName: ${error.message}")
        }

        val oriented = applyOrientation(context, sourceUri, headerCheck.sourceFormat, decoded)
        if (oriented !== decoded) {
            decoded.recycle()
        }

        val prepared = if (config.targetFormat == TargetFormat.JPEG && oriented.hasAlpha()) {
            flattenAlpha(oriented)
        } else {
            oriented
        }
        if (prepared !== oriented) {
            oriented.recycle()
        }

        val encoded = try {
            context.contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                BufferedOutputStream(output, ENCODE_BUFFER_BYTES).use { buffered ->
                    prepared.compress(resolveCompressFormat(config.targetFormat), config.quality, buffered)
                }
            } ?: false
        } catch (_: Exception) {
            false
        } finally {
            prepared.recycle()
        }

        if (!encoded) {
            return TranscodeResult(ResultKind.FAILED, "Fail $sourceDisplayName: encode returned false.")
        }

        val outputSize = querySize(context.contentResolver, outputUri)
        if (sourceSizeBytes > 0 && outputSize > 0 && outputSize >= sourceSizeBytes) {
            return TranscodeResult(
                ResultKind.SKIPPED,
                "Skip $sourceDisplayName: output was not smaller than source.",
            )
        }

        val deletedSource = if (allowDeleteSource) {
            safeDelete(context.contentResolver, sourceUri)
        } else {
            false
        }

        val bytesSaved = when {
            sourceSizeBytes > 0 && outputSize > 0 -> sourceSizeBytes - outputSize
            else -> 0L
        }

        val deleteSuffix = when {
            allowDeleteSource && deletedSource -> " original deleted."
            allowDeleteSource -> " converted, but original delete failed."
            else -> " source kept."
        }

        return TranscodeResult(
            ResultKind.CONVERTED,
            "Converted $sourceDisplayName to ${config.targetFormat.name}.$deleteSuffix",
            bytesSaved = bytesSaved.coerceAtLeast(0L),
        )
    }

    internal fun buildTargetName(sourceDisplayName: String, targetFormat: TargetFormat): String {
        val dot = sourceDisplayName.lastIndexOf('.')
        val baseName = if (dot > 0) sourceDisplayName.substring(0, dot) else sourceDisplayName
        return baseName + targetFormat.extension
    }

    private fun decodeBitmap(
        context: Context,
        sourceUri: Uri,
        sourceFormat: SourceFormat,
    ): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, sourceUri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        } else {
            context.contentResolver.openInputStream(sourceUri).use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptionsFor(sourceFormat))
                    ?: throw IOException("BitmapFactory returned null.")
            }
        }
    }

    private fun decodeBitmap(
        sourceFile: File,
        sourceFormat: SourceFormat,
    ): Bitmap {
        return BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptionsFor(sourceFormat))
            ?: throw IOException("BitmapFactory returned null.")
    }

    private fun applyOrientation(
        context: Context,
        sourceUri: Uri,
        sourceFormat: SourceFormat,
        bitmap: Bitmap,
    ): Bitmap {
        if (!FormatDetector.mayHaveExif(sourceFormat)) {
            return bitmap
        }
        val orientation = try {
            context.contentResolver.openInputStream(sourceUri)?.use { ExifInterface(it) }
        } catch (_: Exception) {
            null
        } ?: return bitmap

        return applyOrientationTransform(orientation, bitmap)
    }

    private fun applyOrientation(
        sourceFile: File,
        sourceFormat: SourceFormat,
        bitmap: Bitmap,
    ): Bitmap {
        val exifTransform = readExifTransform(sourceFile, sourceFormat)
        if (exifTransform.rotationDegrees == 0 && !exifTransform.flipped) {
            return bitmap
        }
        return applyOrientationTransform(
            rotation = exifTransform.rotationDegrees,
            flipped = exifTransform.flipped,
            bitmap = bitmap,
        )
    }

    private fun applyOrientationTransform(
        orientation: ExifInterface,
        bitmap: Bitmap,
    ): Bitmap {
        return applyOrientationTransform(
            rotation = orientation.rotationDegrees,
            flipped = orientation.isFlipped,
            bitmap = bitmap,
        )
    }

    private fun applyOrientationTransform(
        rotation: Int,
        flipped: Boolean,
        bitmap: Bitmap,
    ): Bitmap {
        if (rotation == 0 && !flipped) {
            return bitmap
        }

        val matrix = Matrix().apply {
            if (flipped) {
                postScale(-1f, 1f)
            }
            if (rotation != 0) {
                postRotate(rotation.toFloat())
            }
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readExifTransform(
        sourceFile: File,
        sourceFormat: SourceFormat,
    ): ExifTransform {
        if (!FormatDetector.mayHaveExif(sourceFormat)) {
            return ExifTransform()
        }

        val exif = try {
            ExifInterface(sourceFile.absolutePath)
        } catch (_: Exception) {
            null
        } ?: return ExifTransform()

        return ExifTransform(
            rotationDegrees = exif.rotationDegrees,
            flipped = exif.isFlipped,
        )
    }

    private fun flattenAlpha(bitmap: Bitmap): Bitmap {
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return flattened
    }

    private fun decodeOptionsFor(sourceFormat: SourceFormat): BitmapFactory.Options {
        return BitmapFactory.Options().apply {
            inPreferredConfig = if (sourceFormat == SourceFormat.JPEG) {
                Bitmap.Config.RGB_565
            } else {
                Bitmap.Config.ARGB_8888
            }
        }
    }

    private fun finalizeFastFileResult(
        sourceFile: File,
        outputFile: File,
        config: JobConfig,
        nativePipeline: Boolean,
    ): TranscodeResult {
        val sourceSize = sourceFile.length()
        val outputSize = outputFile.length()
        if (sourceSize > 0 && outputSize > 0 && outputSize >= sourceSize) {
            outputFile.delete()
            return TranscodeResult(ResultKind.SKIPPED, "Skip ${sourceFile.name}: output was not smaller than source.")
        }

        val deletedSource = if (config.deleteOriginals) sourceFile.delete() else false
        val deleteSuffix = when {
            config.deleteOriginals && deletedSource -> " original deleted."
            config.deleteOriginals -> " converted, but original delete failed."
            else -> " source kept."
        }
        val pipelineSuffix = if (nativePipeline) " native path." else ""

        return TranscodeResult(
            ResultKind.CONVERTED,
            "Converted ${sourceFile.name} to ${config.targetFormat.name}.$deleteSuffix$pipelineSuffix",
            bytesSaved = (sourceSize - outputSize).coerceAtLeast(0L),
        )
    }

    private fun resolveCompressFormat(targetFormat: TargetFormat): Bitmap.CompressFormat {
        return when (targetFormat) {
            TargetFormat.JPEG -> Bitmap.CompressFormat.JPEG
            TargetFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
        }
    }

    private fun safeDelete(contentResolver: ContentResolver, uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
            try {
                contentResolver.delete(uri, null, null) > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun querySize(contentResolver: ContentResolver, uri: Uri): Long {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        return cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
