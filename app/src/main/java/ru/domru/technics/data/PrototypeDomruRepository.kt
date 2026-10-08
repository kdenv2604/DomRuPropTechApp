package ru.domru.technics.data

import kotlinx.coroutines.delay
import ru.domru.technics.domain.AddressSearch
import ru.domru.technics.model.AccessSource
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.CameraStream
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.model.TemporalCode

/**
 * Временные данные нужны только для показа и проверки интерфейса.
 * Они не отправляют запросы и не открывают настоящие двери.
 */
class PrototypeDomruRepository : DomruRepository {
    private val kazan = Locality("demo-kazan", "г. Казань")
    private val zelenodolsk = Locality("demo-zelenodolsk", "г. Зеленодольск")

    private val accounts = listOf(
        AccountProfile("demo-main", "Домоуправление №7", "техническая служба", isDemo = true),
        AccountProfile("demo-east", "Аварийная служба", "дежурный аккаунт", isDemo = true),
    )

    private val streetsByAccount = mapOf(
        "demo-main" to listOf(
            Street("main-ador", "Адоратского", 3, locality = kazan),
            Street("main-chist", "Чистопольская", 2, locality = kazan),
            Street("main-hakim", "Сибгата Хакима", 1, locality = kazan),
        ),
        "demo-east" to listOf(
            Street("east-amir", "Фатыха Амирхана", 2, locality = zelenodolsk),
            Street("east-yam", "Ямашева", 1, locality = zelenodolsk),
        ),
    )

    private val housesByStreet = mapOf(
        "main-ador" to listOf(
            House("ador-7", "main-ador", "Дом 7", 2),
            House("ador-9", "main-ador", "Дом 9", 4),
            House("ador-11", "main-ador", "Дом 11", 3),
        ),
        "main-chist" to listOf(
            House("chist-3", "main-chist", "Дом 3", 2),
            House("chist-5", "main-chist", "Дом 5", 2),
        ),
        "main-hakim" to listOf(House("hakim-15", "main-hakim", "Дом 15", 3)),
        "east-amir" to listOf(
            House("amir-21", "east-amir", "Дом 21", 2),
            House("amir-23", "east-amir", "Дом 23", 3),
        ),
        "east-yam" to listOf(House("yam-44", "east-yam", "Дом 44", 4)),
    )

    private val entrancesByHouse: Map<String, List<Entrance>> = housesByStreet.values
        .flatten()
        .associate { house ->
            val count = house.entranceCount ?: 1
            house.id to (1..count).map { number ->
                Entrance(
                    id = "${house.id}-$number",
                    houseId = house.id,
                    label = "Подъезд $number",
                    cameraAvailable = number != 2,
                )
            }
        }

    override suspend fun signIn(login: String, password: String): AccountProfile {
        // Демо-вход не принимает настоящие данные. Им занимается сетевой репозиторий.
        throw DomruAuthenticationException("Демонстрационный вход не принимает логин")
    }

    override suspend fun signOut(accountId: String) {
        // У демо нет настоящей сессии. В рабочей версии здесь удалятся токены.
    }

    /** Даёт экрану заранее подготовленные аккаунты без секретных данных. */
    fun demoAccounts(): List<AccountProfile> = accounts

    override suspend fun loadStreets(accountId: String): List<Street> {
        pauseLikeNetwork()
        return streetsByAccount[accountId].orEmpty().map { street ->
            street.copy(sources = listOf(AccessSource(accountId, street.id)))
        }
    }

    override suspend fun loadHouses(accountId: String, streetId: String): List<House> {
        pauseLikeNetwork()
        return housesByStreet[streetId].orEmpty().map { house ->
            house.copy(sources = listOf(AccessSource(accountId, house.id)))
        }
    }

    override suspend fun loadEntrances(accountId: String, houseId: String): List<Entrance> {
        pauseLikeNetwork()
        return entrancesByHouse[houseId].orEmpty().map { entrance ->
            entrance.copy(sources = listOf(AccessSource(accountId, entrance.id, canVideo = entrance.cameraAvailable)))
        }
    }

    override suspend fun search(accountId: String, query: String): List<AddressSearchResult> {
        pauseLikeNetwork(short = true)
        val streets = streetsByAccount[accountId].orEmpty()
        return streets.flatMap { street ->
            housesByStreet[street.id].orEmpty().mapNotNull { house ->
                if (AddressSearch.matches(street.locality.name, street.name, house.label, query)) {
                    AddressSearchResult(
                        street.copy(sources = listOf(AccessSource(accountId, street.id))),
                        house.copy(sources = listOf(AccessSource(accountId, house.id))),
                        "${street.locality.name} · ${street.name} · ${house.label}",
                    )
                } else {
                    null
                }
            }
        }.take(12)
    }

    override suspend fun openDoor(accountId: String, entranceId: String): DoorOpenResult {
        // В демо ждём чуть-чуть, чтобы было видно состояние кнопки «Открываем…».
        delay(1_100)
        return DoorOpenResult.Confirmed
    }

    override suspend fun getTemporalCode(accountId: String, entranceId: String): TemporalCode? {
        pauseLikeNetwork()
        val digits = entranceId.hashCode().toUInt().toString().takeLast(5).padStart(5, '4')
        return TemporalCode(
            value = digits,
            accessControlId = entranceId,
            updatedAtEpochMillis = System.currentTimeMillis(),
            validUntilEpochMillis = System.currentTimeMillis() + 10 * 60 * 1_000L,
        )
    }

    override suspend fun getCameraStream(accountId: String, entranceId: String): CameraStream? {
        pauseLikeNetwork()
        // У демонстрационных дверей нет настоящих камер и сетевых ссылок.
        return null
    }

    /** Маленькая задержка помогает увидеть загрузку и проверить блокировку кнопок. */
    private suspend fun pauseLikeNetwork(short: Boolean = false) {
        delay(if (short) 140 else 320)
    }
}
