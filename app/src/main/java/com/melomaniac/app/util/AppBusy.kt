package com.melomaniac.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global blocking busy state for UI actions (buttons).
 * While [message] is non-null, the app shows a full-screen loading overlay.
 */
object AppBusy {
    private val mutex = Mutex()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val isBusy: Boolean get() = _message.value != null

    suspend fun <T> run(label: String, block: suspend () -> T): T = mutex.withLock {
        _message.value = label
        AppLog.i("Busy", label)
        try {
            block()
        } catch (e: Exception) {
            AppLog.e("Busy", "Failed: $label", e)
            throw e
        } finally {
            _message.value = null
        }
    }
}
