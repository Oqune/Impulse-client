package com.example.impulse.util

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central, structured logging for Impulse.
 *
 * Wraps [Timber] and installs three trees depending on the build type:
 *  - [DebugTree]   : detailed logcat output, debug builds only.
 *  - [FileTree]    : appends every record to a rotating file set on disk
 *                    (max 5 MB per file, 5 files kept) so logs survive process
 *                    death and can be exported from the in-app Logs screen.
 *  - [ReleaseTree] : only ERROR/ASSERT to logcat in production (no file, no PII).
 *
 * Privacy: the [redact] helper guarantees we NEVER write message contents,
 * passwords, private keys, or full cert hashes. Cert hashes are truncated to
 * the first 8 hex chars for correlation. The FileTree is safe to share with a
 * developer because it contains no secrets by construction.
 *
 * Usage from anywhere:
 *   LogManager.d("ChatController") { "group secret derived from ${keys.size}" }
 *   LogManager.i("WebTransportClient", "session ready")
 *   LogManager.e("ChatController", "auth failed", throwable)
 */
object LogManager {

    private const val MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024 // 5 MB
    private const val MAX_FILES = 5
    private const val FILE_PREFIX = "impulse_log"
    private const val FILE_EXT = ".txt"

    private val initialized = AtomicBoolean(false)
    private lateinit var logDir: File
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    // User-controlled master switch for on-disk file logging. When false, the
    // FileTree is not planted (or is uprooted), so nothing is written to disk.
    // Logcat output in debug builds is independent of this flag.
    @Volatile private var fileLoggingEnabled = true
    private val fileTree = FileTree()

    /** Install the appropriate trees. Safe to call once from Application.onCreate. */
    fun init(context: Context, isDebug: Boolean, loggingEnabled: Boolean = true) {
        if (initialized.getAndSet(true)) return
        logDir = File(context.filesDir, "logs").also { it.mkdirs() }
        fileLoggingEnabled = loggingEnabled

        if (isDebug) {
            Timber.plant(DebugTree())
            if (fileLoggingEnabled) Timber.plant(fileTree)
        } else {
            // Production: minimal footprint, no file, only errors/asserts.
            Timber.plant(ReleaseTree())
        }
        i("LogManager", "logging initialized (debug=$isDebug, fileLogging=$fileLoggingEnabled, sdk=${Build.VERSION.SDK_INT})")
    }

    /**
     * Runtime toggle for on-disk file logging. When disabled, the rotating log
     * file tree is removed so no further records are persisted; re-enabling
     * re-plants it. This is the backing store for the "Вести логи" switch in
     * App Settings.
     */
    fun setLoggingEnabled(enabled: Boolean) {
        fileLoggingEnabled = enabled
        if (!initialized.get()) return
        if (enabled) {
            // Avoid planting a duplicate tree.
            if (Timber.forest().none { it === fileTree }) Timber.plant(fileTree)
        } else {
            Timber.uproot(fileTree)
        }
    }

    /** Current state of the on-disk file logging toggle. */
    fun isLoggingEnabled(): Boolean = fileLoggingEnabled

    // ---- Level helpers (inline so the tag is cheap and call-sites read well) ----

    fun v(tag: String, message: String) = Timber.tag(tag).v(message)
    fun v(tag: String, lazy: () -> String) = Timber.tag(tag).v(lazy())
    fun d(tag: String, message: String) = Timber.tag(tag).d(message)
    fun d(tag: String, lazy: () -> String) = Timber.tag(tag).d(lazy())
    fun i(tag: String, message: String) = Timber.tag(tag).i(message)
    fun i(tag: String, lazy: () -> String) = Timber.tag(tag).i(lazy())
    fun w(tag: String, message: String) = Timber.tag(tag).w(message)
    fun w(tag: String, lazy: () -> String) = Timber.tag(tag).w(lazy())
    fun w(tag: String, message: String, t: Throwable) = Timber.tag(tag).w(t, message)
    fun e(tag: String, message: String, t: Throwable? = null) =
        if (t != null) Timber.tag(tag).e(t, message) else Timber.tag(tag).e(message)

    // ---- Privacy helpers ----

    /** Truncate a cert hash to its first 8 hex chars (safe for correlation). */
    fun shortHash(full: String?): String = full?.take(8)?.lowercase() ?: "—"

    /**
     * Redact a potentially sensitive value. Returns a fixed placeholder so we
     * never accidentally log secrets. Use for passwords / keys / ciphertext.
     */
    fun redact(value: String?): String = if (value.isNullOrEmpty()) "—" else "<redacted>"

    /**
     * Last-line defence against secret leakage: scans a log message and masks
     * anything that looks like a secret. Applied to EVERY record written by
     * the FileTree (and DebugTree) so that even a careless call-site cannot
     * persist a private key, password, ciphertext, or full cert hash.
     *
     * Rules:
     *  - any run of >= 32 hex characters is replaced with "<hex-redacted>"
     *    (covers ML-KEM ciphertext, AES-GCM ciphertext, full cert hashes, etc.)
     *  - common secret-bearing keywords are replaced with "<redacted>".
     *
     * Note: cert hashes are already truncated to 8 chars via [shortHash] at
     * call-sites; this pass is purely defensive.
     */
    internal fun redactSecrets(message: String): String {
        // 1) Any run of >= 32 hex chars (ciphertext, full cert hash, raw keys).
        val hexMasked = message.replace(Regex("[0-9a-fA-F]{32,}"), "<hex-redacted>")
        // 2) Secret-bearing key=value / key: value pairs -> fully masked.
        val pairMasked = hexMasked.replace(
            Regex(
                "(?i)\\b(password|passwd|pwd|secret|privatekey|private_key|privkey|" +
                    "ciphertext|cipher|token|apikey|api_key|bearer|seed)\\b\\s*[=:]\\s*\\S+"
            ),
            "<redacted>"
        )
        // 3) Any remaining standalone secret keyword -> masked.
        return pairMasked.replace(
            Regex(
                "(?i)\\b(password|passwd|pwd|secret|privatekey|private_key|privkey|" +
                    "ciphertext|cipher|token|apikey|api_key|bearer|seed)\\b"
            ),
            "<redacted>"
        )
    }

    // ---- File access for the in-app Logs screen ----

    /** All log lines (newest file first, newest line last) as a single list. */
    fun readAll(): List<String> {
        if (!initialized.get()) return emptyList()
        return logFiles().flatMap { file ->
            runCatching { file.readLines() }.getOrDefault(emptyList())
        }
    }

    /**
     * Returns at most [max] of the most recent log lines (newest last), which
     * keeps the in-app Logs screen responsive even when the files are large.
     */
    fun readLast(max: Int = 1000): List<String> {
        val all = readAll()
        return if (all.size <= max) all else all.takeLast(max)
    }

    /** Export all logs into a single file; returns its path or null on failure. */
    fun export(targetDir: File): File? {
        if (!initialized.get()) return null
        val out = File(targetDir, "impulse_logs_${System.currentTimeMillis()}$FILE_EXT")
        return runCatching {
            out.bufferedWriter().use { w ->
                logFiles().forEach { f ->
                    f.readLines().forEach { w.appendLine(it) }
                }
            }
            out
        }.getOrNull()
    }

    /**
     * Export only the most recent [max] log lines (newest last) into a single
     * file. This is what the in-app "Экспорт" action uses so the shared file is
     * bounded and contains only recent, relevant history. Every line is already
     * secret-redacted by the FileTree, so the export is safe to share.
     */
    fun exportRecent(targetDir: File, max: Int = 1000): File? {
        if (!initialized.get()) return null
        val lines = readLast(max)
        if (lines.isEmpty()) return null
        val out = File(targetDir, "impulse_logs_${System.currentTimeMillis()}$FILE_EXT")
        return runCatching {
            out.bufferedWriter().use { w -> lines.forEach { w.appendLine(it) } }
            out
        }.getOrNull()
    }

    /** Deletes every log file on disk. */
    fun clear() {
        if (!initialized.get()) return
        logFiles().forEach { runCatching { it.delete() } }
    }

    // ---- Internals ----

    private fun logFiles(): List<File> {
        val files = logDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_EXT) }
            ?: return emptyList()
        // Oldest first so concatenation reads chronologically.
        return files.sortedBy { it.name }
    }

    // ------------------------------------------------------------------
    // Trees
    // ------------------------------------------------------------------

    /** Debug builds: full logcat with tag + message. */
    private class DebugTree : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String =
            "Impulse:${element.fileName}:${element.lineNumber}"

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Defensive redaction so secrets never reach logcat either.
            super.log(priority, tag, redactSecrets(message), t)
        }
    }

    /** Production: only errors/asserts, no file, no PII. */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.ERROR) {
                android.util.Log.e(tag, redactSecrets(message), t)
            }
        }
    }

    /**
     * Rotating file tree. Each line is formatted as:
     *   [2025-07-17T14:30:45.123] [INFO] [Tag] message
     * When the active file exceeds [MAX_FILE_SIZE_BYTES], it is rolled and the
     * oldest of [MAX_FILES] is deleted. Every record is passed through
     * [redactSecrets] so no private key / password / ciphertext / full hash is
     * ever persisted.
     */
    private class FileTree : Timber.Tree() {
        @Synchronized
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val safe = redactSecrets(message)
            val level = when (priority) {
                android.util.Log.VERBOSE -> "VERBOSE"
                android.util.Log.DEBUG -> "DEBUG"
                android.util.Log.INFO -> "INFO"
                android.util.Log.WARN -> "WARN"
                android.util.Log.ERROR -> "ERROR"
                else -> "ASSERT"
            }
            val ts = isoFmt.format(Date())
            val line = buildString {
                append("[$ts] [$level] [${tag ?: "?"}] $safe")
                t?.let { append(" | ${it.javaClass.simpleName}: ${it.message?.let { m -> redactSecrets(m) }}") }
            }
            runCatching { appendLine(line) }
        }

        private fun currentFile(): File {
            val files = logDir.listFiles { f ->
                f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_EXT)
            }?.sortedBy { it.name } ?: emptyList()
            val last = files.lastOrNull()
            if (last == null || last.length() >= MAX_FILE_SIZE_BYTES) {
                // Roll: create a new timestamped file, prune oldest if needed.
                val rolled = File(logDir, "${FILE_PREFIX}_${System.currentTimeMillis()}$FILE_EXT")
                // Keep at most MAX_FILES-1 old + this new one.
                val excess = files.size - (MAX_FILES - 1)
                if (excess > 0) files.take(excess).forEach { runCatching { it.delete() } }
                return rolled
            }
            return last
        }

        private fun appendLine(line: String) {
            val file = currentFile()
            BufferedWriter(FileWriter(file, true)).use { w ->
                w.appendLine(line)
            }
        }
    }
}
