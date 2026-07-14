package io.audiobookshelf.aaos.diagnostics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class DiagnosticEventLogger(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
    private val logFile = File(directory, LOG_FILE_NAME)
    private var lastPrunedAt = 0L

    fun record(event: String, details: Map<String, String?> = emptyMap()) {
        val recordedDetails = details.toMap()
        writer.execute {
            synchronized(fileLock) {
                runCatching {
                    directory.mkdirs()
                    pruneExpiredLogsIfDue()
                    rotateIfNeeded()
                    val payload = JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("event", event)
                        recordedDetails
                            .filterValues { !it.isNullOrBlank() }
                            .forEach { (key, value) -> put(key, value) }
                    }
                    logFile.appendText(payload.toString() + "\n")
                }
            }
        }
    }

    fun readLogText(): String {
        return readRecentText(logFile)
    }

    fun readPreviousLogText(): String {
        return readRecentText(File(directory, BACKUP_LOG_FILE_NAME))
    }

    private fun readRecentText(file: File): String {
        return synchronized(fileLock) {
            try {
                if (file.isFile) {
                    filterRecentLines(file.readLines()).joinToString(separator = "\n", postfix = "\n").takeIf { it.isNotBlank() }
                        ?: ""
                } else {
                    ""
                }
            } catch (_: IOException) {
                ""
            }
        }
    }

    private fun pruneExpiredLogsIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPrunedAt < PRUNE_INTERVAL_MS) {
            return
        }
        lastPrunedAt = now
        pruneExpiredLogs()
    }

    private fun pruneExpiredLogs() {
        pruneExpiredLogFile(logFile)
        pruneExpiredLogFile(File(directory, BACKUP_LOG_FILE_NAME))
    }

    private fun pruneExpiredLogFile(file: File) {
        if (!file.isFile) {
            return
        }
        val recentLines = filterRecentLines(file.readLines())
        if (recentLines.isEmpty()) {
            file.delete()
        } else {
            file.writeText(recentLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun filterRecentLines(lines: List<String>): List<String> {
        val cutoff = System.currentTimeMillis() - MAX_LOG_AGE_MS
        return lines.filter { line ->
            runCatching {
                JSONObject(line).optLong("timestamp", 0L) >= cutoff
            }.getOrDefault(false)
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.length() <= MAX_LOG_BYTES) {
            return
        }
        val backupFile = File(directory, BACKUP_LOG_FILE_NAME)
        if (backupFile.exists()) {
            backupFile.delete()
        }
        logFile.renameTo(backupFile)
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "shelfdrive-diagnostics").apply { isDaemon = true }
        }
        private val fileLock = Any()
        private const val DIRECTORY_NAME = "diagnostics"
        private const val LOG_FILE_NAME = "events.log"
        private const val BACKUP_LOG_FILE_NAME = "events.previous.log"
        private const val MAX_LOG_BYTES = 128L * 1024L
        private const val MAX_LOG_AGE_MS = 10L * 60L * 1000L
        private const val PRUNE_INTERVAL_MS = 60L * 1000L
    }
}
