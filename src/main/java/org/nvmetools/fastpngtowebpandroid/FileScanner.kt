package org.nvmetools.fastpngtowebpandroid

import java.io.File
import java.util.ArrayDeque
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object FileScanner {
    fun countImages(root: File): Long {
        var count = 0L
        walkImageFiles(root) {
            count += 1
        }
        return count
    }

    fun streamImages(root: File): Sequence<File> = sequence {
        walkImageFiles(root) { imageFile ->
            yield(imageFile)
        }
    }

    fun streamImageBatches(root: File, batchSize: Int): Sequence<List<File>> = sequence {
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val batch = ArrayList<File>(safeBatchSize)
        for (file in streamImages(root)) {
            batch.add(file)
            if (batch.size >= safeBatchSize) {
                yield(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            yield(batch.toList())
        }
    }

    private inline fun walkImageFiles(root: File, crossinline onImage: (File) -> Unit) {
        val queue = ArrayDeque<Path>()
        queue.add(root.toPath())

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            try {
                Files.newDirectoryStream(current).use { stream ->
                    for (entry in stream) {
                        try {
                            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                                queue.add(entry)
                            } else if (
                                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) &&
                                FormatDetector.looksLikeImageName(entry.fileName.toString())
                            ) {
                                onImage(entry.toFile())
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
