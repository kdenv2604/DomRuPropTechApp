package ru.domru.technics.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormEncodingTest {
    @Test
    fun `логин и пароль безопасно превращаются в поля формы`() {
        // Пробел, плюс и русские буквы нельзя отправлять как простой текст.
        val encoded = encodeForm(
            linkedMapOf(
                "username" to "тех ник",
                "password" to "a+b&c",
            ),
        )

        assertEquals(
            "username=%D1%82%D0%B5%D1%85+%D0%BD%D0%B8%D0%BA&password=a%2Bb%26c",
            encoded,
        )
    }
}
