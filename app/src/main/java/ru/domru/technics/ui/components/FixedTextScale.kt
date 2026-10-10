package ru.domru.technics.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Heading typography stays fixed while surrounding content follows the system font scale. */
@Composable
internal fun FixedTextScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val fixed = remember(density) { Density(density = density, fontScale = 1f) }
    CompositionLocalProvider(LocalDensity provides fixed, content = content)
}
