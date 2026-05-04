package org.nvmetools.fastpngtowebpandroid

data class ProgressSnapshot(
    val running: Boolean = false,
    val selectionSummary: String = "",
    val scanned: Long = 0,
    val submitted: Long = 0,
    val totalPlanned: Long = 0,
    val scanFinished: Boolean = false,
    val activeWorkers: Int = 0,
    val batchSize: Int = 1,
    val queueDepth: Int = 0,
    val converted: Long = 0,
    val skipped: Long = 0,
    val blocked: Long = 0,
    val failed: Long = 0,
    val savedBytes: Long = 0,
    val speedPerSecond: Double = 0.0,
    val speedHistory: List<Float> = emptyList(),
    val currentMessage: String = "Ready.",
    val recentLogs: List<String> = emptyList(),
)

object JobRuntime {
    private const val MAX_LOG_LINES = 80
    private val lock = Any()
    private var snapshot = ProgressSnapshot()

    fun current(): ProgressSnapshot = synchronized(lock) {
        snapshot.copy(
            speedHistory = snapshot.speedHistory.toList(),
            recentLogs = snapshot.recentLogs.toList(),
        )
    }

    fun reset(selectionSummary: String) {
        synchronized(lock) {
            snapshot = ProgressSnapshot(
                running = true,
                selectionSummary = selectionSummary,
                currentMessage = "Preparing job...",
                recentLogs = listOf("Job created for $selectionSummary"),
            )
        }
    }

    fun update(transform: (ProgressSnapshot) -> ProgressSnapshot) {
        synchronized(lock) {
            snapshot = transform(snapshot)
        }
    }

    fun log(message: String) {
        synchronized(lock) {
            val nextLogs = (snapshot.recentLogs + message).takeLast(MAX_LOG_LINES)
            snapshot = snapshot.copy(recentLogs = nextLogs)
        }
    }

    fun logMany(messages: List<String>) {
        if (messages.isEmpty()) {
            return
        }
        synchronized(lock) {
            val nextLogs = (snapshot.recentLogs + messages).takeLast(MAX_LOG_LINES)
            snapshot = snapshot.copy(recentLogs = nextLogs)
        }
    }

    fun finish(message: String) {
        synchronized(lock) {
            val nextLogs = (snapshot.recentLogs + message).takeLast(MAX_LOG_LINES)
            snapshot = snapshot.copy(
                running = false,
                currentMessage = message,
                recentLogs = nextLogs,
            )
        }
    }
}
