package ru.domru.technics.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.domru.technics.data.DomruRepository
import ru.domru.technics.data.PrototypeDomruRepository
import ru.domru.technics.model.AccessSource
import ru.domru.technics.model.AccountAccessStatus
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.CameraState
import ru.domru.technics.model.CameraStream
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import java.util.UUID

/** Проверяет получение ссылки камеры через ViewModel; плеер и настоящая сеть здесь не запускаются. */
@RunWith(AndroidJUnit4::class)
class AppViewModelCameraLoadingTest {
    private lateinit var application: IsolatedApplication
    private lateinit var repository: CameraRepository
    private lateinit var viewModel: AppViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() = runScenario {
        application = IsolatedApplication(ApplicationProvider.getApplicationContext())
        repository = CameraRepository()
        onMain {
            viewModel = AppViewModel(application, repository)
            store = ViewModelStore().also { it.put("camera", viewModel) }
            viewModel.onAppForegrounded()
        }
        viewModel.state.first { !it.streetsLoading && it.streets.size == 1 }
    }

    @After
    fun tearDown() = runScenario {
        if (::viewModel.isInitialized) {
            val scopeJob = viewModel.viewModelScope.coroutineContext.job
            onMain { store.clear() }
            scopeJob.join()
        }
        if (::application.isInitialized) application.clearPreferences()
    }

    @Test
    fun openingEntranceRequestsItsCameraAndMakesStreamReady() = runScenario {
        val entrance = openCamera()

        val ready = completeCameraRequest(entrance.id, "opened")

        assertEquals(entrance.id, viewModel.state.value.selection.entranceId)
        assertEquals(AccessSource("camera-account", "remote-entrance"), ready.stream.source)
        assertTrue(ready.playbackGeneration > 0)
    }

    @Test
    fun refreshingAddressesRestoresTheSelectedEntranceWithAFreshStream() = runScenario {
        val entrance = openCamera()
        val initial = completeCameraRequest(entrance.id, "before-refresh")
        val selection = viewModel.state.value.selection

        // Сохранённую ветку не задаём вручную: её должен сохранить обычный выбор подъезда.
        onMain { viewModel.refreshAddresses() }
        val restored = completeCameraRequest(entrance.id, "after-refresh")

        assertEquals(selection, viewModel.state.value.selection)
        assertEquals(initial.stream.source, restored.stream.source)
        assertTrue(restored.playbackGeneration > initial.playbackGeneration)
    }

    @Test
    fun returningToForegroundRequestsAFreshStreamForTheSelectedEntrance() = runScenario {
        val entrance = openCamera()
        val initial = completeCameraRequest(entrance.id, "before-background")
        val selection = viewModel.state.value.selection

        onMain { viewModel.onAppBackgrounded() }
        assertTrue(viewModel.state.value.cameraStates.isEmpty())
        assertEquals(selection, viewModel.state.value.selection)
        assertTrue(repository.cameraRequests.tryReceive().isFailure)

        onMain { viewModel.onAppForegrounded() }
        val resumed = completeCameraRequest(entrance.id, "after-background")

        assertEquals(selection, viewModel.state.value.selection)
        assertEquals(initial.stream.source, resumed.stream.source)
        assertTrue(resumed.playbackGeneration > initial.playbackGeneration)
    }

    private suspend fun openCamera(): Entrance {
        val street = viewModel.state.value.streets.single()
        onMain { viewModel.toggleStreet(street) }
        val house = viewModel.state.first { it.housesByStreet[street.id]?.size == 1 }
            .housesByStreet.getValue(street.id).single()
        onMain { viewModel.toggleHouse(street.id, house) }
        val entrance = viewModel.state.first { it.entrancesByHouse[house.id]?.size == 1 }
            .entrancesByHouse.getValue(house.id).single()
        assertTrue(entrance.cameraAvailable)
        onMain { viewModel.toggleEntrance(street.id, house.id, entrance) }
        return entrance
    }

    private suspend fun completeCameraRequest(entranceId: String, streamName: String): CameraState.Ready {
        val request = repository.cameraRequests.receive()
        assertEquals("camera-account", request.accountId)
        assertEquals("remote-entrance", request.entranceId)
        assertEquals(CameraState.Loading, viewModel.state.value.cameraStates[entranceId])
        val url = "https://camera.example.invalid/$streamName.m3u8"
        request.response.complete(CameraStream(url))
        val state = viewModel.state.first {
            (it.cameraStates[entranceId] as? CameraState.Ready)?.stream?.streamUrl == url
        }
        return state.cameraStates.getValue(entranceId) as CameraState.Ready
    }

    private fun runScenario(block: suspend () -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private data class CameraRequest(
        val accountId: String,
        val entranceId: String,
        val response: CompletableDeferred<CameraStream> = CompletableDeferred(),
    )

    private class CameraRepository : DomruRepository by PrototypeDomruRepository() {
        val cameraRequests = Channel<CameraRequest>(Channel.UNLIMITED)
        private val account = AccountProfile("camera-account", "Тестовый аккаунт", "test-login")
        private val street = Street(
            "remote-street", "Тестовая", locality = Locality("remote-city", "Тестовый город"),
        )
        private val house = House("remote-house", street.id, "Дом 1")
        private val entrance = Entrance("remote-entrance", house.id, "Подъезд 1", cameraAvailable = true)

        override fun savedAccounts(): List<AccountProfile> = listOf(account)

        override suspend fun checkAccess(accountId: String): AccountAccessStatus = AccountAccessStatus.ACTIVE

        override suspend fun loadStreets(accountId: String): List<Street> = listOf(street)

        override suspend fun loadHouses(accountId: String, streetId: String): List<House> = listOf(house)

        override suspend fun loadEntrances(accountId: String, houseId: String): List<Entrance> = listOf(entrance)

        override suspend fun getCameraStream(accountId: String, entranceId: String): CameraStream {
            val request = CameraRequest(accountId, entranceId)
            cameraRequests.send(request)
            return request.response.await()
        }
    }

    private class IsolatedApplication(context: Context) : Application() {
        private val prefix = "camera_loading_test_${UUID.randomUUID()}_"
        private val preferenceFiles = mutableSetOf<String>()

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getSystemService(name: String): Any? =
            // Смена сети устройства не должна создавать посторонние запросы в управляемом сценарии.
            if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)

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
