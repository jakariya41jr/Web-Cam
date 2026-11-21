package com.example.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Lightweight, non-blocking diagnostics log collector.
 * Never executes on the real-time camera/audio hook thread.
 * Supports both non-root in-app logcat / event capture and optional `su` Vector logcat retrieval.
 */
object LogCollector {
    private const val MAX_INTERNAL_LINES = 80
    private val internalEvents = ConcurrentLinkedDeque<String>()

    fun recordEvent(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        internalEvents.addFirst("$ts [$tag] $message")
        while (internalEvents.size > MAX_INTERNAL_LINES) {
            internalEvents.pollLast()
        }
    }

    fun getInternalEvents(): List<String> = internalEvents.toList()

    fun clearInternalEvents() {
        internalEvents.clear()
    }

    suspend fun collectLogs(useRoot: Boolean): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val collected = mutableListOf<String>()
            collected += "=== VirtualCam App Events ==="
            val events = getInternalEvents()
            if (events.isEmpty()) {
                collected += "(No local app diagnostic events yet)"
            } else {
                collected += events
            }

            collected += ""
            collected += if (useRoot) {
                "=== Root Logcat (Vector / VirtualCam) ==="
            } else {
                "=== App Logcat (Non-Root) ==="
            }

            val cmd = if (useRoot) {
                arrayOf(
                    "su", "-c",
                    "logcat -d -t 200 -v time | grep -iE 'VirtualCam|VectorLegacyBridge|XSharedPreferences|Camera|AudioRecord' | tail -n 120"
                )
            } else {
                arrayOf("logcat", "-d", "-t", "120", "-v", "time")
            }

            val lines = withTimeoutOrNull(3500L) {
                readCommandOutput(cmd)
            }

            if (lines == null) {
                collected += "(Logcat collection timed out)"
            } else if (lines.isEmpty()) {
                collected += if (useRoot) {
                    "(No matching root log lines or root access denied)"
                } else {
                    "(No recent app log lines)"
                }
            } else {
                collected += lines
            }
            collected
        }
    }

    private fun readCommandOutput(cmd: Array<String>): List<String> {
        var process: Process? = null
        return try {
            process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = mutableListOf<String>()
            var line: String? = reader.readLine()
            while (line != null && result.size < 200) {
                result.add(line)
                line = reader.readLine()
            }
            result
        } catch (t: Throwable) {
            listOf("(Log reader unavailable: ${t.message ?: t.javaClass.simpleName})")
        } finally {
            runCatching { process?.destroy() }
        }
    }
}
