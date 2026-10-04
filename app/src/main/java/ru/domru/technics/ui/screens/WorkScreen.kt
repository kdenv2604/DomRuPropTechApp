package ru.domru.technics.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.AccountAccessStatus
import ru.domru.technics.model.CodeActionState
import ru.domru.technics.model.DoorActionState
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.model.ThemeMode
import ru.domru.technics.ui.AppUiState

/** Экран помнит, какая нижняя панель сейчас открыта. */
private enum class OpenSheet { ACCOUNTS, SETTINGS }

/** Главный рабочий экран со всеми дверями из всех добавленных аккаунтов. */
@Composable
fun WorkScreen(
    state: AppUiState,
    snackbarHostState: SnackbarHostState,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onRefreshAccounts: () -> Unit,
    onBeginPasswordRenewal: (String) -> Unit,
    onCancelPasswordRenewal: () -> Unit,
    onRenewPassword: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onRefreshAddresses: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchResultClick: (AddressSearchResult) -> Unit,
    onLocalityClick: (Locality) -> Unit,
    onStreetClick: (Street) -> Unit,
    onHouseClick: (streetId: String, house: House) -> Unit,
    onEntranceClick: (streetId: String, houseId: String, entrance: Entrance) -> Unit,
    onOpenDoor: (Entrance) -> Unit,
    onRequestCode: (Entrance) -> Unit,
    onRetryCamera: (Entrance) -> Unit,
    onCloseCode: () -> Unit,
) {
    var openSheet by remember { mutableStateOf<OpenSheet?>(null) }
    val clipboard = LocalClipboardManager.current
    val visibleRows = remember(
        state.streets,
        state.housesByStreet,
        state.entrancesByHouse,
        state.selection,
        state.loadingStreetIds,
        state.loadingHouseIds,
    ) {
        buildVisibleAddressRows(state)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                WorkHeader(
                    activeAccountCount = state.accounts.count { account ->
                        account.isDemo ||
                            state.accountStatuses[account.id] == AccountAccessStatus.ACTIVE
                    },
                    totalAccountCount = state.accounts.size,
                    onAccountsClick = { openSheet = OpenSheet.ACCOUNTS },
                )
            }
            item(key = "search") {
                AddressSearchField(
                    query = state.searchQuery,
                    searching = state.searchBusy,
                    onQueryChange = onSearchChange,
                )
            }

            if (state.searchQuery.isNotBlank()) {
                if (!state.searchBusy && state.searchResults.isEmpty()) {
                    item(key = "search-empty") {
                        EmptySearchResult(query = state.searchQuery)
                    }
                }
                items(state.searchResults, key = { "search:${it.street.id}:${it.house.id}" }) { result ->
                    SearchResultRow(result = result, onClick = { onSearchResultClick(result) })
                }
            } else if (state.streetsLoading) {
                item(key = "street-loading") { AddressLoadingRow() }
            } else if (state.streets.isEmpty()) {
                item(key = "empty-tree") {
                    EmptyAddressTree(onRefresh = onRefreshAddresses)
                }
            } else {
                items(visibleRows, key = AddressListItem::key) { row ->
                    when (row) {
                        is AddressListItem.LocalityRow -> LocalityListRow(
                            locality = row.locality,
                            streetCount = row.streetCount,
                            expanded = state.selection.localityId == row.locality.id,
                            onClick = { onLocalityClick(row.locality) },
                        )
                        is AddressListItem.StreetRow -> StreetListRow(
                            street = row.street,
                            expanded = state.selection.streetId == row.street.id,
                            onClick = { onStreetClick(row.street) },
                        )
                        is AddressListItem.HouseRow -> HouseListRow(
                            house = row.house,
                            expanded = state.selection.houseId == row.house.id,
                            onClick = { onHouseClick(row.streetId, row.house) },
                        )
                        is AddressListItem.EntranceRow -> EntranceListRow(
                            entrance = row.entrance,
                            expanded = state.selection.entranceId == row.entrance.id,
                            doorState = state.doorActions[row.entrance.id] ?: DoorActionState.Idle,
                            codeState = state.codeActions[row.entrance.id] ?: CodeActionState.Idle,
                            onOpenDoor = { onOpenDoor(row.entrance) },
                            onToggle = {
                                onEntranceClick(row.streetId, row.houseId, row.entrance)
                            },
                        )
                        is AddressListItem.PreviewRow -> EntrancePreviewRow(
                            entrance = row.entrance,
                            cameraState = state.cameraStates[row.entrance.id]
                                ?: ru.domru.technics.model.CameraState.Idle,
                            doorState = state.doorActions[row.entrance.id] ?: DoorActionState.Idle,
                            codeState = state.codeActions[row.entrance.id] ?: CodeActionState.Idle,
                            onOpenDoor = { onOpenDoor(row.entrance) },
                            onRequestCode = { onRequestCode(row.entrance) },
                            onRetryCamera = { onRetryCamera(row.entrance) },
                        )
                        is AddressListItem.LoadingRow -> AddressLoadingRow()
                    }
                }
            }
        }
    }

    when (openSheet) {
        OpenSheet.ACCOUNTS -> AccountsSheet(
            accounts = state.accounts,
            accountStatuses = state.accountStatuses,
            onDismiss = { openSheet = null },
            onAddAccount = {
                openSheet = null
                onAddAccount()
            },
            onRemoveAccount = onRemoveAccount,
            onRenewPassword = onBeginPasswordRenewal,
            onRefreshAccounts = onRefreshAccounts,
            onOpenSettings = { openSheet = OpenSheet.SETTINGS },
        )
        OpenSheet.SETTINGS -> SettingsSheet(
            selectedTheme = state.themeMode,
            onThemeSelected = onThemeModeChange,
            onDismiss = { openSheet = null },
        )
        null -> Unit
    }

    state.passwordRenewalAccountId?.let { accountId ->
        state.accounts.firstOrNull { it.id == accountId }?.let { account ->
            PasswordRenewalDialog(
                account = account,
                busy = state.passwordRenewalBusy,
                errorMessage = state.passwordRenewalError,
                onConfirm = onRenewPassword,
                onDismiss = onCancelPasswordRenewal,
            )
        }
    }

    state.shownCode?.let { code ->
        TemporalCodeDialog(
            code = code,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            onDismiss = onCloseCode,
        )
    }
}

/** Верхняя карточка с названием портала и быстрым входом в список аккаунтов. */
@Composable
private fun WorkHeader(
    activeAccountCount: Int,
    totalAccountCount: Int,
    onAccountsClick: () -> Unit,
) {
    val background = Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.background),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .statusBarsPadding()
            .padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    // Это не логотип. Это зелёный адрес портала, с которым работает приложение.
                    Text(
                        "lk.proptech.ru",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                    )
                    Spacer(Modifier.height(13.dp))
                    Text("Все двери", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Доступ есть: $activeAccountCount из $totalAccountCount",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Surface(
                    modifier = Modifier.clickable(onClick = onAccountsClick),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Действующие логины",
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(totalAccountCount.toString(), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Поле поиска ждёт текст и показывает, выполняется ли запрос. */
@Composable
private fun AddressSearchField(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 3.dp),
        placeholder = { Text("Город, улица или дом") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            when {
                searching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить поиск")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/** Один найденный адрес с отдельными строками города, улицы и дома. */
@Composable
private fun SearchResultRow(result: AddressSearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Все части адреса переносятся по словам и не заходят под край карточки.
                Text(
                    text = result.street.locality.name,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelLarge,
                    softWrap = true,
                )
                Text(
                    text = result.street.name,
                    style = MaterialTheme.typography.titleMedium,
                    softWrap = true,
                )
                Text(
                    result.house.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    softWrap = true,
                )
            }
        }
    }
}

/** Сообщает, что введённый адрес не найден ни в одном действующем аккаунте. */
@Composable
private fun EmptySearchResult(query: String) {
    EmptyMessage(
        title = "Ничего не найдено",
        subtitle = "Для запроса «$query» нет доступных домов",
    )
}

/** Показывается, когда сервер не вернул ни одного доступного адреса. */
@Composable
private fun EmptyAddressTree(onRefresh: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyMessage(
            title = "Дверей пока нет",
            subtitle = "Проверь соединение и запроси список ещё раз",
        )
        Button(onClick = onRefresh) {
            Text("ОБНОВИТЬ")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Общая аккуратная карточка для пустого состояния и подсказки. */
@Composable
private fun EmptyMessage(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(7.dp))
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
