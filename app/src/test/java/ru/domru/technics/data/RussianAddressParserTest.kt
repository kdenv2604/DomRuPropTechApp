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
}
