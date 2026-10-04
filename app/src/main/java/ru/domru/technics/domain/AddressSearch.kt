package ru.domru.technics.domain

import java.util.Locale

/** Сравнивает адреса без учёта регистра, знаков препинания и буквы «ё». */
object AddressSearch {
    /** Разбиваем запрос на кусочки. Поэтому «Адо 9» найдёт «Адоратского, дом 9». */
    fun matches(street: String, house: String, query: String): Boolean {
        return matches(locality = "", street = street, house = house, query = query)
    }

    /** Город или посёлок участвует в поиске наравне с улицей и домом. */
    fun matches(locality: String, street: String, house: String, query: String): Boolean {
        val tokens = canonical(query).split(' ').filter(String::isNotBlank)
        if (tokens.isEmpty()) return true
        val localityValue = canonical(locality)
        val streetValue = canonical(street)
        val houseValue = canonical(house)
        return tokens.all { token ->
            localityValue.split(' ').any { it.startsWith(token) } ||
                streetValue.split(' ').any { it.startsWith(token) } ||
                houseValue.split(' ').any { it.startsWith(token) }
        }
    }

    // Приводим разные варианты написания к одному простому виду.
    fun canonical(value: String): String = value
        .lowercase(Locale("ru"))
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9]+"), " ")
        .trim()

    /** Убирает служебное слово «город» или «посёлок», но оставляет само название. */
    fun canonicalLocality(value: String): String {
        val simple = canonical(value)
        return simple.replace(
            Regex("^(г|город|с|село|п|пос|поселок|пгт|дер|деревня|станица|аул)\\s+"),
            "",
        )
    }
}
