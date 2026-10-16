package ru.domru.technics.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.domru.technics.data.DomruRepository
import ru.domru.technics.data.PrototypeDomruRepository
import ru.domru.technics.model.AccountAccessStatus
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import java.util.UUID

/** Проверяет запоздалые результаты после смены аккаунтов без настоящего сервера и сессий. */
@RunWith(AndroidJUnit4::class)
class AppViewModelAccountCheckTest {
    private lateinit var application: IsolatedApplication
    private lateinit var repository: DelayedAccountRepository
    private lateinit var viewModel: AppViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() = runScenario {
        application = IsolatedApplication(ApplicationProvider.getApplicationContext())
        repository = DelayedAccountRepository()
        onMain {
            viewModel = AppViewModel(application, repository)
            store = ViewModelStore().also { it.put("accounts", viewModel) }
        }
    }

    @After
    fun tearDown() = runScenario {
        if (::viewModel.isInitialized) {
            val scopeJob = viewModel.viewModelScope.coroutineContext.job
            onMain { store.clear() }
            repository.releasePendingChecks()
            scopeJob.join()
        }
        if (::application.isInitialized) application.clearPreferences()
    }

    @Test
    fun oldBatchCannotRevokeNewLoginAndRemainingAccountsFinishChecking() = runScenario {
        val initialChecks = List(2) { repository.checkRequests.receive() }.associateBy { it.accountId }
        initialChecks.getValue("account-a").reply(AccountAccessStatus.NEEDS_PASSWORD)
        assertEquals(AccountAccessStatus.CHECKING, viewModel.state.value.accountStatuses["account-a"])

        onMain { viewModel.addLoginAndPassword("new-login", "test-password") }
        val repeatedCheck = repository.checkRequests.receive()
        assertEquals("account-b", repeatedCheck.accountId)
        val afterLogin = viewModel.state.first {
            !it.streetsLoading && it.streets.size == 1 && !it.loginBusy
        }
        assertEquals(AccountAccessStatus.ACTIVE, afterLogin.accountStatuses["account-a"])
        assertEquals(AccountAccessStatus.CHECKING, afterLogin.accountStatuses["account-b"])
        assertEquals(1, repository.checkedAccounts.count { it == "account-a" })
        assertEquals(2, repository.checkedAccounts.count { it == "account-b" })

        initialChecks.getValue("account-b").reply(AccountAccessStatus.UNAVAILABLE)
        val afterOldReply = viewModel.state.value
        assertEquals(AccountAccessStatus.ACTIVE, afterOldReply.accountStatuses["account-a"])
        assertEquals(AccountAccessStatus.CHECKING, afterOldReply.accountStatuses["account-b"])
        assertEquals(afterLogin.streets, afterOldReply.streets)
        assertFalse(afterOldReply.streetsLoading)

        repeatedCheck.reply(AccountAccessStatus.ACTIVE)
        val completed = viewModel.state.first { !it.streetsLoading && it.streets.size == 2 }
        assertEquals(AccountAccessStatus.ACTIVE, completed.accountStatuses["account-a"])
        assertEquals(AccountAccessStatus.ACTIVE, completed.accountStatuses["account-b"])
        assertFalse(AccountAccessStatus.CHECKING in completed.accountStatuses.values)
    }

    @Test
    fun switchingToDemoDiscardsOldAccountChecksWithoutRestartingThem() = runScenario {
        val initialChecks = List(2) { repository.checkRequests.receive() }
        onMain { viewModel.openDemo() }
        val demoState = viewModel.state.first { !it.streetsLoading && it.streets.size == 2 }
        assertTrue(demoState.accounts.all(AccountProfile::isDemo))

        initialChecks.forEach { it.reply(AccountAccessStatus.UNAVAILABLE) }

        val completed = viewModel.state.value
        assertEquals(demoState.streets, completed.streets)
        assertTrue(completed.accounts.all(AccountProfile::isDemo))
        assertEquals(completed.accounts.map(AccountProfile::id).toSet(), completed.accountStatuses.keys)
        assertTrue(completed.accountStatuses.values.all { it == AccountAccessStatus.ACTIVE })
        assertEquals(2, repository.checkedAccounts.size)
        assertTrue(repository.checkRequests.tryReceive().isFailure)
    }

    private fun runScenario(block: suspend () -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private class DelayedAccountRepository : DomruRepository by PrototypeDomruRepository() {
        val checkRequests = Channel<DelayedCheck>(Channel.UNLIMITED)
        val checkedAccounts = mutableListOf<String>()
        private val pending = mutableListOf<DelayedCheck>()
        private val accounts = listOf(
            AccountProfile("account-a", "Аккаунт A", "login-a", hasSavedPassword = true),
            AccountProfile("account-b", "Аккаунт B", "login-b", hasSavedPassword = true),
        )

        override fun savedAccounts(): List<AccountProfile> = accounts

        override suspend fun checkAccess(accountId: String): AccountAccessStatus {
            checkedAccounts += accountId
            val request = DelayedCheck(accountId, currentCoroutineContext().job)
            pending += request
            checkRequests.send(request)
            return request.await()
        }

        override suspend fun signIn(login: String, password: String): AccountProfile = accounts.first().copy(
            title = "Новая карточка A",
        )

        override suspend fun loadStreets(accountId: String): List<Street> = listOf(
            Street("remote-$accountId", "Улица $accountId", locality = Locality("test-city", "Тестовый город")),
        )

        suspend fun releasePendingChecks() = withContext(Dispatchers.Main.immediate) {
            pending.forEach { it.cancelWait() }
        }
    }

    private class DelayedCheck(val accountId: String, private val requestJob: Job) {
        private val response = CompletableDeferred<AccountAccessStatus>()

        suspend fun await(): AccountAccessStatus = withContext(NonCancellable) { response.await() }

        suspend fun reply(status: AccountAccessStatus) {
            withContext(Dispatchers.Main.immediate) { response.complete(status) }
            requestJob.join()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        fun cancelWait() {
            response.completeExceptionally(CancellationException("Test finished"))
        }
    }

    private class IsolatedApplication(context: Context) : Application() {
        private val prefix = "account_check_test_${UUID.randomUUID()}_"
        private val preferenceFiles = mutableSetOf<String>()

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceFiles += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun clearPreferences() {
            preferenceFiles.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }
}
