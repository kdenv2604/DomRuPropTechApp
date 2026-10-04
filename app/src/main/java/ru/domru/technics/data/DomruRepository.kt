package ru.domru.technics.data

import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.CameraStream
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Street
import ru.domru.technics.model.TemporalCode

/** Это общий список действий. Экран не знает, по каким адресам работает сервер. */
interface DomruRepository {
    /** Проверяет логин и пароль и возвращает безопасную карточку аккаунта. */
    suspend fun signIn(login: String, password: String): AccountProfile
    /** Завершает серверную сессию и удаляет её с телефона. */
    suspend fun signOut(accountId: String)
    /** Загружает все улицы одного аккаунта. */
    suspend fun loadStreets(accountId: String): List<Street>
    /** Загружает дома выбранной серверной улицы. */
    suspend fun loadHouses(accountId: String, streetId: String): List<House>
    /** Загружает подъезды и двери выбранного дома. */
    suspend fun loadEntrances(accountId: String, houseId: String): List<Entrance>
    /** Ищет адреса внутри одного аккаунта. */
    suspend fun search(accountId: String, query: String): List<AddressSearchResult>
    /** Посылает одну команду открытия выбранной двери. */
    suspend fun openDoor(accountId: String, entranceId: String): DoorOpenResult
    /** Читает действующий код, ничего не меняя на сервере. */
    suspend fun getTemporalCode(accountId: String, entranceId: String): TemporalCode?
    /** Получает временную ссылку на видео выбранного домофона. */
    suspend fun getCameraStream(accountId: String, entranceId: String): CameraStream?
}

/** Помогает отличить неверный пароль от временно пропавшего сервера. */
enum class AuthenticationFailure {
    INVALID_CREDENTIALS,
    SESSION_EXPIRED,
    PASSWORD_NOT_STORED,
    CLIENT_REJECTED,
    SERVICE_UNAVAILABLE,
}

/** Сервер не принял данные для входа. Технические ответы сервера наружу не показываем. */
class DomruAuthenticationException(
    message: String,
    val failure: AuthenticationFailure = AuthenticationFailure.SERVICE_UNAVAILABLE,
) : IllegalStateException(message)

/** Рабочую сессию нельзя случайно заменить новым паролем. */
class ActiveSessionPasswordProtectedException : IllegalStateException(
    "Доступ уже действует. Новый пароль не нужен",
)

/** Пароль нельзя спрашивать, пока причина сбоя доступа точно не установлена. */
class PasswordRenewalUnavailableException(message: String) : IllegalStateException(message)

/** Защищённое хранилище телефона не смогло сохранить или прочитать сессию. */
class SessionStorageException(cause: Throwable? = null) : IllegalStateException(
    "Телефон не смог сохранить защищённую сессию",
    cause,
)

/** Сервер ответил ошибкой, которую можно безопасно показать без его внутренних данных. */
class PortalRequestException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

/** Ответ пришёл, но обязательных полей в нём не оказалось. */
class PortalProtocolException(message: String) : IllegalStateException(message)
