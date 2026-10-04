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
}
