package ru.domru.technics.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.domru.technics.domain.CombinedAccessService
import ru.domru.technics.domain.RetryPolicy
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.Street
import java.io.IOException

/** Проверяет общий путь чтения, чтобы вложенные повторы не умножали попытки входа. */
class AutomaticLoginRetryTest {
    @Test
    fun networkFailureDuringAutomaticLoginStopsAfterThreeAttempts() = runBlocking {
        assertAutomaticLoginAttempts(IOException("Тестовая сеть недоступна"), expectedAttempts = 3)
    }

    @Test
    fun temporaryServerFailureDuringAutomaticLoginStopsAfterThreeAttempts() = runBlocking {
        assertAutomaticLoginAttempts(
            DomruAuthenticationException("Тестовый сервис недоступен", AuthenticationFailure.SERVICE_UNAVAILABLE),
            expectedAttempts = 3,
        )
    }

    @Test
    fun rejectedPasswordIsTriedOnlyOnce() = runBlocking {
        assertAutomaticLoginAttempts(rejectedTestPassword(), expectedAttempts = 1)
    }

    @Test
    fun ordinaryReadStillRetriesTemporaryNetworkFailure() = runBlocking {
        var reads = 0
        val repository = object : DomruRepository by PrototypeDomruRepository() {
            override suspend fun loadStreets(accountId: String): List<Street> {
                reads++
                if (reads < 3) throw IOException("Тестовый обрыв чтения")
                return emptyList()
            }
        }

        CombinedAccessService(repository, RetryPolicy(sleeper = {})).loadStreets(listOf(account))

        assertEquals(3, reads)
    }

    private suspend fun assertAutomaticLoginAttempts(failure: Exception, expectedAttempts: Int) {
        val session = expiredTestSession().copy(refreshToken = null)
        val store = InMemorySessionStore(session)
        val client = ControlledOidcClient().apply {
            signInAction = { _, _ -> throw failure }
        }
        val provider = DomruSessionProvider(store, client, pause = {})
        val repository = object : DomruRepository by PrototypeDomruRepository() {
            override suspend fun loadStreets(accountId: String): List<Street> {
                provider.accessToken(accountId)
                return emptyList()
            }
        }
        val service = CombinedAccessService(repository, RetryPolicy(sleeper = {}))

        val result = runCatching { service.loadStreets(listOf(account)) }

        assertTrue(result.isFailure)
        assertEquals(expectedAttempts, client.signInCalls)
        assertEquals(session, store.find(session.accountId))
    }

    private val account = AccountProfile("test-account", "Тестовый аккаунт", "test-login")
}
