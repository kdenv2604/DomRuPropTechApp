package ru.domru.technics.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Даёт запросам свежий токен и сам восстанавливает вход сохранённым паролем.
 * Для одного логина восстановление всегда идёт по очереди, а не несколькими потоками сразу.
 */
internal class DomruSessionProvider(
    private val sessionStore: SessionStore,
    private val oidcClient: OidcSessionClient,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val accountLocks = ConcurrentHashMap<String, Mutex>()

    /** Возвращает ещё годный токен или восстанавливает закончившуюся сессию. */
    suspend fun accessToken(accountId: String): String = lockFor(accountId).withLock {
        val session = requireSession(accountId)
        if (session.hasFreshAccessToken()) return@withLock session.accessToken

        val refreshToken = session.refreshToken
        if (refreshToken != null) {
            try {
                val refreshed = oidcClient.refresh(refreshToken)
                val updated = session.copy(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken,
                    accessTokenExpiresAtMillis = refreshed.accessTokenExpiresAtMillis,
                )
                sessionStore.save(updated)
                return@withLock updated.accessToken
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // Причина может быть не только 401: сервер иногда отвечает общим сбоем.
                // Полноценный вход ниже либо восстановит сессию, либо честно вернёт ошибку.
            }
        }

        automaticSignIn(session).accessToken
    }

    /**
     * После ответа 401 получает совершенно новую сессию сохранённой парой логин-пароль.
     * Если соседний запрос уже успел это сделать, второй вход не выполняется.
     */
    suspend fun recoverAfterUnauthorized(
        accountId: String,
        rejectedAccessToken: String,
    ): String = lockFor(accountId).withLock {
        val current = requireSession(accountId)
        if (current.accessToken != rejectedAccessToken && current.hasFreshAccessToken()) {
            return@withLock current.accessToken
        }
        automaticSignIn(current).accessToken
    }

    /** Делает не больше трёх попыток входа и сохраняет новую сессию вместе с паролем. */
    private suspend fun automaticSignIn(oldSession: StoredSession): StoredSession {
        val password = oldSession.password
            ?: throw DomruAuthenticationException(
                "Для автовхода нужно один раз сохранить пароль",
                AuthenticationFailure.PASSWORD_NOT_STORED,
            )
        var lastError: Throwable? = null
        repeat(MAX_AUTOMATIC_LOGIN_ATTEMPTS) { index ->
            try {
                val authorized = oidcClient.signIn(oldSession.login, password)
                // Старый внутренний номер оставляем, чтобы открытый экран не потерял аккаунт.
                return oldSession.copy(
                    title = authorized.title,
                    login = authorized.login,
                    accessToken = authorized.accessToken,
                    refreshToken = authorized.refreshToken,
                    password = password,
                    accessTokenExpiresAtMillis = authorized.accessTokenExpiresAtMillis,
                ).also(sessionStore::save)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                // Явный отказ означает, что те же данные повторять бессмысленно и небезопасно.
                if (error.isDefinitiveLoginRejection()) throw error
                val hasNextAttempt = index + 1 < MAX_AUTOMATIC_LOGIN_ATTEMPTS
                if (!hasNextAttempt) throw error
                pause(if (index == 0) FIRST_RETRY_DELAY_MILLIS else SECOND_RETRY_DELAY_MILLIS)
            }
        }
        throw checkNotNull(lastError)
    }

    /** Находит запись или объясняет, почему автоматический вход невозможен. */
    private fun requireSession(accountId: String): StoredSession = sessionStore.find(accountId)
        ?: throw DomruAuthenticationException(
            "Сохранённая сессия не найдена",
            AuthenticationFailure.SESSION_EXPIRED,
        )

    /** Проверяет срок с небольшим запасом, чтобы токен не умер во время запроса. */
    private fun StoredSession.hasFreshAccessToken(): Boolean = accessTokenExpiresAtMillis >
        currentTimeMillis() + TOKEN_SAFETY_MARGIN_MILLIS

    /** Один аккаунт получает один замок, поэтому одновременные запросы не портят токены. */
    private fun lockFor(accountId: String): Mutex = accountLocks.getOrPut(accountId) { Mutex() }

    /** Неверный пароль и запрет клиента не исправятся от повторения тех же данных. */
    private fun Throwable.isDefinitiveLoginRejection(): Boolean =
        this is DomruAuthenticationException && failure in setOf(
            AuthenticationFailure.INVALID_CREDENTIALS,
            AuthenticationFailure.CLIENT_REJECTED,
        )

    private companion object {
        const val TOKEN_SAFETY_MARGIN_MILLIS = 30_000L
        const val MAX_AUTOMATIC_LOGIN_ATTEMPTS = 3
        const val FIRST_RETRY_DELAY_MILLIS = 300L
        const val SECOND_RETRY_DELAY_MILLIS = 900L
    }
}
