package ru.domru.technics.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import ru.domru.technics.data.*

class VideoReconnectTest {
    @Test fun recoversAfterNetworkFailureAndMissingStream() = runBlocking {
        val pauses = mutableListOf<Long>(); var requests = 0
        val result = VideoReconnect { pauses += it }.load(
            visible = { true }, retryable = { true }, loading = {}, failed = { _, _ -> },
            request = { when (++requests) { 1 -> throw IOException(); 2 -> null; else -> "live" } })
        assertEquals("live", result); assertEquals(listOf(2000L, 4000L), pauses)
    }
    @Test fun closingViewDuringPausePreventsAnotherRequest() = runBlocking {
        var visible = true; var requests = 0
        VideoReconnect { visible = false }.load<String>(
            visible = { visible }, retryable = { true }, loading = {}, failed = { _, _ -> },
            request = { requests++; null })
        assertEquals(1, requests)
    }
    @Test fun permissionFailureDoesNotRetry() = runBlocking {
        var requests = 0; var delaySeconds: Int? = 1
        VideoReconnect { fail("Unexpected retry") }.load<String>(
            visible = { true }, retryable = { false }, loading = {}, failed = { _, seconds -> delaySeconds = seconds },
            request = { requests++; throw SecurityException() })
        assertEquals(1, requests); assertNull(delaySeconds)
    }
    @Test fun cancellationStopsReconnect() = runBlocking {
        var requests = 0
        try {
            VideoReconnect { throw CancellationException() }.load<String>(
                visible = { true }, retryable = { true }, loading = {}, failed = { _, _ -> }, request = { requests++; null })
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, requests) }
    }

    @Test fun temporaryTokenRefreshFailureRecoversWithoutReopeningCameraManually() = runBlocking {
        var requests = 0
        val result = VideoReconnect { }.load(
            visible = { true }, retryable = ::isVideoRequestRetryable, loading = {}, failed = { _, _ -> },
            request = {
                if (++requests == 1) throw DomruAuthenticationException("Refresh temporarily unavailable", AuthenticationFailure.SERVICE_UNAVAILABLE)
                "live"
            },
        )
        assertEquals("live", result)
        assertEquals(2, requests)
        assertTrue(isVideoRequestRetryable(SessionStorageException()))
        assertFalse(isVideoRequestRetryable(DomruAuthenticationException("Expired", AuthenticationFailure.SESSION_EXPIRED)))
        assertFalse(isVideoRequestRetryable(PortalRequestException(403, "Revoked")))
        assertFalse(isVideoRequestRetryable(IllegalStateException("Broken implementation")))
    }
}
