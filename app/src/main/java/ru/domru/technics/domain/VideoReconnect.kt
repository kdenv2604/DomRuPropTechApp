package ru.domru.technics.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import ru.domru.technics.data.AuthenticationFailure
import ru.domru.technics.data.DomruAuthenticationException
import ru.domru.technics.data.PortalProtocolException
import ru.domru.technics.data.PortalRequestException
import ru.domru.technics.data.SessionStorageException
import java.io.IOException

/** Временный сбой сессии или хранилища не останавливает открытую камеру навсегда. */
internal fun isVideoRequestRetryable(error: Throwable): Boolean = when (error) {
    is IOException, is SessionStorageException, is PortalProtocolException -> true
    is DomruAuthenticationException -> error.failure == AuthenticationFailure.SERVICE_UNAVAILABLE
    is PortalRequestException -> error.statusCode in setOf(408, 425, 429) || error.statusCode in 500..599
    else -> false
}

/** Восстанавливает открытую камеру с паузой после каждого неудачного запроса. */
class VideoReconnect(private val wait: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun <T : Any> load(
        visible: () -> Boolean,
        retryable: (Throwable) -> Boolean,
        loading: () -> Unit,
        failed: (Throwable?, Int?) -> Unit,
        request: suspend () -> T?,
    ): T? {
        var failures = 0
        while (visible()) {
            loading()
            var error: Throwable? = null
            val result = try { request() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { error = cause; null }
            if (!visible()) return null
            if (result != null) return result
            val seconds = if (error != null && !retryable(error)) null else when (failures++) {
                0 -> 2; 1 -> 4; 2 -> 8; 3 -> 15; else -> 30
            }
            failed(error, seconds)
            if (seconds == null) return null
            wait(seconds * 1000L)
        }
        return null
    }
}
