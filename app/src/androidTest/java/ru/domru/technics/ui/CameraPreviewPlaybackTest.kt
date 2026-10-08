@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package ru.domru.technics.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.domru.technics.model.CameraStream
import ru.domru.technics.ui.screens.CameraPlaybackFailure
import ru.domru.technics.ui.screens.CameraPlaybackSession
import ru.domru.technics.ui.screens.LiveCameraPlayer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real AVC decoding with a local source; this activity never creates an account repository. */
@RunWith(AndroidJUnit4::class)
class CameraPreviewPlaybackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun connectionFailureAndEndedStreamAutomaticallyRestartSameUrlAndRenderNewFrames() {
        val stream = localStream("same-url")
        val generation = mutableLongStateOf(1)
        val current = AtomicReference<CameraPlaybackSession?>()
        val created = AtomicInteger()
        val retries = AtomicInteger()
        val failuresEnabled = AtomicBoolean(true)
        val source = CountingSource(failuresEnabled)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    LiveCameraPlayer(stream, playbackGeneration = generation.longValue,
                        retryDelayMs = 1_500,
                        onRetry = {
                            retries.incrementAndGet()
                            failuresEnabled.set(false)
                            // A new Ready result can keep the exact URI without an observed Loading state.
                            generation.longValue++
                        }, sessionFactory = { context, camera ->
                            created.incrementAndGet()
                            CameraPlaybackSession(context, camera, source).also(current::set)
                        })
                }
            }
        }
        await("synthetic HTTP-like connection failure") { current.get()?.playbackFailed == true }
        val failed = current.get()!!
        assertEquals(CameraPlaybackFailure.CONNECTION, failed.failureReason)
        assertNotNull(failed.failureCode)
        await("automatic same-URL reconnection and actual first frame") {
            current.get()?.let { it !== failed && it.videoReady && !it.playbackFailed } == true
        }
        val playing = current.get()!!
        assertTrue("The failed decoder must be released", failed.released)
        assertEquals(1, retries.get())
        assertEquals(2, created.get())
        assertEquals(stream, playing.stream)

        compose.runOnUiThread {
            assertTrue("The local fixture must have a finite timeline", playing.player.duration > 0)
            playing.player.seekTo(playing.player.duration)
        }
        await("real Media3 ENDED event") { playing.failureReason == CameraPlaybackFailure.ENDED }
        await("automatic live restart after ENDED and actual replacement frame") {
            current.get()?.let { it !== playing && it.videoReady && !it.playbackFailed } == true
        }
        assertTrue(playing.released)
        assertEquals("Each failure must request one new stream", 2, retries.get())
        assertEquals(3, created.get())
        assertEquals("A repeated URL still needs a new decoder", stream.streamUrl, current.get()!!.stream.streamUrl)
        assertNull(current.get()!!.failureReason)
    }

    @Test
    fun firstRenderedFrameClearsWatchdogFailureWithoutAnotherReadyEventOrReconnection() {
        val mounted = mutableStateOf(false)
        val current = AtomicReference<CameraPlaybackSession?>()
        val source = CountingSource()
        val readyAfterDeadline = AtomicInteger()
        compose.runOnUiThread { current.set(CameraPlaybackSession(compose.activity, localStream("late-frame"), source)) }
        val session = current.get()!!
        try {
            compose.setContent {
                MaterialTheme {
                    // Prepare the real AVC stream without attaching a video surface.
                    if (mounted.value) Box(Modifier.fillMaxSize()) {
                        AndroidView(factory = { context -> PlayerView(context).apply { useController = false } },
                            update = session::attach, onRelease = session::detach, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            await("READY without a rendered first frame") {
                var ready = false
                compose.runOnUiThread { ready = session.player.playbackState == Player.STATE_READY && !session.videoReady }
                ready
            }
            compose.runOnUiThread {
                session.player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) readyAfterDeadline.incrementAndGet()
                    }
                })
                // Reach the watchdog deadline, then allow an actual decoded frame to arrive.
                session.failIfWaitingForVideo()
                assertTrue(session.playbackFailed)
                assertEquals(CameraPlaybackFailure.BUFFER_TIMEOUT, session.failureReason)
                mounted.value = true
            }
            await("the actual first frame clears timeout") {
                session.videoReady && !session.playbackFailed && session.failureReason == null
            }
            assertEquals("The first-frame callback must recover without another READY transition", 0, readyAfterDeadline.get())
            assertFalse(session.released)
            assertNull(session.failureCode)
            assertEquals("A late frame must not reconnect the source", 1, source.opens.get())
        } finally {
            compose.runOnUiThread { session.release() }
        }
    }

    @Test
    fun removingOrBackgroundingPreviewReleasesPlayerAndCancelsPendingRecovery() {
        val stream = localStream("preview-lifetime")
        val rendered = mutableStateOf(true)
        val current = AtomicReference<CameraPlaybackSession?>()
        val created = AtomicInteger()
        val retries = AtomicInteger()
        val source = CountingSource()
        compose.setContent {
            MaterialTheme {
                if (rendered.value) Box(Modifier.fillMaxSize()) {
                    LiveCameraPlayer(stream, retryDelayMs = 2_000,
                        onRetry = { retries.incrementAndGet() }, sessionFactory = { context, camera ->
                            created.incrementAndGet()
                            CameraPlaybackSession(context, camera, source).also(current::set)
                        })
                }
            }
        }
        await("preview first frame") { current.get()?.videoReady == true }
        val first = current.get()!!
        end(first)
        compose.runOnUiThread { rendered.value = false }
        await("removed preview releases its player") { first.released }
        // Run beyond the armed effect's delay; a wall-clock sleep would not run test-clock coroutines.
        compose.mainClock.advanceTimeBy(2_300)
        compose.waitForIdle()
        assertEquals("A disposed preview must not request recovery", 0, retries.get())
        assertEquals("No replacement player may start while the preview is absent", 1, created.get())
        assertEquals(0, source.active.get())

        compose.runOnUiThread { rendered.value = true }
        await("remount at the same URL renders a replacement frame") {
            current.get()?.let { it !== first && it.videoReady } == true
        }
        val beforeBackground = current.get()!!
        end(beforeBackground)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue("ON_STOP must release synchronously", beforeBackground.released)
        compose.mainClock.advanceTimeBy(2_300)
        compose.waitForIdle()
        assertEquals("A background preview must not request recovery", 0, retries.get())
        assertEquals("Backgrounding must not create another player", 2, created.get())
        assertEquals(0, source.active.get())

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("foreground restoration renders a new frame") {
            current.get()?.let { it !== beforeBackground && it.videoReady && !it.released } == true
        }
        assertEquals(3, created.get())
        assertEquals(0, retries.get())
    }

    private fun end(session: CameraPlaybackSession) {
        compose.runOnUiThread {
            assertTrue(session.player.duration > 0)
            session.player.seekTo(session.player.duration)
        }
        await("ENDED before recovery delay") { session.failureReason == CameraPlaybackFailure.ENDED }
        // Observe ENDED in composition and arm its delayed recovery before testing cancellation.
        compose.waitForIdle()
    }

    private fun localStream(name: String) = CameraStream("memory://$name/live.mp4", mimeType = MimeTypes.VIDEO_MP4)

    private fun await(description: String, condition: () -> Boolean) {
        try {
            // Advance recomposition/effect time while yielding to the real Media3 decoder and Android surface.
            compose.waitUntil(timeoutMillis = 20_000) { compose.runOnUiThread(condition) }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $description", error)
        }
    }

    private class CountingSource(private val failuresEnabled: AtomicBoolean = AtomicBoolean(false)) : DataSource.Factory {
        val opens = AtomicInteger()
        val active = AtomicInteger()
        private val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("local-camera.mp4").use { it.readBytes() }

        override fun createDataSource(): DataSource {
            val delegate = ByteArrayDataSource(bytes)
            return object : DataSource by delegate {
                private var opened = false
                override fun open(dataSpec: DataSpec): Long {
                    if (failuresEnabled.get()) throw IOException("Synthetic connection failure")
                    val length = delegate.open(dataSpec)
                    opens.incrementAndGet()
                    active.incrementAndGet()
                    opened = true
                    return length
                }
                override fun close() {
                    delegate.close()
                    if (opened) {
                        opened = false
                        active.decrementAndGet()
                    }
                }
            }
        }
    }
}
