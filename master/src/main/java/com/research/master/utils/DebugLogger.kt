package com.research.master.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized debug logging utility that respects user settings
 * Replaces direct Log.d(), Log.i(), Log.e() calls throughout the app
 *
 * Usage:
 * DebugLogger.d("Tag", "Debug message")
 * DebugLogger.i("Tag", "Info message")
 * DebugLogger.e("Tag", "Error message", exception)
 */
object DebugLogger {

    private var fileManager: FileManager? = null
    private var context: Context? = null

    // Log levels
    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * Initialize the logger with context (call this in Application.onCreate())
     */
    fun initialize(context: Context) {
        this.context = context
        this.fileManager = FileManager(context)
    }

    /**
     * Debug level logging
     */
    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message, null)
    }

    /**
     * Info level logging
     */
    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message, null)
    }

    /**
     * Warning level logging
     */
    fun w(tag: String, message: String) {
        log(Level.WARN, tag, message, null)
    }

    /**
     * Warning level logging with throwable
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        log(Level.WARN, tag, message, throwable)
    }

    /**
     * Error level logging
     */
    fun e(tag: String, message: String) {
        log(Level.ERROR, tag, message, null)
    }

    /**
     * Error level logging with throwable
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        log(Level.ERROR, tag, message, throwable)
    }

    /**
     * Core logging method that respects user settings
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        val fm = fileManager ?: return

        try {
            // Console logging (if enabled)
            if (fm.isConsoleLoggingEnabled()) {
                when (level) {
                    Level.DEBUG -> {
                        if (throwable != null) {
                            Log.d(tag, message, throwable)
                        } else {
                            Log.d(tag, message)
                        }
                    }
                    Level.INFO -> {
                        if (throwable != null) {
                            Log.i(tag, message, throwable)
                        } else {
                            Log.i(tag, message)
                        }
                    }
                    Level.WARN -> {
                        if (throwable != null) {
                            Log.w(tag, message, throwable)
                        } else {
                            Log.w(tag, message)
                        }
                    }
                    Level.ERROR -> {
                        if (throwable != null) {
                            Log.e(tag, message, throwable)
                        } else {
                            Log.e(tag, message)
                        }
                    }
                }
            }

            // File logging (if enabled)
            if (fm.isFileLoggingEnabled()) {
                writeToLogFile(level, tag, message, throwable)
            }

        } catch (e: Exception) {
            // Fallback to console if our logging fails
            Log.e("DebugLogger", "Failed to log message", e)
            Log.e(tag, message, throwable)
        }
    }

    /**
     * Write log entry to file
     */
    private fun writeToLogFile(level: Level, tag: String, message: String, throwable: Throwable?) {
        val fm = fileManager ?: return

        try {
            val logFolderPath = fm.getLogFileFolder()
            val logFolder = File(logFolderPath)

            // Ensure log folder exists
            if (!logFolder.exists()) {
                logFolder.mkdirs()
            }

            if (!logFolder.exists() || !logFolder.isDirectory || !logFolder.canWrite()) {
                // Can't write to log folder - silently fail
                return
            }

            // Create daily log file
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateString = dateFormat.format(Date())
            val logFileName = "stroopapp_${dateString}.log"
            val logFile = File(logFolder, logFileName)

            // Format log entry
            val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timeString = timeFormat.format(Date())
            val levelString = level.name.padEnd(5)

            val logEntry = StringBuilder()
            logEntry.append("$timeString $levelString $tag: $message")

            // Add throwable if present
            if (throwable != null) {
                logEntry.append("\n")
                logEntry.append("Exception: ${throwable.javaClass.simpleName}: ${throwable.message}")

                // Add first few stack trace lines
                val stackTrace = throwable.stackTrace
                for (i in 0 until minOf(5, stackTrace.size)) {
                    logEntry.append("\n    at ${stackTrace[i]}")
                }

                if (stackTrace.size > 5) {
                    logEntry.append("\n    ... ${stackTrace.size - 5} more")
                }
            }

            logEntry.append("\n")

            // Write to file (append mode)
            FileWriter(logFile, true).use { writer ->
                writer.write(logEntry.toString())
            }

        } catch (e: IOException) {
            // Silently fail if we can't write to log file
            // Don't log this error to avoid infinite loops
        }
    }

    /**
     * Clear all log files (for testing/cleanup)
     */
    fun clearLogFiles() {
        val fm = fileManager ?: return

        try {
            val logFolderPath = fm.getLogFileFolder()
            val logFolder = File(logFolderPath)

            if (logFolder.exists() && logFolder.isDirectory) {
                val logFiles = logFolder.listFiles { file ->
                    file.name.startsWith("stroopapp_") && file.name.endsWith(".log")
                }

                logFiles?.forEach { file ->
                    file.delete()
                }

                i("DebugLogger", "Cleared ${logFiles?.size ?: 0} log files")
            }

        } catch (e: Exception) {
            Log.e("DebugLogger", "Failed to clear log files", e)
        }
    }

    /**
     * Get current log files information
     */
    fun getLogFilesInfo(): List<LogFileInfo> {
        val fm = fileManager ?: return emptyList()

        return try {
            val logFolderPath = fm.getLogFileFolder()
            val logFolder = File(logFolderPath)

            if (!logFolder.exists() || !logFolder.isDirectory) {
                return emptyList()
            }

            val logFiles = logFolder.listFiles { file ->
                file.name.startsWith("stroopapp_") && file.name.endsWith(".log")
            } ?: return emptyList()

            logFiles.map { file ->
                LogFileInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            }.sortedByDescending { it.lastModified }

        } catch (e: Exception) {
            Log.e("DebugLogger", "Failed to get log files info", e)
            emptyList()
        }
    }

    /**
     * Data class for log file information
     */
    data class LogFileInfo(
        val name: String,
        val path: String,
        val size: Long,
        val lastModified: Long
    ) {
        fun getSizeString(): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> "${size / (1024 * 1024)} MB"
            }
        }

        fun getDateString(): String {
            val format = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            return format.format(Date(lastModified))
        }
    }
}