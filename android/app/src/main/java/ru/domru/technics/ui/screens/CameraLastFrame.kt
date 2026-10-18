package ru.domru.technics.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Один уменьшенный кадр в памяти. Он не переживает закрытие камеры или уход в фон. */
internal class CameraLastFrame {
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var generation = 0L
        private set

    fun update(frame: Bitmap, expectedGeneration: Long) {
        // PixelCopy асинхронен: результат уже закрытой камеры не должен появиться снова.
        if (generation == expectedGeneration) bitmap = frame
    }

    fun clear() {
        generation++
        bitmap = null
    }
}

@Composable
internal fun rememberCameraLastFrame(cameraId: String = "preview"): CameraLastFrame {
    val owner = LocalLifecycleOwner.current
    val frame = remember(owner, cameraId) { CameraLastFrame() }
    DisposableEffect(owner, frame) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) frame.clear()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); frame.clear() }
    }
    return frame
}

@Composable
internal fun LastCameraFrame(frame: CameraLastFrame, modifier: Modifier = Modifier) {
    frame.bitmap?.let { bitmap ->
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}
