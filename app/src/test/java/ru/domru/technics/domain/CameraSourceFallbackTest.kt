package ru.domru.technics.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.domru.technics.data.*
import ru.domru.technics.model.*

class CameraSourceFallbackTest {
    private val accounts = listOf(AccountProfile("first", "First", ""), AccountProfile("second", "Second", ""))
    private val door = Entrance("merged", "house", "Door", true, deviceIdentity = "device",
        sources = listOf(AccessSource("first", "first-door"), AccessSource("second", "second-door")))
    private val stream = CameraStream("https://camera.example.test/playlist.m3u8")
    private class Repository(val video: suspend (String, String) -> CameraStream?) : DomruRepository {
        val requests = mutableListOf<Pair<String, String>>()
        override suspend fun getCameraStream(accountId: String, entranceId: String): CameraStream? {
            requests += accountId to entranceId
            return video(accountId, entranceId)
        }
        override suspend fun signIn(login: String, password: String): AccountProfile = error("Unexpected mutation")
        override suspend fun signOut(accountId: String) = error("Unexpected mutation")
        override suspend fun loadStreets(accountId: String): List<Street> = error("Unexpected read")
        override suspend fun loadHouses(accountId: String, streetId: String): List<House> = error("Unexpected read")
        override suspend fun loadEntrances(accountId: String, houseId: String): List<Entrance> = error("Unexpected read")
        override suspend fun search(accountId: String, query: String): List<AddressSearchResult> = error("Unexpected read")
        override suspend fun openDoor(accountId: String, entranceId: String): DoorOpenResult = error("Unexpected mutation")
        override suspend fun getTemporalCode(accountId: String, entranceId: String): TemporalCode? = error("Unexpected read")
    }
    private fun service(repo: Repository) = CombinedAccessService(repo, RetryPolicy { })

    @Test fun expiredFirstAccountDoesNotHideSecondAuthorizedCamera() = runBlocking {
        val repo = Repository { account, _ ->
            if (account == "first") throw PortalRequestException(403, "Access expired") else stream
        }
        assertEquals(stream.copy(source = AccessSource("second", "second-door")), service(repo).getCameraStream(accounts, door))
        assertEquals(listOf("first" to "first-door", "second" to "second-door"), repo.requests)
    }

    @Test fun absentStreamFallsBackButSuccessfulFirstSourceIsNotDuplicated() = runBlocking {
        val absent = Repository { account, _ -> if (account == "first") null else stream }
        assertEquals(stream.copy(source = AccessSource("second", "second-door")), service(absent).getCameraStream(accounts, door))
        assertEquals(2, absent.requests.size)
        val success = Repository { _, _ -> stream }
        assertEquals(stream.copy(source = AccessSource("first", "first-door")), service(success).getCameraStream(accounts, door))
        assertEquals(listOf("first" to "first-door"), success.requests)
    }

    @Test fun doesNotTryDisabledVideoSourceOrMissingAccount() = runBlocking {
        val repo = Repository { _, _ -> stream }
        val filtered = door.copy(sources = listOf(AccessSource("first", "first-door", canVideo = false),
            AccessSource("removed", "removed-door"), AccessSource("second", "second-door")))
        assertEquals(stream.copy(source = AccessSource("second", "second-door")), service(repo).getCameraStream(accounts, filtered))
        assertEquals(listOf("second" to "second-door"), repo.requests)
    }

    @Test fun cancellationStopsWithoutTryingAnotherAccount() = runBlocking {
        val repo = Repository { _, _ -> throw CancellationException("View closed") }
        try { service(repo).getCameraStream(accounts, door); fail("Must cancel") } catch (_: CancellationException) { }
        assertEquals(1, repo.requests.size)
    }

    @Test fun keepsLastErrorIfNoAuthorizedSourceWorks() = runBlocking {
        val failure = PortalRequestException(403, "Access expired")
        val repo = Repository { _, _ -> throw failure }
        try { service(repo).getCameraStream(accounts, door); fail("Must retain error") }
        catch (error: PortalRequestException) { assertSame(failure, error) }
        assertEquals(2, repo.requests.size)
    }

    @Test fun temporaryOutageIsNotHiddenByAnotherSubscribersForbiddenResponse() = runBlocking {
        val outage = PortalRequestException(503, "Camera temporarily offline")
        val repo = Repository { account, _ ->
            if (account == "first") throw outage else throw PortalRequestException(403, "Access expired")
        }
        try { service(repo).getCameraStream(accounts, door); fail("Must retain recoverable failure") }
        catch (error: PortalRequestException) { assertSame(outage, error) }
        assertEquals(listOf("first", "first", "first", "second"), repo.requests.map { it.first })
    }

    @Test fun playbackFailureRotatesSourceEvenWhenFirstSourceKeepsReturningDownloadableUrls() = runBlocking {
        val failingPlayback = stream.copy(source = AccessSource("first", "first-door"))
        val workingPlayback = stream.copy(source = AccessSource("second", "second-door"))
        val repo = Repository { account, _ -> if (account == "first") failingPlayback else workingPlayback }
        assertEquals(failingPlayback, service(repo).getCameraStream(accounts, door))
        repo.requests.clear()
        assertEquals(workingPlayback, service(repo).getCameraStream(accounts, door, failingPlayback.source))
        assertEquals(listOf("second" to "second-door"), repo.requests)
        repo.requests.clear()
        assertEquals(failingPlayback, service(repo).getCameraStream(accounts, door, workingPlayback.source))
        assertEquals(listOf("first" to "first-door"), repo.requests)
    }

    @Test fun failedSourceOfAnotherDoorCannotChangeOrderingOrAddNewPermissions() = runBlocking {
        val repo = Repository { _, _ -> stream }
        service(repo).getCameraStream(accounts, door, AccessSource("first", "another-door"))
        assertEquals(listOf("first" to "first-door"), repo.requests)
    }

    @Test fun playbackFailuresReachThirdSourceBeforeCyclingBackAndManualRefreshKeepsAccountOrder() = runBlocking {
        val threeAccounts = accounts + AccountProfile("third", "Third", "")
        val sharedDoor = door.copy(sources = door.sources + AccessSource("third", "third-door"))
        val repo = Repository { account, remote -> stream.copy(source = AccessSource(account, remote)) }
        val service = service(repo)
        var selected = service.getCameraStream(threeAccounts, sharedDoor)!!
        val visited = mutableListOf(selected.source!!.accountId)
        repeat(3) {
            selected = service.getCameraStream(threeAccounts, sharedDoor, selected.source)!!
            visited += selected.source!!.accountId
        }
        assertEquals(listOf("first", "second", "third", "first"), visited)
        assertEquals("first", service.getCameraStream(threeAccounts, sharedDoor)!!.source!!.accountId)
        assertEquals(listOf("first", "second", "third", "first", "first"), repo.requests.map { it.first })
    }

    @Test fun malformedCameraResponseIsNotHiddenByAnotherAccountsForbiddenResponse() = runBlocking {
        val temporary = PortalProtocolException("Temporary empty camera response")
        val repo = Repository { account, _ ->
            if (account == "first") throw temporary else throw PortalRequestException(403, "Access expired")
        }
        try { service(repo).getCameraStream(accounts, door); fail("Must retain recoverable camera failure") }
        catch (error: PortalProtocolException) { assertSame(temporary, error) }
        assertEquals(listOf("first", "second"), repo.requests.map { it.first })
    }
}
