package ru.domru.technics.data

/** Читает служебный маршрут дома. Например, из него можно узнать дом и подъезд. */
internal data class HouseRoute(
    val houseId: String,
    val porch: String?,
) {
    companion object {
        /** Разбирает строку портала; непонятную строку не угадывает и возвращает null. */
        fun decode(value: String): HouseRoute? {
            // Пустой сегмент внутри пути нельзя удалять: иначе номер подъезда станет номером дома.
            val parts = value.trimEnd('.').split('.')
            if (parts.size < 3 || parts.first() !in setOf("1", "2") || parts.any(String::isBlank)) {
                return null
            }
            return HouseRoute(
                houseId = parts[2],
                porch = parts.getOrNull(3),
            )
        }
    }
}
