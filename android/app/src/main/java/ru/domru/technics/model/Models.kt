package ru.domru.technics.model

/** Выбранный вид приложения. Первый вариант повторяет настройку самого телефона. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Выбранная рука определяет положение кнопки открытия; по умолчанию — правая. */
enum class Handedness { RIGHT, LEFT }

/** Короткая карточка аккаунта. Пароля и токенов здесь специально нет. */
data class AccountProfile(
    val id: String,
    val title: String,
    val loginHint: String,
    val isDemo: Boolean = false,
    val hasSavedPassword: Boolean = false,
)

/** Результат безопасной проверки сохранённой сессии. */
enum class AccountAccessStatus {
    CHECKING,
    ACTIVE,
    NEEDS_PASSWORD,
    ACCESS_DENIED,
    UNAVAILABLE,
}

/** Опасные действия разрешены только после подтверждённой потери авторизации. */
fun AccountAccessStatus.allowsAccountRecoveryActions(): Boolean =
    this == AccountAccessStatus.NEEDS_PASSWORD

/** Показывает, из какого аккаунта и какого серверного объекта пришёл доступ. */
data class AccessSource(
    val accountId: String,
    val remoteId: String,
    val canVideo: Boolean = true,
)

/** Населённый пункт — это отдельная ступень над улицами. */
data class Locality(
    val id: String,
    val name: String,
)

/** Улица знает свой населённый пункт и серверные источники доступа. */
data class Street(
    val id: String,
    val name: String,
    val houseCount: Int? = null,
    val sources: List<AccessSource> = emptyList(),
    val locality: Locality = Locality(
        id = UNKNOWN_LOCALITY_ID,
        name = UNKNOWN_LOCALITY_NAME,
    ),
)

/** Дом относится к одной улице и сообщает, сколько у него подъездов. */
data class House(
    val id: String,
    val streetId: String,
    val label: String,
    val entranceCount: Int? = null,
    val sources: List<AccessSource> = emptyList(),
)

/** Подъезд содержит только безопасные данные для экрана, а не секреты сервера. */
data class Entrance(
    val id: String,
    val houseId: String,
    val label: String,
    val cameraAvailable: Boolean,
    val deviceIdentity: String? = null,
    val sources: List<AccessSource> = emptyList(),
)

/** Найденный адрес хранит улицу и дом, которые надо раскрыть после нажатия. */
data class AddressSearchResult(
    val street: Street,
    val house: House,
    val subtitle: String,
)

/** Код двери и время его действия, если сервер сообщил это время. */
data class TemporalCode(
    val value: String,
    val accessControlId: String,
    val updatedAtEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
)

/** Временные ссылки на прямой эфир камеры. На диск они не сохраняются. */
data class CameraStream(
    val streamUrl: String,
    val previewUrl: String? = null,
    val mimeType: String? = null,
    val source: AccessSource? = null,
)

/** Состояние камеры одного раскрытого подъезда. */
sealed interface CameraState {
    /** Карточку ещё не открывали. */
    data object Idle : CameraState
    /** Приложение получает временную ссылку камеры. */
    data object Loading : CameraState
    /** Ссылка получена, видео можно проигрывать. */
    data class Ready(val stream: CameraStream, val playbackGeneration: Long = 0) : CameraState
    /** Видео получить не удалось; текст можно безопасно показать человеку. */
    data class Failed(val message: String, val retryAfterSeconds: Int? = null) : CameraState
}

/** Ответ сервера на единственную команду открытия двери. */
sealed interface DoorOpenResult {
    /** Сервер точно подтвердил выполнение команды. */
    data object Confirmed : DoorOpenResult
    /** Сервер точно отказал и объяснил причину. */
    data class Rejected(val message: String) : DoorOpenResult
    /** Ответ потерялся, поэтому повторять команду автоматически опасно. */
    data class Uncertain(val message: String) : DoorOpenResult
}

/** То, как кнопка открытия выглядит прямо сейчас. */
sealed interface DoorActionState {
    /** Кнопка готова к обычному нажатию. */
    data object Idle : DoorActionState
    /** Команда уже ушла, второе нажатие временно запрещено. */
    data object Sending : DoorActionState
    /** Дверь открыта, кнопка ненадолго стала зелёной. */
    data object Opened : DoorActionState
    /** Сервер отказал; человек может решить, повторять ли запрос. */
    data class Failed(val message: String) : DoorActionState
    /** Неизвестно, сработала ли команда; нужен ручной контроль. */
    data class Uncertain(val message: String) : DoorActionState
}

/** То, как кнопка получения кода выглядит прямо сейчас. */
sealed interface CodeActionState {
    /** Код пока не запрашивается. */
    data object Idle : CodeActionState
    /** Один запрос кода уже выполняется. */
    data object Loading : CodeActionState
    /** Код получить не удалось; текст объясняет причину. */
    data class Failed(val message: String) : CodeActionState
}

/**
 * Здесь записано, какие улица, дом и подъезд сейчас раскрыты.
 * У каждого уровня может быть только один выбранный пункт.
 */
data class AccordionSelection(
    val streetId: String? = null,
    val houseId: String? = null,
    val entranceId: String? = null,
    val localityId: String? = null,
) {
    /** Повторное нажатие закрывает населённый пункт и всю его ветку. */
    fun toggleLocality(id: String): AccordionSelection =
        if (localityId == id) AccordionSelection() else AccordionSelection(localityId = id)

    /** Раскрытая улица должна показать дома, даже когда конкретный дом ещё не выбран. */
    fun mustLoadHouses(): Boolean = streetId != null

    /** Раскрытый дом должен показать подъезды, даже когда карточка подъезда закрыта. */
    fun mustLoadEntrances(): Boolean = streetId != null && houseId != null

    /** Новая улица закрывает старый дом, но оставляет её населённый пункт раскрытым. */
    fun toggleStreet(parentLocalityId: String, id: String): AccordionSelection =
        if (localityId == parentLocalityId && streetId == id) {
            AccordionSelection(localityId = parentLocalityId)
        } else {
            AccordionSelection(streetId = id, localityId = parentLocalityId)
        }

    /** Новый дом всегда закрывает подъезд, который был открыт в старом доме. */
    fun toggleHouse(
        parentLocalityId: String,
        parentStreetId: String,
        id: String,
    ): AccordionSelection =
        if (localityId == parentLocalityId && streetId == parentStreetId && houseId == id) {
            AccordionSelection(streetId = parentStreetId, localityId = parentLocalityId)
        } else {
            AccordionSelection(
                streetId = parentStreetId,
                houseId = id,
                localityId = parentLocalityId,
            )
        }

    /** Новый подъезд заменяет предыдущий. Две камеры сразу запуститься не смогут. */
    fun toggleEntrance(
        parentLocalityId: String,
        parentStreetId: String,
        parentHouseId: String,
        id: String,
    ): AccordionSelection =
        if (
            localityId == parentLocalityId && streetId == parentStreetId &&
            houseId == parentHouseId && entranceId == id
        ) {
            AccordionSelection(
                streetId = parentStreetId,
                houseId = parentHouseId,
                localityId = parentLocalityId,
            )
        } else {
            AccordionSelection(
                streetId = parentStreetId,
                houseId = parentHouseId,
                entranceId = id,
                localityId = parentLocalityId,
            )
        }
}

/** Так подписываем адрес, если сервер прислал улицу без города или посёлка. */
const val UNKNOWN_LOCALITY_NAME = "Населённый пункт не указан"
const val UNKNOWN_LOCALITY_ID = "locality:unknown"
