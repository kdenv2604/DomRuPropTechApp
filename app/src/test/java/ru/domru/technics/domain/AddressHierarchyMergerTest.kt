package ru.domru.technics.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.domru.technics.model.AccessSource
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street

class AddressHierarchyMergerTest {
    @Test
    fun sameStreetFromTwoAccountsBecomesOneStreetWithTwoSources() {
        // Два аккаунта видят одну улицу, поэтому на экране не должно быть дубля.
        val result = AddressHierarchyMerger.streets(
            listOf(
                Street("remote-a", "Адоратского", sources = listOf(AccessSource("a", "remote-a"))),
                Street("remote-b", "адоратского", sources = listOf(AccessSource("b", "remote-b"))),
            ),
        )

        assertEquals(1, result.size)
        assertEquals(2, result.single().sources.size)
    }

    @Test
    fun sameStreetNameInDifferentCitiesStaysSeparate() {
        // Одинаковые улицы в разных городах не должны превратиться в одну карточку.
        val result = AddressHierarchyMerger.streets(
            listOf(
                Street("a", "Центральная", locality = Locality("k", "Казань")),
                Street("b", "Центральная", locality = Locality("z", "Зеленодольск")),
            ),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun cityPrefixDoesNotCreateDuplicateLocality() {
        // Адрес дома и карточка компании могут написать один город немного по-разному.
        val result = AddressHierarchyMerger.streets(
            listOf(
                Street("a", "Адоратского", locality = Locality("a-k", "г. Казань")),
                Street("b", "Адоратского", locality = Locality("b-k", "Казань")),
            ),
        )

        assertEquals(1, result.size)
    }
}
