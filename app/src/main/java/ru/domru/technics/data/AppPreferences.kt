package ru.domru.technics.data

import android.content.Context
import ru.domru.technics.model.AccordionSelection
import ru.domru.technics.model.ThemeMode
import ru.domru.technics.model.Handedness

/**
 * Хранит только безобидные настройки экрана.
 * Секретные токены и пароли в это обычное хранилище класть нельзя.
 */
class AppPreferences(context: Context) {
    private val storage = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Возвращает тему из настроек или системную тему при первом запуске. */
    fun loadThemeMode(): ThemeMode {
        val savedName = storage.getString(THEME_KEY, null) ?: return ThemeMode.SYSTEM
        return ThemeMode.entries.firstOrNull { it.name == savedName } ?: ThemeMode.SYSTEM
    }

    /** Запоминает выбранную человеком тему. */
    fun saveThemeMode(mode: ThemeMode) {
        storage.edit().putString(THEME_KEY, mode.name).apply()
    }

    /** Старые установки без этой настройки используют правую руку. */
    fun loadHandedness(): Handedness {
        val savedName = storage.getString(HANDEDNESS_KEY, null)
        return Handedness.entries.firstOrNull { it.name == savedName } ?: Handedness.RIGHT
    }

    /** Настройка руки остаётся после перезапуска и обновления приложения. */
    fun saveHandedness(handedness: Handedness) {
        storage.edit().putString(HANDEDNESS_KEY, handedness.name).apply()
    }

    /** Запоминает раскрытые адреса. Ссылки камеры и другие временные данные сюда не попадают. */
    fun saveAddressSelection(selection: AccordionSelection) {
        storage.edit()
            // Населённый пункт сохраняем отдельно, потому что теперь он раскрывается первым.
            .putString(LOCALITY_KEY, selection.localityId)
            .putString(STREET_KEY, selection.streetId)
            .putString(HOUSE_KEY, selection.houseId)
            .putString(ENTRANCE_KEY, selection.entranceId)
            .apply()
    }

    /** Читает последнюю раскрытую ветку; отсутствующие старые поля останутся пустыми. */
    fun loadAddressSelection(): AccordionSelection = AccordionSelection(
        streetId = storage.getString(STREET_KEY, null),
        houseId = storage.getString(HOUSE_KEY, null),
        entranceId = storage.getString(ENTRANCE_KEY, null),
        localityId = storage.getString(LOCALITY_KEY, null),
    )

    private companion object {
        const val FILE_NAME = "display_preferences"
        const val THEME_KEY = "theme_mode"
        const val HANDEDNESS_KEY = "handedness"
        const val LOCALITY_KEY = "opened_locality_id"
        const val STREET_KEY = "opened_street_id"
        const val HOUSE_KEY = "opened_house_id"
        const val ENTRANCE_KEY = "opened_entrance_id"
    }
}
