package ru.domru.technics.data

/**
 * Все секреты одного логина, которые телефон хранит под ключом Android Keystore.
 * Пароль нужен только для автоматического нового входа, если сервер отверг старые токены.
 */
internal data class StoredSession(
    val accountId: String,
    val title: String,
    val login: String,
    val accessToken: String,
    val refreshToken: String?,
    val password: String?,
    val accessTokenExpiresAtMillis: Long,
    val createdAtMillis: Long,
)

/** Результат успешного ответа сервера входа. */
internal data class AuthorizedSession(
    val accountId: String,
    val title: String,
    val login: String,
    val accessToken: String,
    val refreshToken: String?,
    val accessTokenExpiresAtMillis: Long,
)

/** Короткий результат обычного обновления токена без повторной отправки пароля. */
internal data class AuthorizedToken(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtMillis: Long,
)

/** Позволяет проверять восстановление сессии без настоящего хранилища телефона. */
internal interface SessionStore {
    fun loadAll(): List<StoredSession>
    fun find(accountId: String): StoredSession?
    fun save(session: StoredSession)
    fun delete(accountId: String)
    /** Атомарно сохраняет результат только пока исходная запись не заменена или удалена. */
    fun saveIfUnchanged(expected: StoredSession, updated: StoredSession): Boolean
    /** Атомарно удаляет только ту запись, для которой было подтверждено окончание доступа. */
    fun deleteIfUnchanged(expected: StoredSession): Boolean
}
