package ru.domru.technics.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.domru.technics.data.*
import ru.domru.technics.model.*
import java.io.IOException

/** Проверяет права конкретного источника и независимость каталогов аккаунтов. */
class VideoCatalogIsolationTest {
    private class Repository : DomruRepository {
        var streets: suspend (String) -> List<Street> = { emptyList() }
        var entrances: suspend (String, String) -> List<Entrance> = { _, _ -> emptyList() }
        val videoRequests = mutableListOf<Pair<String, String>>()
        override suspend fun loadStreets(accountId: String) = streets(accountId)
        override suspend fun loadEntrances(accountId: String, houseId: String) = entrances(accountId, houseId)
        override suspend fun getCameraStream(accountId: String, entranceId: String): CameraStream {
            videoRequests += accountId to entranceId
            return CameraStream("https://camera.example.test/live.flv")
        }
        override suspend fun signIn(login: String, password: String): AccountProfile = error("Unexpected mutation")
        override suspend fun signOut(accountId: String) = error("Unexpected mutation")
        override suspend fun loadHouses(accountId: String, streetId: String): List<House> = error("Unexpected read")
        override suspend fun search(accountId: String, query: String): List<AddressSearchResult> = error("Unexpected read")
        override suspend fun openDoor(accountId: String, entranceId: String): DoorOpenResult = error("Unexpected mutation")
        override suspend fun getTemporalCode(accountId: String, entranceId: String): TemporalCode? = error("Unexpected read")
    }
    private val accounts = listOf(AccountProfile("a", "A", ""), AccountProfile("b", "B", ""))
    private fun service(repo: Repository) = CombinedAccessService(repo, RetryPolicy { })

    @Test fun failedAccountDoesNotHideWorkingAccountsCatalogAndLaterRecovers() = runBlocking {
        val repo = Repository()
        var recovered = false
        repo.streets = { account ->
            if (account == "a" && !recovered) throw IOException("offline")
            listOf(Street(account, "Улица $account"))
        }
        val service = service(repo)
        val partial = service.loadStreets(accounts)
        assertEquals(listOf("b"), partial.single().sources.map { it.accountId })
        recovered = true
        assertEquals(2, service.loadStreets(accounts).size)
    }

    @Test fun sameDeviceKeepsPerAccountVideoPermissionEvenWhenLabelsDiffer() = runBlocking {
        val repo = Repository()
        repo.entrances = { account, _ -> listOf(Entrance("door-$account", "remote-house", "Подъезд $account",
            cameraAvailable = account == "b", deviceIdentity = "physical-device")) }
        val house = House("merged", "street", "1", sources = listOf(AccessSource("a", "ha"), AccessSource("b", "hb")))
        val service = service(repo)
        val entrance = service.loadEntrances(house).single()
        assertTrue(entrance.cameraAvailable)
        assertFalse(entrance.sources.first { it.accountId == "a" }.canVideo)
        service.getCameraStream(accounts, entrance)
        assertEquals(listOf("b" to "door-b"), repo.videoRequests)
    }

    @Test fun equalLabelsOfDifferentDevicesCannotShareCameraFallback() {
        val house = House("house", "street", "1")
        val doors = AddressHierarchyMerger.entrances(house, listOf(
            Entrance("a", "house", "Подъезд 1", true, deviceIdentity = "device-a", sources = listOf(AccessSource("a", "a"))),
            Entrance("b", "house", "Подъезд 1", true, deviceIdentity = "device-b", sources = listOf(AccessSource("b", "b"))),
        ))
        assertEquals(2, doors.size)
        assertTrue(doors.all { it.sources.size == 1 })
    }

    @Test fun cancellationOfCatalogIsNotSwallowedAsPartialSuccess() = runBlocking {
        val repo = Repository()
        repo.streets = { throw CancellationException("Closed") }
        try { service(repo).loadStreets(accounts); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }
}
