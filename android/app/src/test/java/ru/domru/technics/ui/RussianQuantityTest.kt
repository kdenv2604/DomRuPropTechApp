package ru.domru.technics.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianQuantityTest {
    @Test
    fun `выбирает правильное окончание для обычных чисел`() {
        assertEquals("1 дом", russianQuantity(1, "дом", "дома", "домов"))
        assertEquals("2 дома", russianQuantity(2, "дом", "дома", "домов"))
        assertEquals("5 домов", russianQuantity(5, "дом", "дома", "домов"))
    }

    @Test
    fun `числа от одиннадцати до четырнадцати используют много`() {
        assertEquals("11 домов", russianQuantity(11, "дом", "дома", "домов"))
        assertEquals("22 дома", russianQuantity(22, "дом", "дома", "домов"))
        assertEquals("114 домов", russianQuantity(114, "дом", "дома", "домов"))
    }
}
