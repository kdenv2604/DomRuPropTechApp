package ru.domru.technics.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.domru.technics.model.CodeActionState
import ru.domru.technics.model.CameraState
import ru.domru.technics.model.DoorActionState
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Handedness
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.ui.AppUiState
import ru.domru.technics.ui.russianQuantity
import ru.domru.technics.ui.theme.SuccessGreen
import ru.domru.technics.ui.theme.WarningAmber

/** Одна строка большого ленивого списка. Так приложение не рисует тысячи строк сразу. */
sealed interface AddressListItem {
    val key: String

    /** Верхняя строка города или посёлка. */
    data class LocalityRow(
        val locality: Locality,
        val streetCount: Int,
    ) : AddressListItem {
        override val key: String = "locality:${locality.id}"
    }

    /** Повтор загрузки домов улицы после ошибки. Это не отдельный уровень дерева. */
    data class StreetRow(val street: Street) : AddressListItem {
        override val key: String = "street:${street.id}"
    }

    /** Улица и дом в одной строке внутри населённого пункта. */
    data class HouseRow(val streetId: String, val house: House, val streetName: String) : AddressListItem {
        override val key: String = "house:${house.id}"
    }

    /** Короткая строка подъезда внутри дома. */
    data class EntranceRow(
        val streetId: String,
        val houseId: String,
        val entrance: Entrance,
    ) : AddressListItem {
        override val key: String = "entrance:${entrance.id}"
    }

    /** Индикатор загрузки дочерних адресов. */
    data class LoadingRow(override val key: String) : AddressListItem
}

/** Превращает раскрытые ветки адресов в обычный прокручиваемый список. */
fun buildVisibleAddressRows(state: AppUiState): List<AddressListItem> = buildList {
    // Сначала собираем улицы одного города вместе, а уже потом рисуем вложенные строки.
    state.streets.groupBy { it.locality.id }.values.forEach localityLoop@{ localityStreets ->
        val locality = localityStreets.first().locality
        add(AddressListItem.LocalityRow(locality, localityStreets.size))
        if (state.selection.localityId != locality.id) return@localityLoop

        localityStreets.forEach streetLoop@{ street ->
            if (street.id in state.loadingStreetIds) {
                add(AddressListItem.LoadingRow("loading-street:${street.id}"))
            } else if (state.housesByStreet[street.id] == null) {
                add(AddressListItem.StreetRow(street))
            }
            state.housesByStreet[street.id].orEmpty().forEach houseLoop@{ house ->
                add(AddressListItem.HouseRow(street.id, house, street.name))
                if (state.selection.houseId != house.id) return@houseLoop

                if (house.id in state.loadingHouseIds) {
                    add(AddressListItem.LoadingRow("loading-house:${house.id}"))
                }
                state.entrancesByHouse[house.id].orEmpty().forEach { entrance ->
                    add(AddressListItem.EntranceRow(street.id, house.id, entrance))
                }
            }
        }
    }
}

/** Карточка города или посёлка. Длинное название спокойно переносится на новые строки. */
@Composable
fun LocalityListRow(
    locality: Locality,
    streetCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 0.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = locality.name,
                    style = MaterialTheme.typography.titleLarge,
                    softWrap = true,
                )
                Text(
                    text = russianQuantity(streetCount, "улица", "улицы", "улиц"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) {
                    "Свернуть населённый пункт"
                } else {
                    "Раскрыть населённый пункт"
                },
            )
        }
    }
}

/** Карточка улицы; она находится внутри раскрытого населённого пункта. */
@Composable
fun StreetListRow(street: Street, expanded: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 0.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Ограничения по числу строк нет: длинная улица переносится целыми словами.
                Text(
                    text = "Повторить загрузку: ${street.name}",
                    style = MaterialTheme.typography.titleLarge,
                    softWrap = true,
                )
                val details = buildList {
                    street.houseCount?.let { add(russianQuantity(it, "дом", "дома", "домов")) }
                    if (street.sources.size > 1) {
                        add(russianQuantity(street.sources.size, "аккаунт", "аккаунта", "аккаунтов"))
                    }
                }.joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть улицу" else "Раскрыть улицу",
            )
        }
    }
}

/** Карточка дома; счётчик подъездов показывается, если сервер его сообщил. */
@Composable
fun HouseListRow(house: House, streetName: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = if (expanded) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(start = 17.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$streetName · ${house.label}",
                    style = MaterialTheme.typography.titleMedium,
                    softWrap = true,
                )
                house.entranceCount?.let { count ->
                    Text(
                        russianQuantity(count, "подъезд", "подъезда", "подъездов"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть дом" else "Раскрыть дом",
            )
        }
    }
}

/**
 * Одна карточка подъезда в двух состояниях.
 * При раскрытии она сама вырастает, а не создаёт вторую карточку под собой.
 */
@Composable
fun EntranceListRow(
    entrance: Entrance,
    handedness: Handedness,
    expanded: Boolean,
    cameraState: CameraState,
    doorState: DoorActionState,
    codeState: CodeActionState,
    onOpenDoor: () -> Unit,
    onRequestCode: () -> Unit,
    onRetryCamera: () -> Unit,
    onToggle: () -> Unit,
) {
    val busy = doorState == DoorActionState.Sending || codeState == CodeActionState.Loading
    // При крупном системном шрифте кнопку переносим вниз, чтобы она не закрывала подпись.
    val useStackedLayout = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(if (expanded) 22.dp else 18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (expanded) 2.dp else 0.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (handedness == Handedness.RIGHT) {
                    CompactDoorAction(
                        visible = !expanded && !useStackedLayout,
                        state = doorState,
                        enabled = !busy,
                        onClick = onOpenDoor,
                        openingOnLeft = true,
                    )
                }
                EntranceTitle(
                    entrance = entrance,
                    expanded = expanded,
                    modifier = Modifier.weight(1f),
                )
                if (handedness == Handedness.LEFT) {
                    CompactDoorAction(
                        visible = !expanded && !useStackedLayout,
                        state = doorState,
                        enabled = !busy,
                        onClick = onOpenDoor,
                        openingOnLeft = false,
                    )
                }
                EntranceChevron(expanded = expanded, onClick = onToggle)
            }

            AnimatedVisibility(
                visible = !expanded && useStackedLayout,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (handedness == Handedness.RIGHT) Alignment.Start else Alignment.End,
                ) {
                    Spacer(Modifier.height(8.dp))
                    DoorActionButton(
                        state = doorState,
                        enabled = !busy,
                        onClick = onOpenDoor,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ExpandedEntranceContent(
                    entrance = entrance,
                    cameraState = cameraState,
                    doorState = doorState,
                    codeState = codeState,
                    busy = busy,
                    useStackedLayout = useStackedLayout,
                    handedness = handedness,
                    onOpenDoor = onOpenDoor,
                    onRequestCode = onRequestCode,
                    onRetryCamera = onRetryCamera,
                )
            }

            DoorStatusMessage(state = doorState)
        }
    }
}

/** Кнопка в свёрнутой строке появляется с выбранной стороны подписи. */
@Composable
private fun CompactDoorAction(
    visible: Boolean,
    state: DoorActionState,
    enabled: Boolean,
    onClick: () -> Unit,
    openingOnLeft: Boolean,
) {
    val edge = if (openingOnLeft) Alignment.Start else Alignment.End
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandHorizontally(expandFrom = edge),
        exit = fadeOut() + shrinkHorizontally(shrinkTowards = edge),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!openingOnLeft) Spacer(Modifier.width(8.dp))
            DoorActionButton(state = state, enabled = enabled, onClick = onClick)
            if (openingOnLeft) Spacer(Modifier.width(8.dp))
        }
    }
}

/** Показывает короткое имя в строке и полное имя внутри раскрытой карточки. */
@Composable
private fun EntranceTitle(
    entrance: Entrance,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Crossfade(targetState = expanded, label = "entrance-title") { showFullTitle ->
            Text(
                text = if (showFullTitle) {
                    entrance.label.toExpandedEntranceLabel()
                } else {
                    entrance.label.toCompactEntranceLabel()
                },
                style = if (showFullTitle) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.titleMedium
                },
                softWrap = true,
            )
        }
        if (entrance.sources.size > 1) {
            Text(
                russianQuantity(entrance.sources.size, "доступ", "доступа", "доступов"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                softWrap = true,
            )
        }
    }
}

/** Стрелка вынесена отдельно, чтобы обе версии строки выглядели одинаково. */
@Composable
private fun EntranceChevron(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Скрыть камеру" else "Показать камеру",
        )
    }
}

/** Сокращает только слово в начале. Серверное имя вроде «Калитка» не меняется. */
internal fun String.toCompactEntranceLabel(): String {
    val fullWord = "Подъезд"
    val wordEndsHere = length == fullWord.length || getOrNull(fullWord.length)?.isWhitespace() == true
    return if (startsWith(fullWord, ignoreCase = true) && wordEndsHere) {
        "Под." + drop(fullWord.length)
    } else {
        this
    }
}

/** Возвращает полное слово для заголовка раскрытой карточки. */
internal fun String.toExpandedEntranceLabel(): String {
    val shortWord = "Под."
    val wordEndsHere = length == shortWord.length || getOrNull(shortWord.length)?.isWhitespace() == true
    return if (startsWith(shortWord, ignoreCase = true) && wordEndsHere) {
        "Подъезд" + drop(shortWord.length)
    } else {
        this
    }
}

/** Показывает ошибку двери внутри той же карточки в обоих её состояниях. */
@Composable
private fun DoorStatusMessage(state: DoorActionState) {
    val message = when (state) {
        is DoorActionState.Failed -> state.message
        is DoorActionState.Uncertain -> state.message + ". Повтор — только вручную"
        else -> null
    }
    if (message != null) {
        Text(
            text = message,
            modifier = Modifier.padding(top = 7.dp, end = 10.dp),
            color = if (state is DoorActionState.Uncertain) WarningAmber
            else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Красная кнопка двери меняет цвет только после понятного ответа сервера. */
@Composable
private fun DoorActionButton(
    state: DoorActionState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerTarget = when (state) {
        DoorActionState.Opened -> SuccessGreen
        is DoorActionState.Uncertain -> WarningAmber
        else -> MaterialTheme.colorScheme.primary
    }
    val container by animateColorAsState(containerTarget, label = "door-button-color")

    Button(
        onClick = onClick,
        // Минимальная высота остаётся красивой, а крупный шрифт может увеличить кнопку.
        modifier = modifier.heightIn(min = 40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(containerColor = container),
    ) {
        when (state) {
            DoorActionState.Sending -> CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            DoorActionState.Opened -> Text("ОТКРЫТО")
            is DoorActionState.Failed -> Text("ЕЩЁ РАЗ")
            is DoorActionState.Uncertain -> Text("ПРОВЕРИТЬ")
            DoorActionState.Idle -> Text("ОТКРЫТЬ")
        }
    }
}

/** Кнопка кода живёт только в развёрнутой карточке подъезда. */
@Composable
private fun CodeActionButton(
    state: CodeActionState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (state == CodeActionState.Loading) {
            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
        } else {
            Text(if (state is CodeActionState.Failed) "ЕЩЁ" else "КОД")
        }
    }
}

/** Содержимое, в которое превращается сама раскрытая карточка подъезда. */
@Composable
private fun ExpandedEntranceContent(
    entrance: Entrance,
    cameraState: CameraState,
    doorState: DoorActionState,
    codeState: CodeActionState,
    busy: Boolean,
    useStackedLayout: Boolean,
    handedness: Handedness,
    onOpenDoor: () -> Unit,
    onRequestCode: () -> Unit,
    onRetryCamera: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(10.dp))
        CameraArea(
            cameraAvailable = entrance.cameraAvailable,
            cameraState = cameraState,
            onRetry = onRetryCamera,
        )
        Spacer(Modifier.height(12.dp))
        if (useStackedLayout) {
            // На крупном шрифте тексту хватает места; порядок также повторяет выбор руки.
            if (handedness == Handedness.RIGHT) {
                DoorActionButton(doorState, !busy, onOpenDoor, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CodeActionButton(codeState, !busy, onRequestCode, Modifier.fillMaxWidth())
            } else {
                CodeActionButton(codeState, !busy, onRequestCode, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                DoorActionButton(doorState, !busy, onOpenDoor, Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                if (handedness == Handedness.RIGHT) {
                    DoorActionButton(doorState, !busy, onOpenDoor, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    CodeActionButton(codeState, !busy, onRequestCode, Modifier.weight(1f))
                } else {
                    CodeActionButton(codeState, !busy, onRequestCode, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DoorActionButton(doorState, !busy, onOpenDoor, Modifier.weight(1f))
                }
            }
        }
        if (codeState is CodeActionState.Failed) {
            Text(
                text = codeState.message,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Выбирает между плеером, загрузкой и понятным сообщением об ошибке. */
@Composable
private fun CameraArea(
    cameraAvailable: Boolean,
    cameraState: CameraState,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(
                Brush.linearGradient(listOf(Color(0xFF1D2935), Color(0xFF080C11))),
                RoundedCornerShape(17.dp),
            ),
    ) {
        if (cameraState is CameraState.Ready) {
            LiveCameraPlayer(
                stream = cameraState.stream,
                onRetry = onRetry,
                playbackGeneration = cameraState.playbackGeneration,
            )
        } else {
            CameraMessage(
                loading = cameraState == CameraState.Loading,
                message = when {
                    !cameraAvailable -> "Камера недоступна"
                    cameraState is CameraState.Failed -> cameraState.message +
                        (cameraState.retryAfterSeconds?.let { "\nПовтор через $it с…" }.orEmpty())
                    else -> "Получаем видеопоток"
                },
            )
        }
    }
}

/** Заглушка камеры с индикатором и состоянием автоматического восстановления. */
@Composable
private fun CameraMessage(
    loading: Boolean,
    message: String,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.12f)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Небольшой индикатор внутри того уровня дерева, который сейчас загружается. */
@Composable
fun AddressLoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

/** После этого значения обычная строка становится слишком тесной для кнопок. */
private const val LARGE_FONT_SCALE = 1.3f
