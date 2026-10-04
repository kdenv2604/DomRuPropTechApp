@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package ru.domru.technics.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ru.domru.technics.model.CameraStream

/** Проигрывает FLV-поток камеры и полностью останавливает его при закрытии карточки. */
@Composable
fun LiveCameraPlayer(stream: CameraStream, onRetry: () -> Unit) {
    val context = LocalContext.current
    var videoReady by remember(stream.streamUrl) { mutableStateOf(false) }
    var playbackFailed by remember(stream.streamUrl) { mutableStateOf(false) }
    val player = remember(stream.streamUrl) {
        val mediaItem = MediaItem.Builder()
            .setUri(stream.streamUrl)
            .setMimeType(MimeTypes.VIDEO_FLV)
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("lk.proptech.ru Android"),
        )
            // Две повторные попытки плюс первая дают максимум три обращения к потоку.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
            .createMediaSource(mediaItem)
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_500, 8_000, 500, 1_000)
                    .build(),
            )
            .build()
            .apply {
                volume = 0f
                playWhenReady = true
                setMediaSource(mediaSource)
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            /** Убирает индикатор, когда появился первый кадр, и замечает конец потока. */
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    videoReady = true
                }
                if (state == Player.STATE_ENDED) playbackFailed = true
            }

            /** Любую ошибку плеера превращает в ручную кнопку повтора. */
            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(17.dp)),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!videoReady && !playbackFailed) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }

        if (playbackFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xDD080C11)),
                contentAlignment = Alignment.Center,
            ) {
                Button(onClick = onRetry) {
                    Text("ПОВТОРИТЬ ВИДЕО")
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(Modifier.width(7.dp))
                Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
