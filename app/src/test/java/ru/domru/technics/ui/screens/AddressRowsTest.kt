package ru.domru.technics.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.domru.technics.model.AccordionSelection
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.ui.AppUiState

/** Проверяет короткую подпись отдельно от Android-экрана. */
class AddressRowsTest {
    @Test
    fun `слово подъезд сокращается только в начале подписи`() {
        assertEquals("Под. 12", "Подъезд 12".toCompactEntranceLabel())
        assertEquals("Калитка у подъезда", "Калитка у подъезда".toCompactEntranceLabel())
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
}
