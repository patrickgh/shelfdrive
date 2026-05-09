package io.audiobookshelf.aaos.diagnostics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

class DiagnosticEventLogger(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
    private val logFile = File(directory, LOG_FILE_NAME)

    fun record(event: String, details: Map<String, String?> = emptyMap()) {
        runCatching {
            directory.mkdirs()
            rotateIfNeeded()
            val payload = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("event", event)
                details
                    .filterValues { !it.isNullOrBlank() }
                    .forEach { (key, value) -> put(key, value) }
            }
            logFile.appendText(payload.toString() + "\n")
        }
    }

    fun readLogText(): String {
        return readText(logFile)
    }

    fun readPreviousLogText(): String {
        return readText(File(directory, BACKUP_LOG_FILE_NAME))
    }

    private fun readText(file: File): String {
        return try {
            if (file.isFile) {
                file.readText()
            } else {
                ""
            }
        } catch (_: IOException) {
            ""
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
        private const val DIRECTORY_NAME = "diagnostics"
        private const val LOG_FILE_NAME = "events.log"
        private const val BACKUP_LOG_FILE_NAME = "events.previous.log"
        private const val MAX_LOG_BYTES = 128L * 1024L
    }
}
