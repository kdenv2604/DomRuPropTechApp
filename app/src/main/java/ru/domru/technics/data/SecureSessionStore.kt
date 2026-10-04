package ru.domru.technics.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранит каждую сессию в зашифрованном виде.
 * Ключ создаёт сам Android, поэтому обычным чтением файлов токены получить нельзя.
 */
internal class SecureSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** Читает все целые записи, а безнадёжно повреждённые аккуратно убирает. */
    @Synchronized
    fun loadAll(): List<StoredSession> {
        val brokenKeys = mutableListOf<String>()
        val sessions = preferences.all.mapNotNull { (key, storedValue) ->
            if (!key.startsWith(SESSION_PREFIX) || storedValue !is String) return@mapNotNull null
            try {
                decodeSession(decrypt(storedValue))
            } catch (_: Exception) {
                // Повреждённая сессия уже бесполезна. Пароль мы не знаем и подменять её не будем.
                brokenKeys += key
                null
            }
        }.sortedBy(StoredSession::createdAtMillis)

        if (brokenKeys.isNotEmpty()) {
            preferences.edit().also { editor ->
                brokenKeys.forEach(editor::remove)
            }.apply()
        }
        return sessions
    }

    /** Ищет одну сессию по внутреннему номеру аккаунта. */
    @Synchronized
    fun find(accountId: String): StoredSession? = loadAll().firstOrNull {
        it.accountId == accountId
    }

    /** Шифрует и сохраняет сессию; пароль в эту запись никогда не входит. */
    @Synchronized
    fun save(session: StoredSession) {
        try {
            preferences.edit()
                .putString(sessionKey(session.accountId), encrypt(encodeSession(session)))
                .apply()
        } catch (error: SessionStorageException) {
            throw error
        } catch (error: Exception) {
            throw SessionStorageException(error)
        }
    }

    /** Удаляет только запись указанного аккаунта. */
    @Synchronized
    fun delete(accountId: String) {
        preferences.edit().remove(sessionKey(accountId)).apply()
    }

    /** Шифрует текст новым случайным вектором и кладёт вектор рядом с шифром. */
    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(cipher.iv, encrypted).joinToString(PART_SEPARATOR) { bytes ->
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    /** Расшифровывает запись тем же системным ключом Android. */
    private fun decrypt(storedText: String): String {
        val parts = storedText.split(PART_SEPARATOR, limit = 2)
        require(parts.size == 2)
        val initializationVector = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector),
        )
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    /** Берёт существующий ключ или просит Android создать новый невынимаемый ключ. */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** Превращает поля сессии в строку перед шифрованием. */
    private fun encodeSession(session: StoredSession): String = JSONObject()
        .put("accountId", session.accountId)
        .put("title", session.title)
        .put("login", session.login)
        .put("accessToken", session.accessToken)
        .put("refreshToken", session.refreshToken ?: JSONObject.NULL)
        .put("accessTokenExpiresAtMillis", session.accessTokenExpiresAtMillis)
        .put("createdAtMillis", session.createdAtMillis)
        .toString()

    /** Собирает объект сессии из уже расшифрованной строки. */
    private fun decodeSession(jsonText: String): StoredSession {
        val json = JSONObject(jsonText)
        return StoredSession(
            accountId = json.getString("accountId"),
            title = json.getString("title"),
            login = json.getString("login"),
            accessToken = json.getString("accessToken"),
            refreshToken = if (json.isNull("refreshToken")) {
                null
            } else {
                json.optString("refreshToken").takeIf(String::isNotBlank)
            },
            accessTokenExpiresAtMillis = json.getLong("accessTokenExpiresAtMillis"),
            createdAtMillis = json.getLong("createdAtMillis"),
        )
    }

    /** Делает имя настройки безопасным даже для необычного серверного номера. */
    private fun sessionKey(accountId: String): String {
        val safeId = Base64.encodeToString(
            accountId.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return SESSION_PREFIX + safeId
    }

    private companion object {
        const val PREFERENCES_NAME = "protected_sessions"
        const val SESSION_PREFIX = "session."
        const val KEY_ALIAS = "lk.proptech.ru.sessions.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val PART_SEPARATOR = "."
    }
}
