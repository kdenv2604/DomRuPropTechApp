package ru.domru.technics.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccordionSelectionTest {
    @Test
    fun switchingStreetClosesHouseAndEntrance() {
        val current = AccordionSelection(
            streetId = "street-a",
            houseId = "house-a",
            entranceId = "entrance-a",
            localityId = "city-a",
        )
        val next = current.toggleStreet("city-b", "street-b")
        assertEquals("city-b", next.localityId)
        assertEquals("street-b", next.streetId)
        assertNull(next.houseId)
        assertNull(next.entranceId)
    }

    @Test
    fun switchingHouseClosesEntrance() {
        val current = AccordionSelection(
            streetId = "street-a",
            houseId = "house-a",
            entranceId = "entrance-a",
            localityId = "city-a",
        )
        val next = current.toggleHouse("city-a", "street-a", "house-b")
        assertEquals("house-b", next.houseId)
        assertNull(next.entranceId)
    }

    @Test
    fun switchingLocalityClosesTheWholeOldBranch() {
        val current = AccordionSelection(
            streetId = "street-a",
            houseId = "house-a",
            entranceId = "entrance-a",
            localityId = "city-a",
        )

        val next = current.toggleLocality("city-b")

        assertEquals("city-b", next.localityId)
        assertNull(next.streetId)
        assertNull(next.houseId)
        assertNull(next.entranceId)
    }

    @Test
    fun streetOnlyRestorationStillLoadsHouses() {
        val saved = AccordionSelection(streetId = "street-a")
        assertEquals(true, saved.mustLoadHouses())
        assertEquals(false, saved.mustLoadEntrances())
    }

    @Test
    fun houseRestorationStillLoadsEntrances() {
        val saved = AccordionSelection(streetId = "street-a", houseId = "house-a")
        assertEquals(true, saved.mustLoadHouses())
        assertEquals(true, saved.mustLoadEntrances())
    }
}
