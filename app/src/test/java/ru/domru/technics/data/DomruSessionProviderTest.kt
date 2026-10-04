package ru.domru.technics.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** Проверяет восстановление долгоживущего входа без настоящего телефона и сервера. */
class DomruSessionProviderTest {
    @Test
    fun deadRefreshTokenFallsBackToSavedLoginAndPassword() = runBlocking {
        val store = MemorySessionStore(
            expiredSession(password = "secret").copy(refreshToken = "dead-refresh"),
        )
        val client = FakeOidcClient().apply {
            refreshAction = {
                throw DomruAuthenticationException(
                    "refresh-token умер",
                    AuthenticationFailure.INVALID_CREDENTIALS,
                )
            }
            signInAction = { login, password ->
                assertEquals("worker", login)
                assertEquals("secret", password)
                authorizedSession("fresh-access")
            }
        }
        val provider = DomruSessionProvider(
            sessionStore = store,
            oidcClient = client,
            currentTimeMillis = { NOW_MILLIS },
            pause = {},
        )

        assertEquals("fresh-access", provider.accessToken(ACCOUNT_ID))
        assertEquals("secret", store.find(ACCOUNT_ID)?.password)
        assertEquals(1, client.refreshCalls)
        assertEquals(1, client.signInCalls)
    }

    @Test
    fun automaticLoginStopsAfterThreeTemporaryFailures() = runBlocking {
        val expected = IllegalStateException("сеть недоступна")
        val store = MemorySessionStore(expiredSession(password = "secret"))
        val client = FakeOidcClient().apply {
            signInAction = { _, _ -> throw expected }
        }
        val provider = DomruSessionProvider(
            sessionStore = store,
            oidcClient = client,
            currentTimeMillis = { NOW_MILLIS },
            pause = {},
        )

        val actual = expectFailure {
            provider.recoverAfterUnauthorized(ACCOUNT_ID, "old-access")
        }
        assertSame(expected, actual)
        assertEquals(3, client.signInCalls)
    }

    @Test
    fun definitivePasswordRejectionIsNotRepeated() = runBlocking {
        val store = MemorySessionStore(expiredSession(password = "wrong"))
        val client = FakeOidcClient().apply {
            signInAction = { _, _ ->
                throw DomruAuthenticationException(
                    "пароль отвергнут",
                    AuthenticationFailure.INVALID_CREDENTIALS,
                )
            }
        }
        val provider = DomruSessionProvider(
            sessionStore = store,
            oidcClient = client,
            currentTimeMillis = { NOW_MILLIS },
            pause = {},
        )

        expectFailure { provider.recoverAfterUnauthorized(ACCOUNT_ID, "old-access") }
        assertEquals(1, client.signInCalls)
    }

    @Test
    fun simultaneousUnauthorizedRequestsPerformOnlyOneLogin() = runBlocking {
        val store = MemorySessionStore(expiredSession(password = "secret"))
        val client = FakeOidcClient().apply {
            signInAction = { _, _ ->
                delay(30)
                authorizedSession("fresh-access")
            }
        }
        val provider = DomruSessionProvider(
            sessionStore = store,
            oidcClient = client,
            currentTimeMillis = { NOW_MILLIS },
            pause = {},
        )

        val first = async { provider.recoverAfterUnauthorized(ACCOUNT_ID, "old-access") }
        val second = async { provider.recoverAfterUnauthorized(ACCOUNT_ID, "old-access") }
        assertEquals("fresh-access", first.await())
        assertEquals("fresh-access", second.await())
        assertEquals(1, client.signInCalls)
    }

    @Test
    fun oldRecordExplainsThatPasswordMustBeSavedOnce() = runBlocking {
        val store = MemorySessionStore(expiredSession(password = null))
        val client = FakeOidcClient()
        val provider = DomruSessionProvider(
            sessionStore = store,
            oidcClient = client,
            currentTimeMillis = { NOW_MILLIS },
            pause = {},
        )

        val error = expectFailure {
            provider.recoverAfterUnauthorized(ACCOUNT_ID, "old-access")
        } as DomruAuthenticationException
        assertEquals(AuthenticationFailure.PASSWORD_NOT_STORED, error.failure)
        assertEquals(0, client.signInCalls)
    }

    /** Создаёт старую закончившуюся сессию с нужным тесту паролем. */
    private fun expiredSession(password: String?): StoredSession = StoredSession(
        accountId = ACCOUNT_ID,
        title = "Технический аккаунт",
        login = "worker",
        accessToken = "old-access",
        refreshToken = null,
        password = password,
        accessTokenExpiresAtMillis = NOW_MILLIS - 1,
        createdAtMillis = NOW_MILLIS - 10_000,
    )

    /** Возвращает новый набор токенов, похожий на успешный ответ id.dom.ru. */
    private fun authorizedSession(accessToken: String): AuthorizedSession = AuthorizedSession(
        accountId = ACCOUNT_ID,
        title = "Технический аккаунт",
        login = "worker",
        accessToken = accessToken,
        refreshToken = "fresh-refresh",
        accessTokenExpiresAtMillis = NOW_MILLIS + 300_000,
    )

    /** Упрощает проверку ожидаемой ошибки и возвращает сам объект ошибки. */
    private suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (error: Throwable) {
            return error
        }
        fail("Ожидалась ошибка")
        throw AssertionError("Недостижимая строка")
    }

    /** Маленькое хранилище в памяти заменяет Android Keystore внутри теста. */
    private class MemorySessionStore(initial: StoredSession) : SessionStore {
        private val sessions = linkedMapOf(initial.accountId to initial)

        override fun loadAll(): List<StoredSession> = sessions.values.toList()

        override fun find(accountId: String): StoredSession? = sessions[accountId]

        override fun save(session: StoredSession) {
            sessions[session.accountId] = session
        }

        override fun delete(accountId: String) {
            sessions.remove(accountId)
        }
    }

    /** Управляемая подделка считает вызовы и отдаёт ответы, заданные конкретным тестом. */
    private class FakeOidcClient : OidcSessionClient {
        var signInCalls = 0
        var refreshCalls = 0
        var signInAction: suspend (String, String) -> AuthorizedSession = { _, _ ->
            error("Тест не настроил вход")
        }
        var refreshAction: suspend (String) -> AuthorizedToken = {
            error("Тест не настроил обновление")
        }

        override suspend fun signIn(login: String, password: String): AuthorizedSession {
            signInCalls += 1
            return signInAction(login, password)
        }

        override suspend fun refresh(refreshToken: String): AuthorizedToken {
            refreshCalls += 1
            return refreshAction(refreshToken)
        }

        override suspend fun signOut(refreshToken: String) {
            // Выход в этих тестах не нужен, поэтому подделка ничего не делает.
        }
    }

    private companion object {
        const val ACCOUNT_ID = "account-1"
        const val NOW_MILLIS = 1_000_000L
    }
}
