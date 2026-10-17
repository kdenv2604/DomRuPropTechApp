package ru.domru.technics.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseRouteTest {
    @Test
    fun `маршрут портала содержит дом и подъезд`() {
        val route = HouseRoute.decode("2.17.house-42.porch-3.")

        assertEquals("house-42", route?.houseId)
        assertEquals("porch-3", route?.porch)
    }

    @Test
    fun `непонятный маршрут не принимается за дверь`() {
        assertNull(HouseRoute.decode("что-то другое"))
    }

    @Test
    fun `пустой сегмент не сдвигает дом и подъезд`() {
        listOf(
            "2.17..12.",
            "2..house-42.porch-3.",
            "2.17.house-42..porch-3.",
            "2.17. .porch-3.",
            ".2.17.house-42.porch-3.",
        ).forEach { value ->
            assertNull(value, HouseRoute.decode(value))
        }
    }

    @Test
    fun `завершающая точка не меняет корректный маршрут`() {
        listOf("2.17.house-42.porch-3", "2.17.house-42.porch-3.").forEach { value ->
            assertEquals(value, HouseRoute("house-42", "porch-3"), HouseRoute.decode(value))
        }
    }

    @Test
    fun `маршрут самого дома не требует подъезда`() {
        assertEquals(HouseRoute("house-42", null), HouseRoute.decode("2.17.house-42."))
    }

    @Test
    fun `дополнительный уровень не меняет дом и подъезд`() {
        assertEquals(
            HouseRoute("house-42", "porch-3"),
            HouseRoute.decode("2.17.house-42.porch-3.device."),
        )
    }
}
