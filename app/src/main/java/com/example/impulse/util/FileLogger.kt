package com.example.impulse.util

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background file logger for Impulse.
 *
 * Writes ALL log messages (D/I/W/E) to a single session log file at:
 *   `context.filesDir/impulse/session.log`
 *
 * The file is truncated on every app launch so only the current session's logs
 * are kept. A background thread drains a bounded queue so logging never blocks
 * the caller. Max file size is capped at 5 MB — older lines are discarded.
 *
 * Retrieve logs:
 *   adb shell run-as com.example.impulse cat files/impulse/session.txt
 *   adb shell run-as com.example.impulse cat files/impulse/session.txt > impulse.txt
 */
object FileLogger {

    private const val DIR_NAME = "impulse"
    private const val FILE_NAME = "session.txt"
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024 // 5 MB
    private const val QUEUE_CAPACITY = 4096

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val initialized = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private var writer: BufferedWriter? = null
    private var file: File? = null

    private val priorities = charArrayOf('D', 'I', 'W', 'E')

    /**
     * Initialize the file logger. Call once from [com.example.impulse.ImpulseApplication.onCreate].
     * Truncates any previous session log.
     */
    fun init(context: Context) {
        if (initialized.getAndSet(true)) return
        try {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            // Clean old session logs — only keep current session.
            dir.listFiles()?.forEach { if (it.name != FILE_NAME) it.delete() }
            val f = File(dir, FILE_NAME)
            // Truncate on each launch so only current session is kept.
            FileWriter(f, false).use { /* truncate */ }
            file = f
            writer = FileWriter(f, true).buffered()
            // Remove legacy logs/ directory from earlier versions.
            val oldDir = File(context.filesDir, "logs")
            if (oldDir.exists() && oldDir.isDirectory) oldDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e("FileLogger", "init failed", e)
            return
        }
        val drainThread = Thread({ drainLoop() }, "FileLogger-drain")
        drainThread.isDaemon = true
        drainThread.start()
    }

    /**
     * Enqueue a log line. Never blocks — drops the message if the queue is full.
     */
    fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (!initialized.get()) return
        val pChar = priorities.getOrElse(priority shr 4) { '?' }
        val time = dateFormat.format(Date())
        val sb = StringBuilder(128)
            .append(time)
            .append(' ')
            .append(pChar)
            .append('/')
            .append(tag)
            .append(": ")
            .append(message)
        if (throwable != null) {
            sb.append('\n').append(Log.getStackTraceString(throwable))
        }
        queue.offer(sb.toString()) // drop if full
    }

    /**
     * Return the full session log as a string (for sharing / copy).
     */
    fun getLogText(): String {
        val f = file ?: return ""
        return try {
            if (f.exists()) f.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Return the log file path (for `adb pull`).
     */
    fun getLogPath(): String? = file?.absolutePath

    /** Return log file size in bytes. */
    fun getLogSize(): Long = file?.length() ?: 0

    // ---- Internal ----

    private fun drainLoop() {
        while (true) {
            try {
                val line = queue.take()
                writeLine(line)
                // Drain any remaining queued lines without blocking.
                while (queue.isNotEmpty()) {
                    val extra = queue.poll() ?: break
                    writeLine(extra)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    @Synchronized
    private fun writeLine(line: String) {
        try {
            writer?.apply {
                append(line)
                append('\n')
                flush()
            }
            // Rotate if too large.
            val f = file ?: return
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                rotate(f)
            }
        } catch (e: Exception) {
            Log.e("FileLogger", "write failed", e)
        }
    }

    private fun rotate(f: File) {
        try {
            writer?.close()
            // Keep the last 256 KB, discard the rest.
            val text = f.readText()
            val keep = 256 * 1024
            val trimmed = if (text.length > keep) text.substring(text.length - keep) else text
            f.writeText(trimmed)
            writer = FileWriter(f, true).buffered()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /**
     * Timber tree that forwards all logs to [FileLogger].
     */
    class FileTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // The file channel is exposed via a FileProvider URI and may be
            // read by other apps given the URI, so redact secrets here too
            // (Bug: "unredacted secrets in file logs").
            FileLogger.log(priority, tag ?: "Impulse", LogManager.redactSecrets(message), t)
        }
    }
}
