package ru.domru.technics.data

/** Хранилище в памяти заменяет Android Keystore в тестах сессий. */
internal class InMemorySessionStore(initial: StoredSession) : SessionStore {
    private val sessions = linkedMapOf(initial.accountId to initial)

    @Synchronized
    override fun loadAll(): List<StoredSession> = sessions.values.toList()

    @Synchronized
    override fun find(accountId: String): StoredSession? = sessions[accountId]

    @Synchronized
    override fun save(session: StoredSession) {
        sessions[session.accountId] = session
    }

    @Synchronized
    override fun delete(accountId: String) {
        sessions.remove(accountId)
    }

    @Synchronized
    override fun saveIfUnchanged(expected: StoredSession, updated: StoredSession): Boolean {
        require(expected.accountId == updated.accountId)
        if (sessions[expected.accountId] != expected) return false
        sessions[expected.accountId] = updated
        return true
    }

    @Synchronized
    override fun deleteIfUnchanged(expected: StoredSession): Boolean {
        if (sessions[expected.accountId] != expected) return false
        sessions.remove(expected.accountId)
        return true
    }
}

/** Все ответы задаются тестом; настоящий сервер никогда не вызывается. */
internal class ControlledOidcClient : OidcSessionClient {
    var signInCalls = 0
    var refreshCalls = 0
    var signInAction: suspend (String, String) -> AuthorizedSession = { _, _ ->
        error("Тест не настроил вход")
    }
    var refreshAction: suspend (String) -> AuthorizedToken = {
        error("Тест не настроил обновление")
    }
    var signOutAction: suspend (String) -> Unit = {
        error("Тест не настроил выход")
    }

    override suspend fun signIn(login: String, password: String): AuthorizedSession {
        signInCalls++
        return signInAction(login, password)
    }

    override suspend fun refresh(refreshToken: String): AuthorizedToken {
        refreshCalls++
        return refreshAction(refreshToken)
    }

    override suspend fun signOut(refreshToken: String) = signOutAction(refreshToken)
}

internal fun expiredTestSession(password: String? = "old-password") = StoredSession(
    accountId = "test-account",
    title = "Тестовый аккаунт",
    login = "test-login",
    accessToken = "old-access",
    refreshToken = "old-refresh",
    password = password,
    accessTokenExpiresAtMillis = 0L,
    createdAtMillis = 100L,
)

internal fun authorizedTestSession(token: String) = AuthorizedSession(
    accountId = "test-account",
    title = "Тестовый аккаунт",
    login = "test-login",
    accessToken = token,
    refreshToken = "$token-refresh",
    accessTokenExpiresAtMillis = Long.MAX_VALUE,
)

internal fun rejectedTestPassword() = DomruAuthenticationException(
    "Тестовый пароль отвергнут",
    AuthenticationFailure.INVALID_CREDENTIALS,
)
