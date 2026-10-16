package ru.domru.technics.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Задержки ответов задаются явно: тесты проверяют порядок операций без sleep и сети. */
class SessionConcurrencyTest {
    @Test
    fun lateRefreshDoesNotEraseExplicitlySavedPassword() = runBlocking {
        withTimeout(5_000) {
            val old = expiredTestSession(password = null)
            val store = InMemorySessionStore(old)
            val refreshStarted = CompletableDeferred<Unit>()
            val refreshReply = CompletableDeferred<AuthorizedToken>()
            val client = ControlledOidcClient().apply {
                refreshAction = {
                    refreshStarted.complete(Unit)
                    refreshReply.await()
                }
                signInAction = { _, _ -> authorizedTestSession("explicit-access") }
            }
            val provider = DomruSessionProvider(store, client, pause = {})
            val repository = ProptechDomruRepository(store, client)
            val refreshing = async { provider.accessToken(old.accountId) }
            refreshStarted.await()

            // Явный вход обязан завершиться до ответа старого refresh.
            val saved = repository.savePasswordForAutomaticLogin(old.accountId, "new-password")
            assertTrue(saved.hasSavedPassword)
            assertFalse(refreshing.isCompleted)
            val explicitSession = store.find(old.accountId)
            refreshReply.complete(AuthorizedToken("late-access", "late-refresh", Long.MAX_VALUE))

            assertEquals("explicit-access", refreshing.await())
            assertEquals(explicitSession, store.find(old.accountId))
            assertEquals("new-password", store.find(old.accountId)?.password)
        }
    }

    @Test
    fun lateRefreshFailureUsesExplicitSessionWithoutOldPasswordLogin() = runBlocking {
        withTimeout(5_000) {
            val old = expiredTestSession()
            val store = InMemorySessionStore(old)
            val refreshStarted = CompletableDeferred<Unit>()
            val refreshReply = CompletableDeferred<AuthorizedToken>()
            val client = ControlledOidcClient().apply {
                refreshAction = {
                    refreshStarted.complete(Unit)
                    refreshReply.await()
                }
                signInAction = { _, password ->
                    assertEquals("new-password", password)
                    authorizedTestSession("explicit-access")
                }
            }
            val provider = DomruSessionProvider(store, client, pause = {})
            val repository = ProptechDomruRepository(store, client)
            val refreshing = async { runCatching { provider.accessToken(old.accountId) } }
            refreshStarted.await()
            repository.signIn(old.login, "new-password")
            refreshReply.completeExceptionally(rejectedTestPassword())

            assertEquals("explicit-access", refreshing.await().getOrThrow())
            assertEquals(1, client.signInCalls)
            assertEquals("new-password", store.find(old.accountId)?.password)
        }
    }

    @Test
    fun lateAutomaticLoginDoesNotOverwriteExplicitSession() = runBlocking {
        withTimeout(5_000) {
            val old = expiredTestSession().copy(refreshToken = null)
            val store = InMemorySessionStore(old)
            val oldLoginStarted = CompletableDeferred<Unit>()
            val oldLoginReply = CompletableDeferred<AuthorizedSession>()
            val client = ControlledOidcClient().apply {
                signInAction = { _, password ->
                    if (password == "old-password") {
                        oldLoginStarted.complete(Unit)
                        oldLoginReply.await()
                    } else {
                        authorizedTestSession("explicit-access")
                    }
                }
            }
            val provider = DomruSessionProvider(store, client, pause = {})
            val repository = ProptechDomruRepository(store, client)
            val automatic = async { provider.accessToken(old.accountId) }
            oldLoginStarted.await()
            repository.signIn(old.login, "new-password")
            val explicitSession = store.find(old.accountId)
            oldLoginReply.complete(authorizedTestSession("late-access"))

            assertEquals("explicit-access", automatic.await())
            assertEquals(explicitSession, store.find(old.accountId))
        }
    }

    @Test
    fun lateAutomaticLoginRejectionDoesNotRejectNewSession() = runBlocking {
        withTimeout(5_000) {
            val old = expiredTestSession().copy(refreshToken = null)
            val store = InMemorySessionStore(old)
            val oldLoginStarted = CompletableDeferred<Unit>()
            val oldLoginReply = CompletableDeferred<AuthorizedSession>()
            val client = ControlledOidcClient().apply {
                signInAction = { _, password ->
                    if (password == "old-password") {
                        oldLoginStarted.complete(Unit)
                        oldLoginReply.await()
                    } else {
                        authorizedTestSession("explicit-access")
                    }
                }
            }
            val provider = DomruSessionProvider(store, client, pause = {})
            val repository = ProptechDomruRepository(store, client)
            val automatic = async { runCatching { provider.accessToken(old.accountId) } }
            oldLoginStarted.await()
            repository.signIn(old.login, "new-password")
            oldLoginReply.completeExceptionally(rejectedTestPassword())

            assertEquals("explicit-access", automatic.await().getOrThrow())
            assertEquals(2, client.signInCalls)
            assertEquals("new-password", store.find(old.accountId)?.password)
        }
    }

    @Test
    fun lateRefreshDoesNotRestoreDeletedAccount() = runBlocking {
        withTimeout(5_000) {
            val old = expiredTestSession()
            val store = InMemorySessionStore(old)
            val refreshStarted = CompletableDeferred<Unit>()
            val refreshReply = CompletableDeferred<AuthorizedToken>()
            val client = ControlledOidcClient().apply {
                refreshAction = {
                    refreshStarted.complete(Unit)
                    refreshReply.await()
                }
            }
            val provider = DomruSessionProvider(store, client, pause = {})
            val refreshing = async { runCatching { provider.accessToken(old.accountId) } }
            refreshStarted.await()
            store.delete(old.accountId)
            refreshReply.complete(AuthorizedToken("late-access", "late-refresh", Long.MAX_VALUE))

            val error = refreshing.await().exceptionOrNull()
            assertTrue(error is DomruAuthenticationException)
            assertEquals(AuthenticationFailure.SESSION_EXPIRED, (error as DomruAuthenticationException).failure)
            assertNull(store.find(old.accountId))
            assertEquals(0, client.signInCalls)
        }
    }

    @Test
    fun lateSignOutDoesNotDeleteExplicitSession() = runBlocking {
        withTimeout(5_000) {
            val old = expiredTestSession()
            val store = InMemorySessionStore(old)
            val logoutStarted = CompletableDeferred<Unit>()
            val logoutReply = CompletableDeferred<Unit>()
            val client = ControlledOidcClient().apply {
                refreshAction = { throw rejectedTestPassword() }
                signInAction = { _, password ->
                    if (password == "old-password") throw rejectedTestPassword()
                    authorizedTestSession("explicit-access")
                }
                signOutAction = {
                    assertEquals("old-refresh", it)
                    logoutStarted.complete(Unit)
                    logoutReply.await()
                }
            }
            val repository = ProptechDomruRepository(store, client)
            val removing = async { runCatching { repository.signOut(old.accountId) } }
            logoutStarted.await()
            repository.signIn(old.login, "new-password")
            val explicitSession = store.find(old.accountId)
            logoutReply.complete(Unit)

            // Отмена удаления важна и для ViewModel: успешный результат убрал бы новую карточку.
            assertTrue(removing.await().exceptionOrNull() is PasswordRenewalUnavailableException)
            assertEquals(explicitSession, store.find(old.accountId))
        }
    }

    @Test
    fun confirmedExpiredAccountIsDeletedEvenWhenServerLogoutFails() = runBlocking {
        val old = expiredTestSession()
        val store = InMemorySessionStore(old)
        val client = ControlledOidcClient().apply {
            refreshAction = { throw rejectedTestPassword() }
            signInAction = { _, _ -> throw rejectedTestPassword() }
            signOutAction = { throw java.io.IOException("Тестовый обрыв соединения") }
        }

        ProptechDomruRepository(store, client).signOut(old.accountId)

        assertNull(store.find(old.accountId))
    }
}
