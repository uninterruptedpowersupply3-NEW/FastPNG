package org.nvmetools.fastpngtowebpandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import org.nvmetools.fastpngtowebpandroid.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    companion object {
        private const val DEFAULT_FAST_PATH = "/storage/emulated/0/DCIM"
        private const val DEFAULT_WHATSAPP_PATH = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"
    }

    private lateinit var binding: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())
    private var selection: Selection = Selection.None
    private var pendingSingleSource: Selection.SingleImage? = null

    private val pollStateRunnable = object : Runnable {
        override fun run() {
            renderState(JobRuntime.current())
            uiHandler.postDelayed(this, 300)
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        selection = Selection.SingleImage(uri, resolveDisplayName(uri))
        updateSelectionUi()
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        selection = Selection.Folder(uri, resolveTreeName(uri))
        updateSelectionUi()
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val singleSelection = pendingSingleSource ?: return@registerForActivityResult
        pendingSingleSource = null
        if (uri == null) {
            JobRuntime.log("Single-image job canceled before output was chosen.")
            return@registerForActivityResult
        }
        startSingleJob(singleSelection, uri)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacyStoragePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        renderFastAccessUi()
    }

    private val fastAccessSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        renderFastAccessUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fastPathInput.setText(DEFAULT_FAST_PATH)
        binding.qualitySeek.setOnSeekBarChangeListener(SimpleSeekListener { progress ->
            binding.qualityValue.text = (60 + progress).toString()
        })
        binding.qualityValue.text = currentQuality().toString()

        binding.pickImageButton.setOnClickListener {
            imagePicker.launch(arrayOf("image/*"))
        }
        binding.pickFolderButton.setOnClickListener {
            folderPicker.launch(null)
        }
        binding.grantFastAccessButton.setOnClickListener {
            requestFastStorageAccess()
        }
        binding.useFastPathButton.setOnClickListener {
            selectFastFolderPathFromInput()
        }
        binding.setDcimButton.setOnClickListener {
            binding.fastPathInput.setText(DEFAULT_FAST_PATH)
            selectFastFolderPathFromInput()
        }
        binding.setWhatsAppButton.setOnClickListener {
            binding.fastPathInput.setText(DEFAULT_WHATSAPP_PATH)
            selectFastFolderPathFromInput()
        }
        binding.startButton.setOnClickListener {
            ensureNotificationPermission()
            when (val currentSelection = selection) {
                is Selection.Folder -> startFolderJob(currentSelection)
                is Selection.FastFolderPath -> startFastFolderJob(currentSelection)
                is Selection.SingleImage -> {
                    pendingSingleSource = currentSelection
                    createDocument.launch(ImageTranscoder.buildTargetName(currentSelection.displayName, currentTargetFormat()))
                }
                Selection.None -> {
                    binding.statusText.text = getString(R.string.pick_selection_first)
                }
            }
        }
        binding.stopButton.setOnClickListener {
            stopConversion()
        }

        updateSelectionUi()
        renderFastAccessUi()
        renderState(JobRuntime.current())
    }

    override fun onStart() {
        super.onStart()
        renderFastAccessUi()
        uiHandler.post(pollStateRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(pollStateRunnable)
        super.onStop()
    }

    private fun startFolderJob(folderSelection: Selection.Folder) {
        val intent = Intent(this, TranscodeService::class.java).apply {
            action = TranscodeService.ACTION_START_FOLDER
            putExtra(TranscodeService.EXTRA_INPUT_URI, folderSelection.treeUri.toString())
            putExtra(TranscodeService.EXTRA_SELECTION_LABEL, folderSelection.label)
            putExtra(TranscodeService.EXTRA_TARGET_FORMAT, currentTargetFormat().name)
            putExtra(TranscodeService.EXTRA_QUALITY, currentQuality())
            putExtra(TranscodeService.EXTRA_DELETE_ORIGINALS, binding.deleteOriginalsCheck.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startFastFolderJob(folderSelection: Selection.FastFolderPath) {
        if (!hasFastStorageAccess()) {
            binding.statusText.text = getString(R.string.fast_access_required_status)
            requestFastStorageAccess()
            return
        }

        val folder = File(folderSelection.folderPath)
        if (!folder.exists()) {
            binding.statusText.text = getString(R.string.fast_path_missing, folderSelection.folderPath)
            return
        }
        if (!folder.isDirectory) {
            binding.statusText.text = getString(R.string.fast_path_not_folder, folderSelection.folderPath)
            return
        }

        val intent = Intent(this, TranscodeService::class.java).apply {
            action = TranscodeService.ACTION_START_FAST_FOLDER
            putExtra(TranscodeService.EXTRA_INPUT_PATH, folder.absolutePath)
            putExtra(TranscodeService.EXTRA_SELECTION_LABEL, "Fast path: ${folder.absolutePath}")
            putExtra(TranscodeService.EXTRA_TARGET_FORMAT, currentTargetFormat().name)
            putExtra(TranscodeService.EXTRA_QUALITY, currentQuality())
            putExtra(TranscodeService.EXTRA_DELETE_ORIGINALS, binding.deleteOriginalsCheck.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startSingleJob(singleSelection: Selection.SingleImage, outputUri: Uri) {
        val intent = Intent(this, TranscodeService::class.java).apply {
            action = TranscodeService.ACTION_START_SINGLE
            putExtra(TranscodeService.EXTRA_INPUT_URI, singleSelection.sourceUri.toString())
            putExtra(TranscodeService.EXTRA_OUTPUT_URI, outputUri.toString())
            putExtra(TranscodeService.EXTRA_SELECTION_LABEL, "Single image: ${singleSelection.displayName}")
            putExtra(TranscodeService.EXTRA_TARGET_FORMAT, currentTargetFormat().name)
            putExtra(TranscodeService.EXTRA_QUALITY, currentQuality())
            putExtra(TranscodeService.EXTRA_DELETE_ORIGINALS, false)
            putExtra(TranscodeService.EXTRA_SOURCE_NAME, singleSelection.displayName)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopConversion() {
        val intent = Intent(this, TranscodeService::class.java).apply {
            action = TranscodeService.ACTION_STOP
        }
        startService(intent)
    }

    private fun renderState(snapshot: ProgressSnapshot) {
        val completed = snapshot.converted + snapshot.skipped + snapshot.blocked + snapshot.failed
        binding.statusText.text = snapshot.currentMessage
        if (snapshot.running && !snapshot.scanFinished) {
            binding.progressBar.isIndeterminate = true
            binding.progressText.text = "Scanning... ${snapshot.scanned} seen, ${snapshot.submitted} queued, ${snapshot.activeWorkers} active, batch ${snapshot.batchSize}"
        } else {
            val totalPlanned = if (snapshot.totalPlanned > 0) snapshot.totalPlanned else maxOf(1L, completed)
            val clampedMax = totalPlanned.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val clampedProgress = completed.coerceAtMost(clampedMax.toLong()).toInt()
            val percent = if (totalPlanned > 0) (completed * 100.0 / totalPlanned) else 0.0
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max = clampedMax
            binding.progressBar.progress = clampedProgress
            binding.progressText.text = "Progress: $completed / $totalPlanned (${String.format("%.1f", percent)}%) | batch ${snapshot.batchSize}"
        }
        binding.throughputGraph.setPoints(snapshot.speedHistory)
        binding.statsText.text = buildString {
            append("Selection: ")
            append(if (snapshot.selectionSummary.isBlank()) "none" else snapshot.selectionSummary)
            append("\nScanned: ${snapshot.scanned}")
            append(" | Queued: ${snapshot.submitted}")
            append(" | Converted: ${snapshot.converted}")
            append(" | Skipped: ${snapshot.skipped}")
            append(" | Blocked: ${snapshot.blocked}")
            append(" | Failed: ${snapshot.failed}")
            append("\nSpeed: ${"%.1f".format(snapshot.speedPerSecond)} img/s")
            append(" | Active workers: ${snapshot.activeWorkers}")
            append(" | Batch size: ${snapshot.batchSize}")
            append(" | Queue depth: ${snapshot.queueDepth}")
            append(" | Saved: ${"%.2f".format(snapshot.savedBytes / 1024.0 / 1024.0)} MB")
        }
        binding.logText.text = if (snapshot.recentLogs.isEmpty()) {
            getString(R.string.log_ready)
        } else {
            snapshot.recentLogs.joinToString(separator = "\n")
        }
        binding.startButton.isEnabled = !snapshot.running
        binding.stopButton.isEnabled = snapshot.running
    }

    private fun updateSelectionUi() {
        when (val currentSelection = selection) {
            Selection.None -> {
                binding.selectionLabel.text = getString(R.string.no_selection)
                binding.deleteOriginalsCheck.isEnabled = true
            }
            is Selection.Folder -> {
                binding.selectionLabel.text = "Folder (SAF): ${currentSelection.label}"
                binding.deleteOriginalsCheck.isEnabled = true
            }
            is Selection.FastFolderPath -> {
                binding.selectionLabel.text = "Folder (fast path): ${currentSelection.label}"
                binding.deleteOriginalsCheck.isEnabled = true
            }
            is Selection.SingleImage -> {
                binding.selectionLabel.text = "Single image: ${currentSelection.displayName}"
                binding.deleteOriginalsCheck.isChecked = false
                binding.deleteOriginalsCheck.isEnabled = false
            }
        }
    }

    private fun renderFastAccessUi() {
        val granted = hasFastStorageAccess()
        binding.fastAccessText.text = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && granted ->
                getString(R.string.fast_access_granted)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                getString(R.string.fast_access_missing)
            granted ->
                getString(R.string.fast_access_legacy_granted)
            else ->
                getString(R.string.fast_access_legacy_missing)
        }
        binding.grantFastAccessButton.isEnabled = !granted
    }

    private fun selectFastFolderPathFromInput() {
        val normalized = normalizeFolderPath(binding.fastPathInput.text?.toString().orEmpty())
        if (normalized.isBlank()) {
            binding.statusText.text = getString(R.string.fast_path_empty)
            return
        }
        binding.fastPathInput.setText(normalized)
        binding.fastPathInput.setSelection(normalized.length)
        selection = Selection.FastFolderPath(normalized, normalized)
        updateSelectionUi()
    }

    private fun normalizeFolderPath(rawPath: String): String {
        return rawPath.trim().removeSurrounding("\"").replace('\\', '/')
    }

    private fun requestFastStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            fastAccessSettings.launch(intent)
            return
        }

        legacyStoragePermissions.launch(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
        )
    }

    private fun hasFastStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun currentTargetFormat(): TargetFormat {
        return when (binding.formatGroup.checkedRadioButtonId) {
            binding.jpegRadio.id -> TargetFormat.JPEG
            else -> TargetFormat.WEBP
        }
    }

    private fun currentQuality(): Int = 60 + binding.qualitySeek.progress

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) ?: "selected-image"
            }
        }
        return "selected-image"
    }

    private fun resolveTreeName(uri: Uri): String {
        return try {
            val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            contentResolver.query(treeDocumentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0) ?: uri.toString()
                } else {
                    uri.toString()
                }
            } ?: uri.toString()
        } catch (_: Exception) {
            uri.toString()
        }
    }

    sealed class Selection {
        data object None : Selection()
        data class SingleImage(val sourceUri: Uri, val displayName: String) : Selection()
        data class Folder(val treeUri: Uri, val label: String) : Selection()
        data class FastFolderPath(val folderPath: String, val label: String) : Selection()
    }

    private class SimpleSeekListener(
        private val onProgressChanged: (Int) -> Unit,
    ) : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            onProgressChanged(progress)
        }

        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
    }
}
