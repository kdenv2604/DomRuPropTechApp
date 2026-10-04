package ru.domru.technics.data

/**
 * Эти данные заменяют сохранённый пароль.
 * Сервер выдаёт токены после входа, а телефон прячет их своим системным ключом.
 */
internal data class StoredSession(
    val accountId: String,
    val title: String,
    val login: String,
    val accessToken: String,
    val refreshToken: String?,
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
