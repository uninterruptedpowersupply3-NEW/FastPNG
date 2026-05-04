package org.nvmetools.fastpngtowebpandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class TranscodeService : Service() {
    companion object {
        private const val CHANNEL_ID = "transcode_jobs"
        private const val NOTIFICATION_ID = 1001
        private const val PREFETCH_MULTIPLIER = 2
        private const val MIN_IN_FLIGHT_IMAGES = 64
        private const val MAX_SPEED_HISTORY_POINTS = 120

        const val ACTION_START_FOLDER = "org.nvmetools.fastpngtowebpandroid.START_FOLDER"
        const val ACTION_START_FAST_FOLDER = "org.nvmetools.fastpngtowebpandroid.START_FAST_FOLDER"
        const val ACTION_START_SINGLE = "org.nvmetools.fastpngtowebpandroid.START_SINGLE"
        const val ACTION_STOP = "org.nvmetools.fastpngtowebpandroid.STOP"

        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_INPUT_PATH = "input_path"
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_SELECTION_LABEL = "selection_label"
        const val EXTRA_TARGET_FORMAT = "target_format"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DELETE_ORIGINALS = "delete_originals"
        const val EXTRA_SOURCE_NAME = "source_name"
    }

    private val stopRequested = AtomicBoolean(false)
    @Volatile
    private var workerThread: Thread? = null
    private var lastNotificationTimeMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested.set(true)
                JobRuntime.log("Stop requested.")
                updateNotification(force = true)
                return START_NOT_STICKY
            }

            ACTION_START_FOLDER, ACTION_START_FAST_FOLDER, ACTION_START_SINGLE -> {
                if (workerThread?.isAlive == true) {
                    JobRuntime.log("Ignored duplicate start request.")
                    return START_NOT_STICKY
                }

                createNotificationChannel()
                startForegroundNow()
                stopRequested.set(false)

                val jobConfig = JobConfig(
                    targetFormat = TargetFormat.valueOf(intent.getStringExtra(EXTRA_TARGET_FORMAT) ?: TargetFormat.WEBP.name),
                    quality = intent.getIntExtra(EXTRA_QUALITY, 85),
                    deleteOriginals = intent.getBooleanExtra(EXTRA_DELETE_ORIGINALS, true),
                )
                val selectionLabel = intent.getStringExtra(EXTRA_SELECTION_LABEL).orEmpty()
                JobRuntime.reset(selectionLabel)

                workerThread = Thread({
                    try {
                        when (intent.action) {
                            ACTION_START_FOLDER -> runFolderJob(intent, jobConfig)
                            ACTION_START_FAST_FOLDER -> runFastFolderJob(intent, jobConfig)
                            ACTION_START_SINGLE -> runSingleJob(intent, jobConfig)
                        }
                    } finally {
                        stopSelfResult(startId)
                    }
                }, "transcode-worker").apply { start() }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        super.onDestroy()
    }

    private fun runFolderJob(intent: Intent, jobConfig: JobConfig) {
        val treeUri = intent.getStringExtra(EXTRA_INPUT_URI)?.toUri()
        if (treeUri == null) {
            JobRuntime.finish("Folder job failed: missing tree URI.")
            updateNotification(force = true)
            return
        }

        val parallelism = chooseParallelism()
        val queueDepth = createQueueDepth(parallelism)
        val batchSize = chooseBatchSize(parallelism, queueDepth)
        val executor = Executors.newFixedThreadPool(parallelism)
        val completion = ExecutorCompletionService<BatchTranscodeResult>(executor)
        val pendingSlots = Semaphore(queueDepth)
        val startTimeMs = System.currentTimeMillis()
        var submitted = 0L
        var completed = 0L
        var submittedBatches = 0

        JobRuntime.update { snapshot ->
            snapshot.copy(
                currentMessage = "Scanning folder tree with $parallelism worker(s), batch size $batchSize, and $queueDepth queued batch slot(s)...",
                scanFinished = false,
                totalPlanned = 0,
                activeWorkers = 0,
                batchSize = batchSize,
                queueDepth = queueDepth,
            )
        }
        JobRuntime.log("Folder mode started with $parallelism worker(s), batch size $batchSize, and queue depth $queueDepth.")
        updateNotification(force = true)

        try {
            for (batch in SafScanner.streamTreeBatches(contentResolver, treeUri, batchSize)) {
                if (stopRequested.get()) {
                    break
                }

                val batchCount = batch.size.toLong()
                JobRuntime.update { snapshot ->
                    val nextScanned = snapshot.scanned + batchCount
                    snapshot.copy(
                        scanned = nextScanned,
                        currentMessage = "Scanning folder tree... $nextScanned candidates seen, $submitted queued, next batch $batchCount.",
                    )
                }

                drainCompleted(completion, pendingSlots, startTimeMs, nonBlocking = true)

                if (!acquireSubmissionSlot(completion, pendingSlots, startTimeMs)) {
                    break
                }
                completion.submit(Callable { ImageTranscoder.processTreeBatch(this, batch, jobConfig) })
                submitted += batchCount
                submittedBatches += 1
                JobRuntime.update { snapshot ->
                    snapshot.copy(
                        submitted = submitted,
                        activeWorkers = snapshot.activeWorkers + 1,
                        currentMessage = "Queued $submitted item(s) in $submittedBatches batch(es). Scanned ${snapshot.scanned}.",
                    )
                }
            }

            JobRuntime.update { snapshot ->
                snapshot.copy(
                    totalPlanned = submitted,
                    scanFinished = true,
                    currentMessage = "Scan finished. Processing $submitted queued item(s)...",
                )
            }

            while (completed < submitted) {
                if (!drainCompleted(completion, pendingSlots, startTimeMs, nonBlocking = false)) {
                    break
                }
                val snapshot = JobRuntime.current()
                completed = snapshot.converted + snapshot.skipped + snapshot.blocked + snapshot.failed
            }
        } catch (error: Exception) {
            JobRuntime.log("Folder job error: ${error.message}")
        } finally {
            executor.shutdownNow()
        }

        val finalSnapshot = JobRuntime.current()
        val finalMessage = if (stopRequested.get()) {
            "Stopped after ${finalSnapshot.scanned} scanned, ${finalSnapshot.submitted} queued, and ${finalSnapshot.converted} converted."
        } else {
            "Finished: ${finalSnapshot.converted} converted, ${finalSnapshot.skipped} skipped, ${finalSnapshot.blocked} blocked, ${finalSnapshot.failed} failed."
        }
        JobRuntime.finish(finalMessage)
        updateNotification(force = true)
    }

    private fun runFastFolderJob(intent: Intent, jobConfig: JobConfig) {
        val inputPath = intent.getStringExtra(EXTRA_INPUT_PATH)?.trim().orEmpty()
        if (inputPath.isBlank()) {
            JobRuntime.finish("Fast folder job failed: missing folder path.")
            updateNotification(force = true)
            return
        }

        val root = File(inputPath)
        if (!root.exists()) {
            JobRuntime.finish("Fast folder job failed: path does not exist: $inputPath")
            updateNotification(force = true)
            return
        }
        if (!root.isDirectory) {
            JobRuntime.finish("Fast folder job failed: path is not a folder: $inputPath")
            updateNotification(force = true)
            return
        }

        val parallelism = chooseParallelism()
        val queueDepth = createQueueDepth(parallelism)
        val batchSize = chooseBatchSize(parallelism, queueDepth)
        val executor = Executors.newFixedThreadPool(parallelism)
        val completion = ExecutorCompletionService<BatchTranscodeResult>(executor)
        val pendingSlots = Semaphore(queueDepth)
        val startTimeMs = System.currentTimeMillis()
        var submitted = 0L
        var completed = 0L
        var submittedBatches = 0

        JobRuntime.update { snapshot ->
            snapshot.copy(
                currentMessage = "Fast scan on ${root.absolutePath} with $parallelism worker(s), batch size $batchSize, and $queueDepth queued batch slot(s)...",
                scanFinished = false,
                totalPlanned = 0,
                activeWorkers = 0,
                batchSize = batchSize,
                queueDepth = queueDepth,
            )
        }
        JobRuntime.log("Fast folder mode started on ${root.absolutePath} with $parallelism worker(s), batch size $batchSize, and queue depth $queueDepth.")
        updateNotification(force = true)

        try {
            for (batch in FileScanner.streamImageBatches(root, batchSize)) {
                if (stopRequested.get()) {
                    break
                }

                val batchCount = batch.size.toLong()
                JobRuntime.update { snapshot ->
                    val nextScanned = snapshot.scanned + batchCount
                    snapshot.copy(
                        scanned = nextScanned,
                        currentMessage = "Fast scan... $nextScanned candidate image(s) seen, $submitted queued, next batch $batchCount.",
                    )
                }

                drainCompleted(completion, pendingSlots, startTimeMs, nonBlocking = true)

                if (!acquireSubmissionSlot(completion, pendingSlots, startTimeMs)) {
                    break
                }
                completion.submit(Callable { ImageTranscoder.processFastBatch(batch, jobConfig) })
                submitted += batchCount
                submittedBatches += 1
                JobRuntime.update { snapshot ->
                    snapshot.copy(
                        submitted = submitted,
                        activeWorkers = snapshot.activeWorkers + 1,
                        currentMessage = "Fast mode queued $submitted item(s) in $submittedBatches batch(es). Scanned ${snapshot.scanned}.",
                    )
                }
            }

            JobRuntime.update { snapshot ->
                snapshot.copy(
                    totalPlanned = submitted,
                    scanFinished = true,
                    currentMessage = "Fast scan finished. Processing $submitted queued item(s)...",
                )
            }

            while (completed < submitted) {
                if (!drainCompleted(completion, pendingSlots, startTimeMs, nonBlocking = false)) {
                    break
                }
                val snapshot = JobRuntime.current()
                completed = snapshot.converted + snapshot.skipped + snapshot.blocked + snapshot.failed
            }
        } catch (error: Exception) {
            JobRuntime.log("Fast folder job error: ${error.message}")
        } finally {
            executor.shutdownNow()
        }

        val finalSnapshot = JobRuntime.current()
        val finalMessage = if (stopRequested.get()) {
            "Fast mode stopped after ${finalSnapshot.scanned} scanned, ${finalSnapshot.submitted} queued, and ${finalSnapshot.converted} converted."
        } else {
            "Fast mode finished: ${finalSnapshot.converted} converted, ${finalSnapshot.skipped} skipped, ${finalSnapshot.blocked} blocked, ${finalSnapshot.failed} failed."
        }
        JobRuntime.finish(finalMessage)
        updateNotification(force = true)
    }

    private fun runSingleJob(intent: Intent, jobConfig: JobConfig) {
        val inputUri = intent.getStringExtra(EXTRA_INPUT_URI)?.toUri()
        val outputUri = intent.getStringExtra(EXTRA_OUTPUT_URI)?.toUri()
        val sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME) ?: "selected image"

        if (inputUri == null || outputUri == null) {
            JobRuntime.finish("Single-image job failed: missing input or output URI.")
            updateNotification(force = true)
            return
        }

        val startTimeMs = System.currentTimeMillis()
        JobRuntime.update { snapshot ->
            snapshot.copy(
                scanned = 1,
                submitted = 1,
                totalPlanned = 1,
                scanFinished = true,
                activeWorkers = 1,
                batchSize = 1,
                queueDepth = 1,
                currentMessage = "Converting $sourceName",
            )
        }
        val result = ImageTranscoder.processSingleItem(this, inputUri, sourceName, outputUri, jobConfig)
        applyBatchResult(BatchTranscodeResult.fromResult(result), startTimeMs)
        val finalSnapshot = JobRuntime.current()
        val finalMessage = "Single-image job finished: ${finalSnapshot.converted} converted, ${finalSnapshot.skipped} skipped, ${finalSnapshot.blocked} blocked, ${finalSnapshot.failed} failed."
        JobRuntime.finish(finalMessage)
        updateNotification(force = true)
    }

    private fun acquireSubmissionSlot(
        completion: ExecutorCompletionService<BatchTranscodeResult>,
        pendingSlots: Semaphore,
        startTimeMs: Long,
    ): Boolean {
        while (!stopRequested.get()) {
            if (pendingSlots.tryAcquire()) {
                return true
            }
            drainCompleted(completion, pendingSlots, startTimeMs, nonBlocking = false)
        }
        return false
    }

    private fun drainCompleted(
        completion: ExecutorCompletionService<BatchTranscodeResult>,
        pendingSlots: Semaphore,
        startTimeMs: Long,
        nonBlocking: Boolean,
    ): Boolean {
        val future = if (nonBlocking) completion.poll() else completion.take()
        if (future == null) {
            return false
        }

        val result = try {
            future.get()
        } catch (error: Exception) {
            BatchTranscodeResult(
                converted = 0,
                skipped = 0,
                blocked = 0,
                failed = 1,
                bytesSaved = 0,
                lastMessage = "Worker failure: ${error.message}",
                logLines = listOf("Worker failure: ${error.message}"),
            )
        }
        pendingSlots.release()
        applyBatchResult(result, startTimeMs)
        return true
    }

    private fun createQueueDepth(parallelism: Int): Int {
        return max(parallelism * PREFETCH_MULTIPLIER, parallelism)
    }

    private fun applyBatchResult(result: BatchTranscodeResult, startTimeMs: Long) {
        val elapsedSeconds = max(1.0, (System.currentTimeMillis() - startTimeMs) / 1000.0)
        JobRuntime.update { snapshot ->
            val nextConverted = snapshot.converted + result.converted
            val nextSkipped = snapshot.skipped + result.skipped
            val nextBlocked = snapshot.blocked + result.blocked
            val nextFailed = snapshot.failed + result.failed
            val nextCompleted = nextConverted + nextSkipped + nextBlocked + nextFailed
            val nextSpeed = nextConverted / elapsedSeconds
            val nextHistory = (snapshot.speedHistory + nextSpeed.toFloat()).takeLast(MAX_SPEED_HISTORY_POINTS)
            val nextActiveWorkers = max(0, snapshot.activeWorkers - 1)
            val progressSuffix = if (snapshot.scanFinished && snapshot.totalPlanned > 0) {
                " ($nextCompleted/${snapshot.totalPlanned})"
            } else {
                ""
            }
            snapshot.copy(
                converted = nextConverted,
                skipped = nextSkipped,
                blocked = nextBlocked,
                failed = nextFailed,
                activeWorkers = nextActiveWorkers,
                savedBytes = snapshot.savedBytes + result.bytesSaved,
                speedPerSecond = nextSpeed,
                speedHistory = nextHistory,
                currentMessage = result.lastMessage + progressSuffix,
            )
        }
        JobRuntime.logMany(result.logLines)
        updateNotification(force = false)
    }

    private fun chooseParallelism(): Int {
        val memoryClassMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            memoryClassMb <= 256 -> min(2, max(1, cores / 2))
            memoryClassMb <= 384 -> min(3, max(2, (cores + 1) / 2))
            memoryClassMb <= 512 -> min(4, max(2, cores / 2))
            memoryClassMb <= 768 -> min(5, max(3, cores - 2))
            else -> min(6, max(3, cores - 2))
        }
    }

    private fun chooseBatchSize(parallelism: Int, queueDepth: Int): Int {
        val memoryClassMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        val baseline = when {
            memoryClassMb <= 256 -> max(4, parallelism * 2)
            memoryClassMb <= 384 -> max(6, parallelism * 2)
            memoryClassMb <= 512 -> max(8, parallelism * 2)
            else -> max(12, parallelism * 3)
        }
        val neededForWindow = (MIN_IN_FLIGHT_IMAGES + queueDepth - 1) / queueDepth
        return max(baseline, neededForWindow).coerceAtMost(64)
    }

    private fun startForegroundNow() {
        val notification = buildNotification(JobRuntime.current())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationTimeMs < 1000) {
            return
        }
        lastNotificationTimeMs = now
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(JobRuntime.current()))
    }

    private fun buildNotification(snapshot: ProgressSnapshot): Notification {
        val completed = snapshot.converted + snapshot.skipped + snapshot.blocked + snapshot.failed
        val body = buildString {
            if (snapshot.scanFinished && snapshot.totalPlanned > 0) {
                append("$completed/${snapshot.totalPlanned} done")
            } else {
                append("${snapshot.scanned} scanned")
            }
            append(" | ${snapshot.converted} converted")
            append(" | ${snapshot.activeWorkers} active")
            append(" | batch ${snapshot.batchSize}")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(snapshot.running)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snapshot.currentMessage))
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
