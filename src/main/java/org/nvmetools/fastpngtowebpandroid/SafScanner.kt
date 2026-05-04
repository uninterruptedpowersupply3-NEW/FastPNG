package org.nvmetools.fastpngtowebpandroid

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class DirectoryState(
    val parentDocumentUri: Uri,
    val siblingNames: MutableSet<String>,
)

data class TreeFileItem(
    val documentUri: Uri,
    val displayName: String,
    val sourceSizeBytes: Long,
    val directoryState: DirectoryState,
)

object SafScanner {
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun streamTree(contentResolver: ContentResolver, treeUri: Uri): Sequence<TreeFileItem> = sequence {
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val pendingDirectories = ArrayDeque<Uri>()
        pendingDirectories.add(rootDocumentUri)

        while (pendingDirectories.isNotEmpty()) {
            val directoryUri = pendingDirectories.removeFirst()
            val directoryDocumentId = DocumentsContract.getDocumentId(directoryUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryDocumentId)
            val siblingNames = ConcurrentHashMap.newKeySet<String>()

            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val displayNameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(displayNameIndex)?.let { siblingNames.add(it) }
                }
            }

            val directoryState = DirectoryState(directoryUri, siblingNames)

            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val displayNameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(documentIdIndex)
                    val displayName = cursor.getString(displayNameIndex) ?: continue
                    val mimeType = cursor.getString(mimeTypeIndex) ?: continue
                    val sizeBytes = if (cursor.isNull(sizeIndex)) -1L else cursor.getLong(sizeIndex)
                    val childDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pendingDirectories.add(childDocumentUri)
                        continue
                    }

                    if (!mimeType.startsWith("image/") && !FormatDetector.looksLikeImageName(displayName)) {
                        continue
                    }

                    yield(
                        TreeFileItem(
                            documentUri = childDocumentUri,
                            displayName = displayName,
                            sourceSizeBytes = sizeBytes,
                            directoryState = directoryState,
                        )
                    )
                }
            }
        }
    }

    fun streamTreeBatches(
        contentResolver: ContentResolver,
        treeUri: Uri,
        batchSize: Int,
    ): Sequence<List<TreeFileItem>> = sequence {
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val batch = ArrayList<TreeFileItem>(safeBatchSize)
        for (item in streamTree(contentResolver, treeUri)) {
            batch.add(item)
            if (batch.size >= safeBatchSize) {
                yield(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            yield(batch.toList())
        }
    }
}
