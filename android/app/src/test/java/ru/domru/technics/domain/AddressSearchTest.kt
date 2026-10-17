package ru.domru.technics.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSearchTest {
    @Test
    fun abbreviatedStreetAndHouseMatch() {
        assertTrue(AddressSearch.matches("Адоратского", "Дом 9", "Адо 9"))
    }

    @Test
    fun unrelatedAddressDoesNotMatch() {
        assertFalse(AddressSearch.matches("Чистопольская", "Дом 3", "Адо 9"))
    }

    @Test
    fun localityNameParticipatesInSearch() {
        // Человек может найти все доступные дома, просто введя название города.
        assertTrue(AddressSearch.matches("Зеленодольск", "Центральная", "Дом 3", "Зелен"))
    }
}
