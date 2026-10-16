package ru.domru.technics.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianAddressParserTest {
    @Test
    fun `город улица и дом становятся отдельными ступенями`() {
        val parts = RussianAddressParser.parse("Казань, улица Примерная, дом 12, корпус 2")

        assertEquals("Казань", parts.locality)
        assertEquals("улица Примерная", parts.street)
        assertEquals("Дом 12, корпус 2", parts.house)
    }

    @Test
    fun `обычный последний номер превращается в подпись дома`() {
        val parts = RussianAddressParser.parse("Адоратского, 9")

        assertEquals(null, parts.locality)
        assertEquals("Адоратского", parts.street)
        assertEquals("Дом 9", parts.house)
    }

    @Test
    fun `область не прилипает к названию города`() {
        val parts = RussianAddressParser.parse(
            "Республика Татарстан, г. Казань, ул. Адоратского, д. 9",
        )

        assertEquals("г. Казань", parts.locality)
        assertEquals("ул. Адоратского", parts.street)
        assertEquals("Дом 9", parts.house)
    }

    @Test
    fun `номер без префикса остаётся вместе с корпусом`() {
        listOf("12", "12А", "12/1", "12-1").forEach { number ->
            val parts = RussianAddressParser.parse("г. Казань, ул. Ленина, $number, корпус 1")

            assertEquals("г. Казань", parts.locality)
            assertEquals("ул. Ленина", parts.street)
            assertEquals("Дом $number, корпус 1", parts.house)
        }
    }

    @Test
    fun `номер с корпусом разбирается и без города`() {
        val parts = RussianAddressParser.parse("Ленина, 12, корпус 1")

        assertEquals(null, parts.locality)
        assertEquals("Ленина", parts.street)
        assertEquals("Дом 12, корпус 1", parts.house)
    }

    @Test
    fun `числа в названии улицы не становятся номером дома`() {
        listOf("8 Марта", "50 лет Октября", "улица 1905 года").forEach { street ->
            val parts = RussianAddressParser.parse("г. Казань, $street, 12, корпус 1")

            assertEquals("г. Казань", parts.locality)
            assertEquals(street, parts.street)
            assertEquals("Дом 12, корпус 1", parts.house)
        }
    }

    @Test
    fun `название улицы перед корпусом не считается номером`() {
        val parts = RussianAddressParser.parse("г. Казань, 8 Марта, корпус 1")

        assertEquals("г. Казань", parts.locality)
        assertEquals("8 Марта", parts.street)
        assertEquals("Дом корпус 1", parts.house)
    }
}
