package com.melomaniac.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class LogEntry(
    val id: Long,
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun timeLabel(): String = TIME_FMT.format(Date(timeMs))

    companion object {
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * In-memory ring buffer of app events for the Logs screen.
 * Also mirrors to Android Logcat.
 */
object AppLog {
    private const val MAX_ENTRIES = 2500
    private val seq = AtomicLong(0)
    private val lock = Any()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = append(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = append(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) {
        append(LogLevel.WARN, tag, format(message, error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        append(LogLevel.ERROR, tag, format(message, error))
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
        Log.i("AppLog", "cleared")
    }

    fun dumpText(): String = synchronized(lock) {
        _entries.value.joinToString("\n") { e ->
            "${e.timeLabel()} ${e.level.name.first()} [${e.tag}] ${e.message}"
        }
    }

    private fun format(message: String, error: Throwable?): String {
        if (error == null) return message
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return if (message.isBlank()) detail else "$message — $detail"
    }

    private fun append(level: LogLevel, tag: String, message: String) {
        val cleanTag = tag.take(32).ifBlank { "App" }
        val cleanMsg = message.trim().ifBlank { "(empty)" }.take(4000)
        val entry = LogEntry(
            id = seq.incrementAndGet(),
            timeMs = System.currentTimeMillis(),
            level = level,
            tag = cleanTag,
            message = cleanMsg,
        )
        synchronized(lock) {
            val cur = _entries.value
            _entries.value = if (cur.size >= MAX_ENTRIES) {
                cur.drop(cur.size - MAX_ENTRIES + 1) + entry
            } else {
                cur + entry
            }
        }
        when (level) {
            LogLevel.DEBUG -> Log.d(cleanTag, cleanMsg)
            LogLevel.INFO -> Log.i(cleanTag, cleanMsg)
            LogLevel.WARN -> Log.w(cleanTag, cleanMsg)
            LogLevel.ERROR -> Log.e(cleanTag, cleanMsg)
        }
    }
}
