package ru.domru.technics.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.domru.technics.model.UNKNOWN_LOCALITY_NAME

class RemoteCatalogBuilderTest {
    private val specification = PortalSpecification("company-access", "company", "г. Казань")

    @Test
    fun preservesAddressLabelsDevicePermissionsAndCommandIdentifiers() {
        val catalog = catalog(
            houses = listOf(house("12")),
            devices = listOf(device("door", "2.17.12.porch-3.", cameraAvailable = false)),
            porches = listOf(PortalPorch("porch-3", "3", "2.17.12.porch-3.", "2.17.12.")),
        )

        val street = catalog.streets.single()
        val house = catalog.housesByStreetId.getValue(street.id).single()
        val entrance = catalog.entrancesByHouseId.getValue(house.id).single()
        assertEquals("г. Казань", street.locality.name)
        assertEquals("ул. Тестовая", street.name)
        assertEquals(1, street.houseCount)
        assertEquals("Дом 12", house.label)
        assertEquals(1, house.entranceCount)
        assertEquals("Подъезд 3", entrance.label)
        assertEquals("company-access::house::12", house.id)
        assertEquals("company-access::door::door", entrance.id)
        assertEquals("door", entrance.deviceIdentity)
        assertFalse(entrance.cameraAvailable)
        assertEquals(
            RemoteDoor("company-access", "door", "lock-door", cameraAvailable = false),
            catalog.doorsByEntranceId.getValue(entrance.id),
        )
    }

    @Test
    fun groupsHousesOnOneStreetAndPrefersLocalityFromAddress() {
        val catalog = catalog(
            houses = listOf(
                house("12").copy(address = "г. Самара, ул. Тестовая, д. 12"),
                house("14").copy(address = "г. Самара, ул. Тестовая, д. 14"),
            ),
            devices = emptyList(),
        )

        assertEquals("г. Самара", catalog.streets.single().locality.name)
        assertEquals(2, catalog.streets.single().houseCount)
        assertEquals(listOf("Дом 12", "Дом 14"), catalog.housesByStreetId.values.single().map { it.label })
    }

    @Test
    fun keepsHouseRouteAliasWhenItDiffersFromThePortalHouseId() {
        val catalog = catalog(
            houses = listOf(house("portal-id", route = "2.17.route-id.")),
            devices = listOf(device("door", "2.17.route-id.1.")),
        )

        assertEquals(listOf("door"), catalog.entrancesByHouseId.getValue("company-access::house::portal-id")
            .map { it.deviceIdentity })
    }

    @Test
    fun malformedHouseAndDeviceRoutesDoNotMatchThroughNull() {
        val catalog = catalog(
            houses = listOf(house("12", route = "")),
            devices = listOf(device("unknown", "unrecognized")),
        )

        assertTrue(catalog.entrancesByHouseId.getValue("company-access::house::12").isEmpty())
        assertEquals("unknown", catalog.entrancesByHouseId.getValue("company-access::house::other")
            .single().deviceIdentity)
    }

    @Test
    fun blankParentRoutesDoNotGiveTheDeviceAnUnrelatedPorchLabel() {
        val catalog = catalog(
            houses = listOf(house("12", route = "")),
            devices = listOf(device("door", "2.17.12.1.")),
            porches = listOf(PortalPorch("1", "99", "unrecognized", "")),
        )

        assertEquals("Домофон door", catalog.entrancesByHouseId.getValue("company-access::house::12")
            .single().label)
    }

    @Test
    fun missingHouseSegmentDoesNotAttachTheDeviceToAnUnrelatedHouse() {
        val catalog = catalog(
            houses = listOf(house("12")),
            devices = listOf(device("unknown", "2.17..12.")),
        )

        assertTrue(catalog.entrancesByHouseId.getValue("company-access::house::12").isEmpty())
        assertEquals("unknown", catalog.entrancesByHouseId.getValue("company-access::house::other")
            .single().deviceIdentity)
    }

    @Test
    fun houseNumberPrefixDoesNotAttachTheDeviceToTwoHouses() {
        val catalog = catalog(
            houses = listOf(house("12", route = "2.17.12"), house("123")),
            devices = listOf(device("door-123", "2.17.123.1.")),
        )

        assertTrue(catalog.entrancesByHouseId.getValue("company-access::house::12").isEmpty())
        assertEquals("door-123", catalog.entrancesByHouseId.getValue("company-access::house::123")
            .single().deviceIdentity)
        assertEquals(1, catalog.entrancesByHouseId.values.sumOf { it.size })
    }

    @Test
    fun keepsLegacyRoutePrefixOnlyAtACompleteSegmentBoundary() {
        val catalog = catalog(
            houses = listOf(house("12", route = "legacy.house12")),
            devices = listOf(device("matching", "legacy.house12.porch1"), device("other", "legacy.house123.porch1")),
        )

        assertEquals("matching", catalog.entrancesByHouseId.getValue("company-access::house::12")
            .single().deviceIdentity)
        assertEquals("other", catalog.entrancesByHouseId.getValue("company-access::house::other")
            .single().deviceIdentity)
    }

    @Test
    fun conflictingHouseIdentifiersLeaveTheDeviceUnassigned() {
        val catalog = catalog(
            houses = listOf(house("12"), house("different", route = "2.17.12.")),
            devices = listOf(device("ambiguous", "2.17.12.1.")),
        )

        assertTrue(catalog.entrancesByHouseId.getValue("company-access::house::12").isEmpty())
        assertTrue(catalog.entrancesByHouseId.getValue("company-access::house::different").isEmpty())
        assertEquals("ambiguous", catalog.entrancesByHouseId.getValue("company-access::house::other")
            .single().deviceIdentity)
    }

    @Test
    fun devicesWithoutAnyHousesRemainAvailableWithTheirOriginalNames() {
        val catalog = catalog(houses = emptyList(), devices = listOf(device("gate", "2.17.99.1.")))

        val street = catalog.streets.single()
        val house = catalog.housesByStreetId.getValue(street.id).single()
        val entrance = catalog.entrancesByHouseId.getValue(house.id).single()
        assertEquals("Другие объекты", street.name)
        assertEquals(UNKNOWN_LOCALITY_NAME, street.locality.name)
        assertEquals("Без адресной привязки", house.label)
        assertEquals("Домофон gate", entrance.label)
        assertEquals("lock-gate", catalog.doorsByEntranceId.getValue(entrance.id).accessControlId)
    }

    @Test
    fun separatesCompaniesAndRetainsIncompleteCatalogStatus() {
        val builder = RemoteCatalogBuilder()
        for (id in listOf("first", "second")) {
            builder.addSpecification(
                specification.copy(id = id), "г. Казань", listOf(house("12")), emptyList(),
                listOf(device("door", "2.17.12.1.")),
            )
        }
        val catalog = builder.build(complete = false)

        assertFalse(catalog.complete)
        assertEquals(2, catalog.streets.size)
        assertEquals(setOf("first::house::12", "second::house::12"), catalog.entrancesByHouseId.keys)
        assertEquals(setOf("first", "second"), catalog.doorsByEntranceId.values.map { it.specificationId }.toSet())
    }

    @Test
    fun emptyCompanyProducesACompleteEmptyCatalog() {
        val catalog = catalog(houses = emptyList(), devices = emptyList())

        assertTrue(catalog.complete)
        assertTrue(catalog.streets.isEmpty())
        assertTrue(catalog.doorsByEntranceId.isEmpty())
    }

    private fun catalog(
        houses: List<PortalHouse>,
        devices: List<PortalDevice>,
        porches: List<PortalPorch> = emptyList(),
    ): RemoteCatalog = RemoteCatalogBuilder().apply {
        addSpecification(specification, specification.localityName, houses, porches, devices)
    }.build(complete = true)

    private fun house(id: String, route: String = "2.17.$id.") =
        PortalHouse(id, id, "ул. Тестовая, д. $id", route)

    private fun device(id: String, route: String, cameraAvailable: Boolean = true) =
        PortalDevice(id, "lock-$id", "Домофон $id", route, cameraAvailable)
}
