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
import ru.domru.technics.data.AppPreferences
import ru.domru.technics.data.DomruRepository
import ru.domru.technics.data.PrototypeDomruRepository
import ru.domru.technics.model.AccordionSelection
import ru.domru.technics.model.AccessSource
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.CodeActionState
import ru.domru.technics.model.DoorActionState
import ru.domru.technics.model.DoorOpenResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.model.TemporalCode
import java.util.UUID

/** Удерживает старый ответ, пока новая загрузка не достигнет проверяемого состояния. */
@RunWith(AndroidJUnit4::class)
class AppViewModelAddressLoadingTest {
    private lateinit var application: IsolatedApplication
    private lateinit var repository: DelayedAddressRepository
    private lateinit var viewModel: AppViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() = runScenario {
        application = IsolatedApplication(ApplicationProvider.getApplicationContext())
        repository = DelayedAddressRepository()
        onMain {
            viewModel = AppViewModel(application, repository)
            store = ViewModelStore().also { it.put("addresses", viewModel) }
            viewModel.openDemo()
        }
        loadedStreet()
    }

    @After
    fun tearDown() = runScenario {
        if (::viewModel.isInitialized) {
            val scopeJob = viewModel.viewModelScope.coroutineContext.job
            onMain { store.clear() }
            repository.releasePendingRequests()
            scopeJob.join()
        }
        if (::application.isInitialized) application.clearPreferences()
    }

    @Test
    fun lateHousesCannotReplaceTheRefreshedHouses() = runScenario {
        val street = loadedStreet()
        onMain { viewModel.toggleStreet(street) }
        val oldRequest = repository.houseRequests.receive()

        val refreshedStreet = refreshAddresses()
        onMain { viewModel.toggleStreet(refreshedStreet) }
        val newRequest = repository.houseRequests.receive()
        newRequest.reply(listOf(rawHouse("Дом 2")))
        viewModel.state.first { it.housesByStreet[street.id]?.singleOrNull()?.label == "Дом 2" }

        oldRequest.reply(listOf(rawHouse("Дом 1")))
        assertEquals(listOf("Дом 2"), viewModel.state.value.housesByStreet[street.id]?.map(House::label))
        assertFalse(street.id in viewModel.state.value.loadingStreetIds)
    }

    @Test
    fun lateHouseSuccessCannotFinishTheReplacementRequest() = runScenario {
        checkPendingHouseRequest(oldRequestFails = false)
    }

    @Test
    fun lateHouseFailureCannotFinishTheReplacementRequest() = runScenario {
        checkPendingHouseRequest(oldRequestFails = true)
    }

    @Test
    fun lateEntrancesCannotReplaceTheRefreshedEntrances() = runScenario {
        val street = loadedStreet()
        val house = loadHouse(street)
        onMain { viewModel.toggleHouse(street.id, house) }
        val oldRequest = repository.entranceRequests.receive()

        val refreshedStreet = refreshAddresses()
        val refreshedHouse = loadHouse(refreshedStreet)
        onMain { viewModel.toggleHouse(refreshedStreet.id, refreshedHouse) }
        val newRequest = repository.entranceRequests.receive()
        newRequest.reply(listOf(rawEntrance("Подъезд 2")))
        viewModel.state.first { it.entrancesByHouse[house.id]?.singleOrNull()?.label == "Подъезд 2" }

        oldRequest.reply(listOf(rawEntrance("Подъезд 1")))
        assertEquals(listOf("Подъезд 2"), viewModel.state.value.entrancesByHouse[house.id]?.map(Entrance::label))
        assertFalse(house.id in viewModel.state.value.loadingHouseIds)
    }

    @Test
    fun lateEntranceSuccessCannotFinishTheReplacementRequest() = runScenario {
        checkPendingEntranceRequest(oldRequestFails = false)
    }

    @Test
    fun lateEntranceFailureCannotFinishTheReplacementRequest() = runScenario {
        checkPendingEntranceRequest(oldRequestFails = true)
    }

    @Test
    fun lateSearchSelectionCannotRestoreOrSaveTheDiscardedAddress() = runScenario {
        val street = loadedStreet()
        val result = AddressSearchResult(street, rawHouse("Дом 1"), "Тестовый адрес")
        onMain { viewModel.openSearchResult(result) }
        repository.houseRequests.receive().reply(listOf(rawHouse("Дом 1")))
        val oldEntrances = repository.entranceRequests.receive()

        refreshAddresses()
        oldEntrances.reply(listOf(rawEntrance("Подъезд 1")))

        val current = viewModel.state.value
        assertEquals(AccordionSelection(), current.selection)
        assertTrue(current.housesByStreet.isEmpty())
        assertTrue(current.entrancesByHouse.isEmpty())
        assertEquals(AccordionSelection(), AppPreferences(application).loadAddressSelection())
    }

    @Test
    fun lateSavedBranchCannotRefillTheClearedCatalog() = runScenario {
        val street = loadedStreet()
        onMain {
            AppPreferences(application).saveAddressSelection(
                AccordionSelection(streetId = street.id, localityId = street.locality.id),
            )
            viewModel.refreshAddresses()
        }
        val restoredHouses = repository.houseRequests.receive()

        refreshAddresses()
        restoredHouses.reply(listOf(rawHouse("Дом 1")))

        assertEquals(AccordionSelection(), viewModel.state.value.selection)
        assertTrue(viewModel.state.value.housesByStreet.isEmpty())
        assertTrue(viewModel.state.value.entrancesByHouse.isEmpty())
    }

    @Test
    fun lateSearchResultCannotOverrideManualSelectionOrItsHouseLoad() = runScenario {
        val street = loadedStreet()
        onMain {
            viewModel.openSearchResult(AddressSearchResult(street, rawHouse("Дом 1"), "Тестовый адрес"))
        }
        repository.houseRequests.receive().reply(listOf(rawHouse("Дом 1")))
        val searchEntrances = repository.entranceRequests.receive()

        onMain { viewModel.toggleLocality(street.locality) }
        val manualHouseLoad = repository.houseRequests.receive()
        val manualSelection = viewModel.state.value.selection
        searchEntrances.reply(listOf(rawEntrance("Подъезд 1")))

        assertEquals(manualSelection, viewModel.state.value.selection)
        assertEquals(manualSelection, AppPreferences(application).loadAddressSelection())
        assertTrue(street.id in viewModel.state.value.loadingStreetIds)
        manualHouseLoad.reply(listOf(rawHouse("Дом 2")))
        val completed = viewModel.state.first { street.id !in it.loadingStreetIds }
        assertEquals(listOf("Дом 2"), completed.housesByStreet[street.id]?.map(House::label))
        assertEquals(manualSelection, completed.selection)
    }

    @Test
    fun delayedStreetListCannotDetachTheNewerSearchSelectionJob() = runScenario {
        val street = loadedStreet()
        onMain {
            repository.delayStreetResponses = true
            viewModel.refreshAddresses()
        }
        val streetRequest = repository.streetRequests.receive()
        onMain {
            viewModel.openSearchResult(AddressSearchResult(street, rawHouse("Дом 1"), "Тестовый адрес"))
        }
        val searchHouses = repository.houseRequests.receive()
        onMain { repository.delayStreetResponses = false }
        streetRequest.reply(listOf(repository.rawStreet))

        onMain { viewModel.toggleLocality(street.locality) }
        val manualHouseLoad = repository.houseRequests.receive()
        val manualSelection = viewModel.state.value.selection
        searchHouses.reply(listOf(rawHouse("Дом 1")))

        assertTrue(repository.entranceRequests.tryReceive().isFailure)
        assertEquals(manualSelection, viewModel.state.value.selection)
        assertEquals(manualSelection, AppPreferences(application).loadAddressSelection())
        manualHouseLoad.reply(listOf(rawHouse("Дом 2")))
        val completed = viewModel.state.first { street.id !in it.loadingStreetIds }
        assertEquals(listOf("Дом 2"), completed.housesByStreet[street.id]?.map(House::label))
    }

    @Test
    fun lateSavedHouseCannotReopenTheCollapsedLocalityOrCancelItsHouseLoad() = runScenario {
        val street = loadedStreet()
        val house = loadHouse(street)
        onMain {
            AppPreferences(application).saveAddressSelection(
                AccordionSelection(streetId = street.id, houseId = house.id, localityId = street.locality.id),
            )
            viewModel.refreshAddresses()
        }
        val restoredHouses = repository.houseRequests.receive()

        onMain { viewModel.toggleStreet(street) }
        val manualHouseLoad = repository.houseRequests.receive()
        onMain { viewModel.toggleLocality(street.locality) }
        assertEquals(AccordionSelection(), viewModel.state.value.selection)
        restoredHouses.reply(listOf(rawHouse("Дом 1")))

        assertEquals(AccordionSelection(), viewModel.state.value.selection)
        assertEquals(AccordionSelection(), AppPreferences(application).loadAddressSelection())
        assertTrue(street.id in viewModel.state.value.loadingStreetIds)
        manualHouseLoad.reply(listOf(rawHouse("Дом 2")))
        val completed = viewModel.state.first { street.id !in it.loadingStreetIds }
        assertEquals(listOf("Дом 2"), completed.housesByStreet[street.id]?.map(House::label))
        assertEquals(AccordionSelection(), completed.selection)
    }

    @Test
    fun lateSavedEntranceCannotReopenTheCollapsedHouse() = runScenario {
        val entrance = openEntrance()
        val street = loadedStreet()
        val house = viewModel.state.value.housesByStreet.getValue(street.id).single()
        onMain { viewModel.refreshAddresses() }
        repository.houseRequests.receive().reply(listOf(rawHouse("Дом 1")))
        val restoredEntrances = repository.entranceRequests.receive()

        onMain { viewModel.toggleHouse(street.id, house) }
        val manualSelection = viewModel.state.value.selection
        assertEquals(null, manualSelection.entranceId)
        assertEquals(null, manualSelection.houseId)
        restoredEntrances.reply(listOf(rawEntrance(entrance.label)))

        assertEquals(manualSelection, viewModel.state.value.selection)
        assertEquals(manualSelection, AppPreferences(application).loadAddressSelection())
    }

    @Test
    fun lateCodeCannotAppearAfterTheEntranceWasClosed() = runScenario {
        val entrance = openEntrance()
        onMain { viewModel.requestTemporalCode(entrance) }
        val codeRequest = repository.codeRequests.receive()
        onMain { viewModel.toggleEntrance(viewModel.state.value.selection.streetId!!, entrance.houseId, entrance) }
        codeRequest.reply(TemporalCode("1111", "test-access"))

        assertEquals(null, viewModel.state.value.shownCode)
        assertFalse(viewModel.state.value.codeActions[entrance.id] == CodeActionState.Loading)
    }

    @Test
    fun lateCodeCannotAppearAfterTheAppWasBackgrounded() = runScenario {
        val entrance = openEntrance()
        onMain {
            viewModel.onAppForegrounded()
            viewModel.requestTemporalCode(entrance)
        }
        val codeRequest = repository.codeRequests.receive()
        onMain { viewModel.onAppBackgrounded() }
        codeRequest.reply(TemporalCode("1111", "test-access"))
        onMain { viewModel.onAppForegrounded() }

        assertEquals(null, viewModel.state.value.shownCode)
        assertFalse(viewModel.state.value.codeActions[entrance.id] == CodeActionState.Loading)
    }

    @Test
    fun lateCodeCannotAppearAfterTheCatalogWasRefreshed() = runScenario {
        val entrance = openEntrance()
        onMain { viewModel.requestTemporalCode(entrance) }
        val codeRequest = repository.codeRequests.receive()
        refreshAddresses()
        codeRequest.reply(TemporalCode("1111", "test-access"))

        assertEquals(null, viewModel.state.value.shownCode)
        assertFalse(viewModel.state.value.codeActions[entrance.id] == CodeActionState.Loading)
    }

    @Test
    fun cancelledCodeCannotClearTheNextRequestForTheSameEntrance() = runScenario {
        val entrance = openEntrance()
        val streetId = viewModel.state.value.selection.streetId!!
        onMain { viewModel.requestTemporalCode(entrance) }
        val oldRequest = repository.codeRequests.receive()
        onMain {
            viewModel.toggleEntrance(streetId, entrance.houseId, entrance)
            viewModel.toggleEntrance(streetId, entrance.houseId, entrance)
            viewModel.requestTemporalCode(entrance)
        }
        val newRequest = repository.codeRequests.receive()
        oldRequest.reply(TemporalCode("1111", "test-access"))

        assertEquals(null, viewModel.state.value.shownCode)
        assertEquals(CodeActionState.Loading, viewModel.state.value.codeActions[entrance.id])
        newRequest.reply(TemporalCode("2222", "test-access"))
        assertEquals("2222", viewModel.state.value.shownCode?.value)
        assertFalse(viewModel.state.value.codeActions[entrance.id] == CodeActionState.Loading)
    }

    @Test
    fun cancelledCodeFailureCannotClearTheNextRequestForTheSameEntrance() = runScenario {
        val entrance = openEntrance()
        val streetId = viewModel.state.value.selection.streetId!!
        onMain { viewModel.requestTemporalCode(entrance) }
        val oldRequest = repository.codeRequests.receive()
        onMain {
            viewModel.toggleEntrance(streetId, entrance.houseId, entrance)
            viewModel.toggleEntrance(streetId, entrance.houseId, entrance)
            viewModel.requestTemporalCode(entrance)
        }
        val newRequest = repository.codeRequests.receive()
        oldRequest.fail()

        assertEquals(null, viewModel.state.value.shownCode)
        assertEquals(CodeActionState.Loading, viewModel.state.value.codeActions[entrance.id])
        newRequest.reply(TemporalCode("2222", "test-access"))
        assertEquals("2222", viewModel.state.value.shownCode?.value)
        assertFalse(viewModel.state.value.codeActions[entrance.id] == CodeActionState.Loading)
    }

    @Test
    fun openingLoginClosesThePreviouslyShownCode() = runScenario {
        val entrance = openEntrance()
        onMain { viewModel.requestTemporalCode(entrance) }
        repository.codeRequests.receive().reply(TemporalCode("1111", "test-access"))
        assertEquals("1111", viewModel.state.value.shownCode?.value)

        onMain { viewModel.showAddLoginAndPassword() }

        assertEquals(null, viewModel.state.value.shownCode)
    }

    @Test
    fun openingSearchResultClosesThePreviouslyShownCode() = runScenario {
        val entrance = openEntrance()
        onMain { viewModel.requestTemporalCode(entrance) }
        repository.codeRequests.receive().reply(TemporalCode("1111", "test-access"))
        assertEquals("1111", viewModel.state.value.shownCode?.value)
        val street = loadedStreet()

        onMain {
            viewModel.openSearchResult(AddressSearchResult(street, rawHouse("Дом 2"), "Тестовый адрес"))
        }
        repository.houseRequests.receive()

        assertEquals(null, viewModel.state.value.shownCode)
    }

    @Test
    fun addressRefreshKeepsPendingDoorBlockedUntilItsResponse() = runScenario {
        val entrance = rawEntrance("Подъезд 1").copy(
            sources = listOf(AccessSource("demo-main", "remote-entrance")),
        )
        onMain { viewModel.openDoor(entrance) }
        val request = repository.doorRequests.receive()

        refreshAddresses()
        assertEquals(DoorActionState.Sending, viewModel.state.value.doorActions[entrance.id])
        onMain {
            viewModel.openDoor(entrance)
            viewModel.requestTemporalCode(entrance)
        }
        assertEquals(1, repository.doorCalls)
        assertTrue(repository.codeRequests.tryReceive().isFailure)

        request.reply(DoorOpenResult.Rejected("Тестовый отказ"))
        assertEquals(
            DoorActionState.Failed("Тестовый отказ"),
            viewModel.state.value.doorActions[entrance.id],
        )
        onMain { viewModel.openDoor(entrance) }
        val retry = repository.doorRequests.receive()
        assertEquals(2, repository.doorCalls)
        retry.reply(DoorOpenResult.Rejected("Повторный тестовый отказ"))
    }

    @Test
    fun addressRefreshDoesNotKeepCompletedDoorResult() = runScenario {
        val entrance = rawEntrance("Подъезд 1").copy(
            sources = listOf(AccessSource("demo-main", "remote-entrance")),
        )
        onMain { viewModel.openDoor(entrance) }
        repository.doorRequests.receive().reply(DoorOpenResult.Rejected("Тестовый отказ"))

        refreshAddresses()

        assertFalse(entrance.id in viewModel.state.value.doorActions)
    }

    private suspend fun checkPendingHouseRequest(oldRequestFails: Boolean) {
        val street = loadedStreet()
        onMain { viewModel.toggleStreet(street) }
        val oldRequest = repository.houseRequests.receive()

        val refreshedStreet = refreshAddresses()
        onMain { viewModel.toggleStreet(refreshedStreet) }
        val newRequest = repository.houseRequests.receive()
        assertTrue(street.id in viewModel.state.value.loadingStreetIds)

        if (oldRequestFails) oldRequest.fail() else oldRequest.reply(listOf(rawHouse("Дом 1")))

        assertTrue(street.id in viewModel.state.value.loadingStreetIds)
        assertFalse(street.id in viewModel.state.value.housesByStreet)
        newRequest.reply(listOf(rawHouse("Дом 2")))
        val completed = viewModel.state.first { street.id !in it.loadingStreetIds }
        assertEquals(listOf("Дом 2"), completed.housesByStreet[street.id]?.map(House::label))
    }

    private suspend fun checkPendingEntranceRequest(oldRequestFails: Boolean) {
        val street = loadedStreet()
        val house = loadHouse(street)
        onMain { viewModel.toggleHouse(street.id, house) }
        val oldRequest = repository.entranceRequests.receive()

        val refreshedStreet = refreshAddresses()
        val refreshedHouse = loadHouse(refreshedStreet)
        onMain { viewModel.toggleHouse(refreshedStreet.id, refreshedHouse) }
        val newRequest = repository.entranceRequests.receive()
        assertTrue(house.id in viewModel.state.value.loadingHouseIds)

        if (oldRequestFails) oldRequest.fail() else oldRequest.reply(listOf(rawEntrance("Подъезд 1")))

        assertTrue(house.id in viewModel.state.value.loadingHouseIds)
        assertFalse(house.id in viewModel.state.value.entrancesByHouse)
        newRequest.reply(listOf(rawEntrance("Подъезд 2")))
        val completed = viewModel.state.first { house.id !in it.loadingHouseIds }
        assertEquals(listOf("Подъезд 2"), completed.entrancesByHouse[house.id]?.map(Entrance::label))
    }

    private suspend fun loadedStreet(): Street = viewModel.state
        .first { !it.streetsLoading && it.streets.size == 1 }.streets.single()

    private suspend fun refreshAddresses(): Street {
        onMain {
            // Запросами управляет сценарий теста; восстановление сохранённой ветки здесь не нужно.
            AppPreferences(application).saveAddressSelection(AccordionSelection())
            viewModel.refreshAddresses()
        }
        return loadedStreet()
    }

    private suspend fun loadHouse(street: Street): House {
        onMain { viewModel.toggleStreet(street) }
        repository.houseRequests.receive().reply(listOf(rawHouse("Дом 1")))
        return viewModel.state.first { it.housesByStreet[street.id]?.size == 1 }
            .housesByStreet.getValue(street.id).single()
    }

    private suspend fun openEntrance(): Entrance {
        val street = loadedStreet()
        val house = loadHouse(street)
        onMain { viewModel.toggleHouse(street.id, house) }
        repository.entranceRequests.receive().reply(listOf(rawEntrance("Подъезд 1")))
        val entrance = viewModel.state.first { it.entrancesByHouse[house.id]?.size == 1 }
            .entrancesByHouse.getValue(house.id).single()
        onMain { viewModel.toggleEntrance(street.id, house.id, entrance) }
        return entrance
    }

    private fun rawHouse(label: String) = House("remote-house", "remote-street", label)

    private fun rawEntrance(label: String) = Entrance("remote-entrance", "remote-house", label, false)

    private fun runScenario(block: suspend () -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private class DelayedAddressRepository : DomruRepository by PrototypeDomruRepository() {
        val rawStreet = Street("remote-street", "Тестовая", locality = Locality("remote-city", "Тестовый город"))
        var delayStreetResponses = false
        val streetRequests = Channel<DelayedReply<List<Street>>>(Channel.UNLIMITED)
        val houseRequests = Channel<DelayedReply<List<House>>>(Channel.UNLIMITED)
        val entranceRequests = Channel<DelayedReply<List<Entrance>>>(Channel.UNLIMITED)
        val codeRequests = Channel<DelayedReply<TemporalCode?>>(Channel.UNLIMITED)
        val doorRequests = Channel<DelayedReply<DoorOpenResult>>(Channel.UNLIMITED)
        var doorCalls = 0
            private set
        private val pending = mutableListOf<DelayedReply<*>>()

        override suspend fun loadStreets(accountId: String): List<Street> {
            if (accountId != "demo-main") return emptyList()
            if (!delayStreetResponses) return listOf(rawStreet)
            val request = DelayedReply<List<Street>>(currentCoroutineContext().job)
            pending += request
            streetRequests.send(request)
            return request.await()
        }

        override suspend fun loadHouses(accountId: String, streetId: String): List<House> {
            val request = DelayedReply<List<House>>(currentCoroutineContext().job)
            pending += request
            houseRequests.send(request)
            return request.await()
        }

        override suspend fun loadEntrances(accountId: String, houseId: String): List<Entrance> {
            val request = DelayedReply<List<Entrance>>(currentCoroutineContext().job)
            pending += request
            entranceRequests.send(request)
            return request.await()
        }

        override suspend fun getTemporalCode(accountId: String, entranceId: String): TemporalCode? {
            val request = DelayedReply<TemporalCode?>(currentCoroutineContext().job)
            pending += request
            codeRequests.send(request)
            return request.await()
        }

        override suspend fun openDoor(accountId: String, entranceId: String): DoorOpenResult {
            doorCalls++
            val request = DelayedReply<DoorOpenResult>(currentCoroutineContext().job)
            pending += request
            doorRequests.send(request)
            return request.await()
        }

        suspend fun releasePendingRequests() = withContext(Dispatchers.Main.immediate) {
            pending.forEach { it.cancelWait() }
        }
    }

    private class DelayedReply<T>(private val requestJob: Job) {
        private val response = CompletableDeferred<T>()

        // Ответ приходит даже после отмены: запрос уже был отправлен до сброса каталога.
        suspend fun await(): T = withContext(NonCancellable) { response.await() }

        suspend fun reply(value: T) = finish { response.complete(value) }

        suspend fun fail() = finish { response.completeExceptionally(IllegalStateException("Delayed test failure")) }

        fun cancelWait() {
            response.completeExceptionally(CancellationException("Test finished"))
        }

        private suspend fun finish(complete: () -> Unit) {
            withContext(Dispatchers.Main.immediate) { complete() }
            requestJob.join()
            // Дожидаемся обработки ответа и завершения очереди главного потока.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private class IsolatedApplication(context: Context) : Application() {
        private val prefix = "address_loading_test_${UUID.randomUUID()}_"
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
