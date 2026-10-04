package ru.domru.technics.data

/** Даёт сетевым запросам свежий токен и при необходимости один раз обновляет его. */
internal class DomruSessionProvider(
    private val sessionStore: SecureSessionStore,
    private val oidcClient: DomruOidcClient,
) {
    /** Возвращает ещё годный токен или один раз обновляет закончившийся. */
    suspend fun accessToken(accountId: String): String {
        val session = sessionStore.find(accountId)
            ?: throw DomruAuthenticationException(
                "Сохранённая сессия не найдена",
                AuthenticationFailure.SESSION_EXPIRED,
            )
        val tokenStillFresh = session.accessTokenExpiresAtMillis >
            System.currentTimeMillis() + TOKEN_SAFETY_MARGIN_MILLIS
        if (tokenStillFresh) return session.accessToken

        val refreshToken = session.refreshToken
            ?: throw DomruAuthenticationException(
                "Нужно снова войти в аккаунт",
                AuthenticationFailure.SESSION_EXPIRED,
            )
        val refreshed = oidcClient.refresh(refreshToken)
        sessionStore.save(
            session.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
                accessTokenExpiresAtMillis = refreshed.accessTokenExpiresAtMillis,
            ),
        )
        return refreshed.accessToken
    }

    private companion object {
        const val TOKEN_SAFETY_MARGIN_MILLIS = 30_000L
    }
}
