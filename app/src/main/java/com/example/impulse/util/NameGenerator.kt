package com.example.impulse.util

import java.util.Random

/**
 * Generates a friendly random display name for chat clients.
 * Shared between the UI (MainScreen) and the connection service so the
 * same logic is not duplicated.
 */
object NameGenerator {
    private val adjectives = arrayOf(
        "Веселый", "Умный", "Быстрый", "Смелый", "Добрый",
        "Ловкий", "Внимательный", "Энергичный", "Креативный", "Надежный"
    )
    private val nouns = arrayOf(
        "Пользователь", "Клиент", "Участник", "Чаттер", "Гость",
        "Посетитель", "Собеседник", "Диалогист"
    )

    fun generate(): String {
        val random = Random()
        val adjective = adjectives[random.nextInt(adjectives.size)]
        val noun = nouns[random.nextInt(nouns.size)]
        val number = random.nextInt(100)
        return "$adjective$noun$number"
    }
}
