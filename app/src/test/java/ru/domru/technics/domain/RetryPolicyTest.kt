package ru.domru.technics.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RetryPolicyTest {
    private val policy = RetryPolicy(sleeper = {})

    @Test
    fun safeReadIsLimitedToThreeAttempts() = runBlocking {
        var calls = 0
        expectIllegalState {
            policy.execute(RequestKind.SAFE_READ) {
                calls += 1
                error("offline")
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun doorOpeningIsNeverRetried() = runBlocking {
        var calls = 0
        expectIllegalState {
            policy.execute(RequestKind.DOOR_OPEN) {
                calls += 1
                error("unknown result")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun refreshIsNeverRetried() = runBlocking {
        var calls = 0
        expectIllegalState {
            policy.execute(RequestKind.SESSION_REFRESH) {
                calls += 1
                error("expired")
            }
        }
        assertEquals(1, calls)
    }

    /** Помогает каждому тесту одинаково проверить ожидаемую ошибку. */
    private suspend fun expectIllegalState(block: suspend () -> Unit) {
        try {
            block()
            fail("Ожидалась ошибка IllegalStateException")
        } catch (_: IllegalStateException) {
            // Всё хорошо: проверяемая операция завершилась именно ожидаемой ошибкой.
        }
    }
}
