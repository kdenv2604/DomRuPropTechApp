package ru.domru.technics.data

/** Отделяет название улицы от номера дома, не меняя написание адреса сервера. */
internal object RussianAddressParser {
    /** Три готовые подписи для трёх уровней адресного дерева. */
    data class Parts(
        val locality: String?,
        val street: String,
        val house: String,
    )

    /** Разбирает полный адрес, но сохраняет исходное написание каждой части. */
    fun parse(address: String): Parts {
        val cleanAddress = address.trim().ifBlank { "Адрес не указан" }
        val chunks = cleanAddress.split(',').map(String::trim).filter(String::isNotBlank)
        if (chunks.size >= 2) {
            val houseStart = chunks.indexOfFirst(::looksLikeHousePart).takeIf { it > 0 }
                ?: chunks.lastIndex
            val addressChunks = chunks.take(houseStart)
            val streetStart = findStreetStart(addressChunks)
            val locality = findLocality(addressChunks, streetStart)
            val street = addressChunks.drop(streetStart).joinToString(", ")
                .ifBlank { addressChunks.lastOrNull() ?: chunks.first() }
            val house = chunks.drop(houseStart).joinToString(", ").toHouseLabel()
            return Parts(locality, street, house)
        }

        val match = ONE_LINE_HOUSE.find(cleanAddress)
        if (match != null && match.groupValues[1].isNotBlank()) {
            return Parts(
                locality = null,
                street = match.groupValues[1].trim().trimEnd(','),
                house = match.groupValues[2].trim().toHouseLabel(),
            )
        }
        return Parts(locality = null, street = cleanAddress, house = "Дом")
    }

    /** Ищет начало улицы, чтобы стоящий перед ней город стал отдельным уровнем. */
    private fun findStreetStart(chunks: List<String>): Int {
        if (chunks.size <= 1) return 0
        val explicitStreet = chunks.indexOfFirst(STREET_PREFIX::containsMatchIn)
        return if (explicitStreet >= 0) explicitStreet else chunks.lastIndex
    }

    /** Берём ближайший к улице город или посёлок, а область в подпись не добавляем. */
    private fun findLocality(chunks: List<String>, streetStart: Int): String? {
        if (streetStart <= 0) return null
        return chunks.take(streetStart).lastOrNull(LOCALITY_PREFIX::containsMatchIn)
            ?: chunks.getOrNull(streetStart - 1)
    }

    /** Проверяет, начинается ли кусочек с номера дома или владения. */
    private fun looksLikeHousePart(value: String): Boolean = HOUSE_PREFIX.containsMatchIn(value)

    /** Делает единообразную подпись «Дом 9» из «9», «д. 9» или «дом 9». */
    private fun String.toHouseLabel(): String {
        val value = trim()
        return when {
            value.startsWith("д.", ignoreCase = true) ->
                "Дом " + value.drop(2).trimStart()
            value.startsWith("дом", ignoreCase = true) ->
                value.replaceFirst(Regex("(?i)^дом\\s*"), "Дом ")
            else -> "Дом $value"
        }
    }

    private val HOUSE_PREFIX = Regex("(?iu)^(д\\.?|дом|владение|корпус)\\s*\\d")
    private val STREET_PREFIX = Regex(
        "(?iu)^(ул\\.?|улица|проспект|пр\\.?-?т|переулок|пер\\.?|бульвар|" +
            "бул\\.?|шоссе|набережная|наб\\.?|площадь|пл\\.?|проезд|тракт|" +
            "аллея|микрорайон|мкр\\.?)\\s+",
    )
    private val LOCALITY_PREFIX = Regex(
        "(?iu)^(г\\.?|город|с\\.?|село|п\\.?|пос\\.?|пос[её]лок|пгт\\.?|" +
            "дер\\.?|деревня|станица|аул)\\s+",
    )
    private val ONE_LINE_HOUSE = Regex(
        "(?iu)^(.*?)(д\\.?\\s*\\d+[А-Яа-яA-Za-z0-9/\\-]*(?:\\s*,?.*)?)$",
    )
}
