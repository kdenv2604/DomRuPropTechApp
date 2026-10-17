package ru.domru.technics.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Описывает три штатные операции Keycloak, чтобы их можно было проверить тестами. */
internal interface OidcSessionClient {
    suspend fun signIn(login: String, password: String): AuthorizedSession
    suspend fun refresh(refreshToken: String): AuthorizedToken
    suspend fun signOut(refreshToken: String)
}

/** Выполняет штатный вход в Keycloak на id.dom.ru без открытия браузера. */
internal class DomruOidcClient : OidcSessionClient {
    /** Проверяет пару логин-пароль и получает совершенно новый набор токенов. */
    override suspend fun signIn(
        login: String,
        password: String,
    ): AuthorizedSession = withContext(Dispatchers.IO) {
        val response = postForm(
            url = TOKEN_URL,
            fields = linkedMapOf(
                "grant_type" to "password",
                "client_id" to CLIENT_ID,
                "scope" to "openid",
                "username" to login,
                "password" to password,
            ),
        )
        val json = parseResponse(response)
        val accessToken = json.requiredText("access_token")
        val claims = readClaims(json.optString("id_token").ifBlank { accessToken })
        val serverId = claims?.optString("sub").orEmpty()
        val title = claims?.optString("name").orEmpty()
            .ifBlank { claims?.optString("preferred_username").orEmpty() }
            .ifBlank { login }
        val expiresInSeconds = json.optLong("expires_in", DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS)

        AuthorizedSession(
            accountId = serverId.ifBlank { stableAccountId(login) },
            title = title,
            login = login,
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank),
            accessTokenExpiresAtMillis = System.currentTimeMillis() +
                expiresInSeconds.coerceAtLeast(0L) * 1_000L,
        )
    }

    /** Получает новый короткий токен по длинной сохранённой сессии. */
    override suspend fun refresh(refreshToken: String): AuthorizedToken = withContext(Dispatchers.IO) {
        val response = postForm(
            url = TOKEN_URL,
            fields = linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to refreshToken,
            ),
        )
        val json = parseResponse(response)
        val expiresInSeconds = json.optLong("expires_in", DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS)
        AuthorizedToken(
            accessToken = json.requiredText("access_token"),
            refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank) ?: refreshToken,
            accessTokenExpiresAtMillis = System.currentTimeMillis() +
                expiresInSeconds.coerceAtLeast(0L) * 1_000L,
        )
    }

    /** Просит сервер завершить сессию, связанную с токеном обновления. */
    override suspend fun signOut(refreshToken: String) = withContext(Dispatchers.IO) {
        val response = postForm(
            url = LOGOUT_URL,
            fields = linkedMapOf(
                "client_id" to CLIENT_ID,
                "refresh_token" to refreshToken,
            ),
        )
        if (response.code !in 200..299) {
            throw DomruAuthenticationException(
                "Сервер не подтвердил выход",
                AuthenticationFailure.SERVICE_UNAVAILABLE,
            )
        }
    }

    /** Отправляет обычную защищённую веб-форму и читает ответ целиком. */
    private fun postForm(url: String, fields: Map<String, String>): HttpResponse {
        val body = encodeForm(fields)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            doOutput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }

        return try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(code, stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    /** Отличает неверный пароль от запрета клиента и временной поломки сервера. */
    private fun parseResponse(response: HttpResponse): JSONObject {
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.code in 200..299 && json != null) return json

        val errorCode = json?.optString("error").orEmpty()
        val failure = when (errorCode) {
            "invalid_grant" -> AuthenticationFailure.INVALID_CREDENTIALS
            "unauthorized_client", "invalid_client" -> AuthenticationFailure.CLIENT_REJECTED
            else -> AuthenticationFailure.SERVICE_UNAVAILABLE
        }
        val safeMessage = when (failure) {
            AuthenticationFailure.INVALID_CREDENTIALS -> "Сервер не принял логин или пароль"
            AuthenticationFailure.CLIENT_REJECTED -> "Сервер не разрешил вход из приложения"
            else -> "Сервер входа временно недоступен"
        }
        throw DomruAuthenticationException(safeMessage, failure)
    }

    /** Читает несекретную часть JWT, чтобы получить имя и номер аккаунта. */
    private fun readClaims(token: String): JSONObject? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return@runCatching null
        val decoded = Base64.decode(
            payload,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        JSONObject(String(decoded, StandardCharsets.UTF_8))
    }.getOrNull()

    /** Требует обязательное текстовое поле и выдаёт понятную ошибку, если его нет. */
    private fun JSONObject.requiredText(name: String): String = optString(name)
        .takeIf(String::isNotBlank)
        ?: throw DomruAuthenticationException(
            "Сервер вернул неполную сессию",
            AuthenticationFailure.SERVICE_UNAVAILABLE,
        )

    /** Делает постоянный безопасный номер, если сервер не прислал собственный. */
    private fun stableAccountId(login: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(login.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
        return "login-" + digest.take(12).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    /** Только те части HTTP-ответа, которые нужны этому классу. */
    private data class HttpResponse(val code: Int, val body: String)

    private companion object {
        const val CLIENT_ID = "lk-frontend"
        const val TOKEN_URL = "https://id.dom.ru/realms/lk/protocol/openid-connect/token"
        const val LOGOUT_URL = "https://id.dom.ru/realms/lk/protocol/openid-connect/logout"
        const val NETWORK_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS = 300L
    }
}

/** Превращает поля формы в безопасную строку для HTTP-запроса. */
internal fun encodeForm(fields: Map<String, String>): String = fields.entries.joinToString("&") {
    val key = URLEncoder.encode(it.key, StandardCharsets.UTF_8.name())
    val value = URLEncoder.encode(it.value, StandardCharsets.UTF_8.name())
    "$key=$value"
}
