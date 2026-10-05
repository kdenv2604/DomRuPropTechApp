package ru.domru.technics.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.domru.technics.model.AccordionSelection
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.ui.AppUiState

/** Проверяет короткую подпись отдельно от Android-экрана. */
class AddressRowsTest {
    @Test
    fun `слово подъезд сокращается только в начале подписи`() {
        assertEquals("Под. 12", "Подъезд 12".toCompactEntranceLabel())
        assertEquals("Калитка у подъезда", "Калитка у подъезда".toCompactEntranceLabel())
        assertEquals("Подъезд 12", "Под. 12".toExpandedEntranceLabel())
    }

    @Test
    fun `раскрытый город показывает только свои улицы`() {
        val kazan = Locality("kazan", "Казань")
        val perm = Locality("perm", "Пермь")
        val state = AppUiState(
            streets = listOf(
                Street("k-1", "Центральная", locality = kazan),
                Street("p-1", "Центральная", locality = perm),
            ),
            selection = AccordionSelection(localityId = kazan.id),
        )

        val rows = buildVisibleAddressRows(state)

        assertEquals(2, rows.count { it is AddressListItem.LocalityRow })
        assertEquals(1, rows.count { it is AddressListItem.StreetRow })
        assertEquals(
            "k-1",
            (rows.first { it is AddressListItem.StreetRow } as AddressListItem.StreetRow).street.id,
        )
    }

    @Test
    fun `раскрытый подъезд остаётся одной карточкой списка`() {
        val locality = Locality("kazan", "Казань")
        val street = Street("street-1", "Адоратского", locality = locality)
        val house = House("house-1", street.id, "Дом 7")
        val entrance = Entrance("entrance-1", house.id, "Подъезд 7", cameraAvailable = true)
        val state = AppUiState(
            streets = listOf(street),
            housesByStreet = mapOf(street.id to listOf(house)),
            entrancesByHouse = mapOf(house.id to listOf(entrance)),
            selection = AccordionSelection(
                localityId = locality.id,
                streetId = street.id,
                houseId = house.id,
                entranceId = entrance.id,
            ),
        )

        val rows = buildVisibleAddressRows(state)

        assertEquals(1, rows.count { it is AddressListItem.EntranceRow })
        assertEquals(4, rows.size)
        assertEquals("entrance:${entrance.id}", rows.last().key)
    }
}
