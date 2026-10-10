package ru.domru.technics.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.domru.technics.model.Handedness

/** A dedicated preference file avoids replacing the user's real display preferences. */
@RunWith(AndroidJUnit4::class)
class HandednessPreferencesTest {
    @Test
    fun previousInstallDefaultsToRightAndExplicitSelectionSurvivesPreferencesRecreation() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "handedness-test-display_preferences"
        val context = object : ContextWrapper(appContext) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                appContext.getSharedPreferences(name, mode)
        }
        appContext.deleteSharedPreferences(name)
        try {
            assertEquals(Handedness.RIGHT, AppPreferences(context).loadHandedness())
            AppPreferences(context).saveHandedness(Handedness.LEFT)
            // commit waits for preceding apply writes before recreating the application preference owner.
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
            assertEquals(Handedness.LEFT, AppPreferences(context).loadHandedness())
            AppPreferences(context).saveHandedness(Handedness.RIGHT)
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
            assertEquals(Handedness.RIGHT, AppPreferences(context).loadHandedness())
        } finally {
            appContext.deleteSharedPreferences(name)
        }
    }
}
