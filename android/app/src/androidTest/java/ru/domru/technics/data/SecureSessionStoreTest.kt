package ru.domru.technics.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Проверяет настоящий Keystore на вымышленных данных в отдельном файле настроек. */
@RunWith(AndroidJUnit4::class)
class SecureSessionStoreTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "review-test-protected-sessions"
    private val context = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            appContext.getSharedPreferences(preferenceName, mode)
    }
    private val preferences: SharedPreferences
        get() = appContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    @Before
    fun prepareStorage() {
        appContext.deleteSharedPreferences(preferenceName)
    }

    @After
    fun removeTestStorage() {
        appContext.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun encryptedSessionSurvivesStoreRecreation() {
        val session = session("first")
        SecureSessionStore(context).save(session)
        preferences.edit().commit()

        assertEquals(session, SecureSessionStore(context).find(session.accountId))
        val stored = preferences.all.values.single() as String
        assertFalse(stored.contains(session.accessToken))
        assertFalse(stored.contains(session.password!!))
    }

    @Test
    fun unreadableRecordIsRetainedAndDoesNotHideReadableSessions() {
        val session = session("working")
        val store = SecureSessionStore(context)
        store.save(session)
        val unreadable = "not-an-encrypted-session"
        preferences.edit().putString("session.unreadable", unreadable).commit()

        assertEquals(listOf(session), store.loadAll())
        assertEquals(unreadable, preferences.getString("session.unreadable", null))
        assertEquals(session, SecureSessionStore(context).find(session.accountId))
        assertEquals(unreadable, preferences.getString("session.unreadable", null))
    }

    @Test
    fun explicitDeletionRemovesOnlyTheSelectedAccount() {
        val store = SecureSessionStore(context)
        store.save(session("first"))
        store.save(session("second"))

        store.delete("first")

        assertNull(store.find("first"))
        assertEquals(listOf(session("second")), store.loadAll())
        assertTrue(preferences.all.size == 1)
    }

    @Test
    fun conditionalWriteAndDeletionKeepNewerSession() {
        val store = SecureSessionStore(context)
        val original = session("first")
        val newer = original.copy(accessToken = "new-access", password = "new-password")
        store.save(original)
        store.save(newer)

        assertFalse(store.saveIfUnchanged(original, original.copy(accessToken = "late-access")))
        assertFalse(store.deleteIfUnchanged(original))

        assertEquals(newer, SecureSessionStore(context).find(original.accountId))
    }

    @Test
    fun conditionalWriteAndDeletionApplyToUnchangedSession() {
        val store = SecureSessionStore(context)
        val original = session("first")
        val refreshed = original.copy(accessToken = "refreshed-access")
        store.save(original)
        store.save(session("second"))

        assertTrue(store.saveIfUnchanged(original, refreshed))
        assertEquals(refreshed, store.find(original.accountId))
        assertTrue(store.deleteIfUnchanged(refreshed))

        assertEquals(listOf(session("second")), SecureSessionStore(context).loadAll())
    }

    @Test
    fun conditionalWriteDoesNotRestoreDeletedSession() {
        val store = SecureSessionStore(context)
        val original = session("first")
        store.save(original)
        store.delete(original.accountId)

        assertFalse(store.saveIfUnchanged(original, original.copy(accessToken = "late-access")))
        assertFalse(store.deleteIfUnchanged(original))

        assertNull(SecureSessionStore(context).find(original.accountId))
    }

    private fun session(id: String) = StoredSession(
        accountId = id,
        title = "Тестовый аккаунт",
        login = "test-$id",
        accessToken = "fake-access-token-$id",
        refreshToken = "fake-refresh-token-$id",
        password = "fake-password-$id",
        accessTokenExpiresAtMillis = 1_000_000L,
        createdAtMillis = 100L,
    )
}
