package ru.domru.technics.ui

/**
 * Подбирает правильное русское окончание.
 * Например: «1 дом», «2 дома», «5 домов».
 */
fun russianQuantity(value: Int, one: String, few: String, many: String): String {
    val positive = kotlin.math.abs(value.toLong())
    val lastTwoDigits = positive % 100
    val word = when {
        lastTwoDigits in 11..14 -> many
        positive % 10 == 1L -> one
        positive % 10 in 2..4 -> few
        else -> many
    }
    return "$value $word"
}
