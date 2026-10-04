package ru.domru.technics.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.CameraStream
import ru.domru.technics.model.TemporalCode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Делает только подтверждённые запросы служебного портала. */
internal class ProptechApiClient(
    private val sessionProvider: DomruSessionProvider,
) {
    /** Узнаёт, к каким служебным компаниям допущен аккаунт. */
    suspend fun loadSpecifications(accountId: String): List<PortalSpecification> {
        val profile = getObject(
            accountId = accountId,
            url = "$PUBLIC_SERVICE/accounts/v1/accounts/me",
        )
        val specifications = profile.optJSONArray("specifications") ?: JSONArray()
        return (0 until specifications.length()).mapNotNull { index ->
            val item = specifications.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("_id", "id")
            val company = item.optJSONObject("company")
            val companyId = item.text("companyId").ifBlank {
                company?.text("_id", "id").orEmpty()
            }
            val localityName = item.text("locationName", "cityName").ifBlank {
                company?.text("locationName", "cityName").orEmpty()
            }.takeIf(String::isNotBlank)
            if (id.isBlank() || companyId.isBlank()) {
                null
            } else {
                PortalSpecification(id, companyId, localityName)
            }
        }.distinctBy { it.id }
    }

    /** Читает город или посёлок из карточки компании, если адрес дома его не содержит. */
    suspend fun loadCompanyLocality(
        accountId: String,
        specification: PortalSpecification,
    ): String? {
        val company = getObject(
            accountId = accountId,
            specificationId = specification.id,
            url = "$PUBLIC_SERVICE/companies/v1/companies/${path(specification.companyId)}",
        )
        val nestedLocation = company.optJSONObject("location")
        return company.text("locationName", "cityName").ifBlank {
            nestedLocation?.text("name", "value", "label").orEmpty()
        }.takeIf(String::isNotBlank)
    }

    /** Загружает все дома компании постранично, чтобы не потерять длинный список. */
    suspend fun loadHouses(
        accountId: String,
        specification: PortalSpecification,
    ): List<PortalHouse> = loadPagedItems(
        accountId = accountId,
        specificationId = specification.id,
        urlForPage = { skip, limit ->
            "$PUBLIC_SERVICE/houses/v1/companies/${path(specification.companyId)}/houses" +
                "?skip=$skip&limit=$limit&sort=address"
        },
    ).mapNotNull { item ->
        val id = item.text("_id", "id")
        val houseId = item.text("houseId").ifBlank { id }
        val address = item.text("address")
        if (id.isBlank() || houseId.isBlank() || address.isBlank()) null else PortalHouse(
            id = id,
            houseId = houseId,
            address = address,
            route = item.text("route"),
        )
    }

    /** Загружает подъезды и их служебные маршруты. */
    suspend fun loadPorches(
        accountId: String,
        specification: PortalSpecification,
    ): List<PortalPorch> = loadPagedItems(
        accountId = accountId,
        specificationId = specification.id,
        urlForPage = { skip, limit ->
            "$PUBLIC_SERVICE/houses/v1/companies/${path(specification.companyId)}/porches" +
                "?skip=$skip&limit=$limit"
        },
    ).mapNotNull { item ->
        val porchId = item.text("porchId", "_id", "id")
        if (porchId.isBlank()) null else PortalPorch(
            porchId = porchId,
            number = item.text("number").ifBlank { porchId },
            route = item.text("route"),
            parentRoute = item.text("parentRoute"),
        )
    }

    /** Загружает домофоны, идентификаторы замков и признак доступного видео. */
    suspend fun loadIntercomDevices(
        accountId: String,
        specification: PortalSpecification,
    ): List<PortalDevice> {
        val response = getArray(
            accountId = accountId,
            specificationId = specification.id,
            url = "$PUBLIC_SERVICE/devices/v2/devices/intercom" +
                "?companyId=${query(specification.companyId)}",
        )
        return (0 until response.length()).mapNotNull { index ->
            val item = response.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("_id", "id")
            val accessControlId = item.text("accessControlId")
            val route = item.optJSONArray("routes")?.optString(0).orEmpty()
            if (id.isBlank() || accessControlId.isBlank() || route.isBlank()) null else PortalDevice(
                id = id,
                accessControlId = accessControlId,
                name = item.text("name").ifBlank { "Домофон" },
                route = route,
                cameraAvailable = item.optJSONObject("meta")?.optBoolean("allowVideo") == true,
            )
        }
    }

    /** Отправляет единственную команду открытия и проверяет подтверждение сервера. */
    suspend fun openDoor(
        accountId: String,
        door: RemoteDoor,
    ): DoorOpenResult {
        val response = request(
            accountId = accountId,
            specificationId = door.specificationId,
            method = "POST",
            url = "$PUBLIC_SERVICE/devices/v2/devices/${path(door.deviceId)}/open",
            body = JSONObject().put("source", "objects").toString(),
        )
        if (response.code !in 200..299) {
            return DoorOpenResult.Rejected(response.safeErrorMessage("Сервер отказал в открытии"))
        }
        val status = runCatching { JSONObject(response.body).opt("status") }.getOrNull()
        val confirmed = when (status) {
            is Boolean -> status
            is Number -> status.toInt() != 0
            is String -> status.isNotBlank() && !status.equals("false", ignoreCase = true)
            else -> false
        }
        return if (confirmed) DoorOpenResult.Confirmed
        else DoorOpenResult.Rejected("Сервер не подтвердил открытие")
    }

    /** Читает уже существующий код доступа и срок его действия. */
    suspend fun getCode(
        accountId: String,
        door: RemoteDoor,
    ): TemporalCode? {
        val response = request(
            accountId = accountId,
            specificationId = door.specificationId,
            method = "GET",
            url = "$BFF_SERVICE/v1/web/codes/${path(door.accessControlId)}",
        )
        if (response.code == 404) return null
        if (response.code !in 200..299) {
            throw PortalRequestException(
                response.code,
                response.safeErrorMessage("Код для этой двери недоступен"),
            )
        }
        val json = runCatching { JSONObject(response.body) }.getOrElse {
            throw PortalProtocolException("Сервер вернул непонятный ответ с кодом")
        }
        val code = json.opt("code")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val installedAt = parseInstant(json.text("installationTime"))
        val validUntil = parseInstant(json.optJSONObject("group")?.text("refreshTime").orEmpty())
            ?: calculatePeriodEnd(installedAt, json.text("period"))
        return TemporalCode(
            value = code,
            accessControlId = door.accessControlId,
            updatedAtEpochMillis = installedAt,
            validUntilEpochMillis = validUntil,
        )
    }

    /** Просит у сервера короткоживущую ссылку на видеопоток. */
    suspend fun getCameraStream(
        accountId: String,
        door: RemoteDoor,
    ): CameraStream {
        val response = request(
            accountId = accountId,
            specificationId = door.specificationId,
            method = "GET",
            url = "$PUBLIC_SERVICE/devices/v2/devices/${path(door.deviceId)}/camera/video",
        )
        if (response.code !in 200..299) throw response.toException()
        val json = runCatching { JSONObject(response.body) }.getOrElse {
            throw PortalProtocolException("Сервер вернул непонятный ответ камеры")
        }
        val streamUrl = json.text("streamUrl")
        if (streamUrl.isBlank()) {
            throw PortalProtocolException("Сервер не дал ссылку на видеопоток")
        }
        return CameraStream(
            streamUrl = streamUrl,
            previewUrl = json.text("previewUrl").takeIf(String::isNotBlank),
        )
    }

    /** Последовательно читает страницы, пока сервер не сообщит, что элементы закончились. */
    private suspend fun loadPagedItems(
        accountId: String,
        specificationId: String,
        urlForPage: (skip: Int, limit: Int) -> String,
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var total = Int.MAX_VALUE
        var page = 0
        while (result.size < total && page < MAX_PAGE_COUNT) {
            val response = getObject(
                accountId = accountId,
                specificationId = specificationId,
                url = urlForPage(result.size, PAGE_SIZE),
            )
            val items = response.optJSONArray("items") ?: JSONArray()
            total = response.optInt("total", items.length())
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(result::add)
            }
            if (items.length() == 0) break
            page += 1
        }
        return result
    }

    /** Выполняет GET и требует, чтобы ответ был JSON-объектом. */
    private suspend fun getObject(
        accountId: String,
        url: String,
        specificationId: String = "",
    ): JSONObject {
        val response = request(accountId, specificationId, "GET", url)
        if (response.code !in 200..299) throw response.toException()
        return runCatching { JSONObject(response.body) }.getOrElse {
            throw PortalProtocolException("Сервер вернул непонятный список")
        }
    }

    /** Выполняет GET и требует, чтобы ответ был JSON-массивом. */
    private suspend fun getArray(
        accountId: String,
        url: String,
        specificationId: String = "",
    ): JSONArray {
        val response = request(accountId, specificationId, "GET", url)
        if (response.code !in 200..299) throw response.toException()
        return runCatching { JSONArray(response.body) }.getOrElse {
            throw PortalProtocolException("Сервер вернул непонятный список устройств")
        }
    }

    /**
     * Выполняет запрос и один раз повторяет безопасное чтение после полноценного автовхода.
     * Команду открытия двери здесь не повторяем: первый POST теоретически мог сработать.
     */
    private suspend fun request(
        accountId: String,
        specificationId: String,
        method: String,
        url: String,
        body: String? = null,
    ): PortalResponse {
        val firstToken = sessionProvider.accessToken(accountId)
        val firstResponse = executeRequest(
            token = firstToken,
            specificationId = specificationId,
            method = method,
            url = url,
            body = body,
        )
        if (firstResponse.code != 401 || method != "GET") return firstResponse

        // Новый вход сам ограничен тремя попытками. Запрос GET после него повторяется один раз.
        val recoveredToken = sessionProvider.recoverAfterUnauthorized(accountId, firstToken)
        return executeRequest(
            token = recoveredToken,
            specificationId = specificationId,
            method = method,
            url = url,
            body = body,
        )
    }

    /** Собирает один HTTP-запрос, подставляет токен и всегда закрывает соединение. */
    private suspend fun executeRequest(
        token: String,
        specificationId: String,
        method: String,
        url: String,
        body: String?,
    ): PortalResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            if (specificationId.isNotBlank()) {
                setRequestProperty("x-specification-id", specificationId)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            PortalResponse(
                code = code,
                body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Переводит технический код HTTP в короткое человеческое сообщение. */
    private fun PortalResponse.toException(): PortalRequestException = PortalRequestException(
        statusCode = code,
        message = when (code) {
            401 -> "Сессия закончилась. Войди в аккаунт снова"
            403 -> "У аккаунта нет права смотреть эти объекты"
            404 -> "Сервер не нашёл запрошенные объекты"
            else -> "Сервер портала ответил с ошибкой $code"
        },
    )

    /** Показывает только заранее известные ошибки, не выдавая внутренний ответ сервера. */
    private fun PortalResponse.safeErrorMessage(fallback: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return when (json?.optString("errorCode")) {
            "CODE_NOT_ALLOWED_ON_NEW_DEVICE" -> "Код пока запрещён для нового устройства"
            "CODE_EMPTY" -> "Код для устройства не найден"
            else -> fallback
        }
    }

    /** Берёт первое непустое поле из нескольких вариантов имени. */
    private fun JSONObject.text(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        optString(name).takeIf { it.isNotBlank() && it != "null" }
    }.orEmpty()

    /** Превращает серверное время в миллисекунды, а плохое значение спокойно пропускает. */
    private fun parseInstant(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()

    /** Вычисляет конец периода вида P10D, если сервер не прислал готовую дату. */
    private fun calculatePeriodEnd(startMillis: Long?, period: String): Long? {
        val days = Regex("^P(\\d+)D$").matchEntire(period)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        return startMillis?.let { Instant.ofEpochMilli(it).plus(days, ChronoUnit.DAYS).toEpochMilli() }
    }

    /** Кодирует один кусочек пути так, чтобы пробел не превратился в плюс. */
    private fun path(value: String): String = query(value).replace("+", "%20")

    /** Кодирует значение для безопасной передачи в адресной строке. */
    private fun query(value: String): String = URLEncoder.encode(
        value,
        StandardCharsets.UTF_8.name(),
    )

    /** Код ответа и его текст без привязки к Android-интерфейсу. */
    private data class PortalResponse(val code: Int, val body: String)

    private companion object {
        const val PUBLIC_SERVICE = "https://public.proptech.ru"
        const val BFF_SERVICE = "https://bff.lk.proptech.ru"
        const val NETWORK_TIMEOUT_MILLIS = 15_000
        const val PAGE_SIZE = 300
        const val MAX_PAGE_COUNT = 100
    }
}
