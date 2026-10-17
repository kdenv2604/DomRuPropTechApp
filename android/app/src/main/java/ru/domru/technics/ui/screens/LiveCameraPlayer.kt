@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package ru.domru.technics.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import ru.domru.technics.model.CameraStream

internal enum class CameraPlaybackFailure(val caption: String) {
    CONNECTION("Соединение с камерой прервалось"),
    FORMAT("Формат видеопотока не поддерживается"),
    DECODER("Не удалось воспроизвести видео"),
    ENDED("Камера завершила видеопоток"),
    BUFFER_TIMEOUT("Камера долго не передаёт видео"),
    UNKNOWN("Не удалось продолжить просмотр"),
}

/** Один поток встроенного предпросмотра; закрытие карточки освобождает соединение. */
internal class CameraPlaybackSession(
    context: Context,
    val stream: CameraStream,
    dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
        // Медиасервер может перенаправить выданный адрес между HTTP и HTTPS.
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(60_000)
        .setReadTimeoutMs(60_000)
        .setUserAgent("lk.proptech.ru Android"),
) {
    var videoReady by mutableStateOf(false)
        private set
    var playbackFailed by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var failureReason by mutableStateOf<CameraPlaybackFailure?>(null)
        private set
    var failureCode by mutableStateOf<Int?>(null)
        private set
    var released = false
        private set
    private var target: PlayerView? = null
    val player = ExoPlayer.Builder(context.applicationContext)
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 500, 1_000).build())
        .build()
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                playbackFailed = false
                failureReason = null
                failureCode = null
            }
            if (state == Player.STATE_ENDED) {
                failureReason = CameraPlaybackFailure.ENDED
                playbackFailed = true
            }
        }

        override fun onRenderedFirstFrame() {
            videoReady = true
            // Поздний первый кадр отменяет повтор, даже если нового события READY не было.
            if (!buffering && failureReason == CameraPlaybackFailure.BUFFER_TIMEOUT) {
                playbackFailed = false
                failureReason = null
                failureCode = null
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            failureCode = error.errorCode
            failureReason = when (error.errorCode) {
                in 2000..2999 -> CameraPlaybackFailure.CONNECTION
                in 3000..3999 -> CameraPlaybackFailure.FORMAT
                in 4000..4999 -> CameraPlaybackFailure.DECODER
                else -> CameraPlaybackFailure.UNKNOWN
            }
            playbackFailed = true
        }
    }

    init {
        player.addListener(listener)
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        player.playWhenReady = true
        val source = DefaultMediaSourceFactory(context.applicationContext)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
            .createMediaSource(MediaItem.Builder().setUri(stream.streamUrl).setMimeType(stream.mimeType).build())
        player.setMediaSource(source)
        player.prepare()
    }

    fun attach(view: PlayerView) {
        if (released || target === view) return
        // Media3 подключает новую поверхность прежде, чем отключить старую.
        PlayerView.switchTargetView(player, target, view)
        target = view
    }

    fun detach(view: PlayerView) {
        // Запоздалое удаление старой поверхности не отключает уже подключённую новую.
        if (target === view) {
            view.player = null
            target = null
        } else if (view.player === player) view.player = null
    }

    fun failIfWaitingForVideo() {
        if (!released && (buffering || !videoReady)) {
            failureReason = CameraPlaybackFailure.BUFFER_TIMEOUT
            failureCode = null
            playbackFailed = true
        }
    }

    fun release() {
        if (released) return
        released = true
        target?.player = null
        target = null
        player.removeListener(listener)
        player.release()
    }
}

/** Предпросмотр владеет плеером только пока карточка отображается и приложение видно. */
@Composable
internal fun LiveCameraPlayer(
    stream: CameraStream,
    onRetry: () -> Unit,
    playbackGeneration: Long = 0,
    sessionFactory: (Context, CameraStream) -> CameraPlaybackSession = { context, camera -> CameraPlaybackSession(context, camera) },
    firstFrameTimeoutMs: Long = 20_000,
    retryDelayMs: Long = 3_000,
) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var lifecycleGeneration by remember(owner) { mutableIntStateOf(0) }
    val session = remember(owner, stream, playbackGeneration, foreground, lifecycleGeneration) {
        stream.takeIf { foreground }?.let { sessionFactory(context, it) }
    }
    val currentSession by rememberUpdatedState(session)
    val retry by rememberUpdatedState(onRetry)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    lifecycleGeneration++
                    currentSession?.release()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(session) { onDispose { session?.release() } }
    LaunchedEffect(session, session?.buffering, session?.videoReady) {
        if (session != null && (session.buffering || !session.videoReady)) {
            delay(firstFrameTimeoutMs)
            session.failIfWaitingForVideo()
        }
    }
    LaunchedEffect(session, session?.playbackFailed) {
        if (session?.playbackFailed == true) {
            delay(retryDelayMs)
            if (!session.released && session.playbackFailed) retry()
        }
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(17.dp))) {
        if (session != null) {
            key(session) {
                AndroidView(
                    factory = { viewContext -> PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        setKeepContentOnPlayerReset(true)
                    } },
                    update = session::attach,
                    onRelease = session::detach,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (session == null || (!session.videoReady && !session.playbackFailed)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), color = Color.White, strokeWidth = 2.dp)
        }
        if (session?.playbackFailed == true) {
            Box(Modifier.fillMaxSize().background(Color(0xDD080C11)), contentAlignment = Alignment.Center) {
                val reason = session.failureReason?.caption
                val code = session.failureCode?.let { " · $it" }.orEmpty()
                Text(listOfNotNull(reason?.plus(code), "Восстанавливаем видеопоток…").joinToString("\n"), color = Color.White,
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
