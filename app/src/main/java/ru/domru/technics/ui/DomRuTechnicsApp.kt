package ru.domru.technics.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.domru.technics.ui.screens.AddLoginScreen
import ru.domru.technics.ui.screens.WorkScreen
import ru.domru.technics.ui.theme.DomRuTechnicsTheme

/** Выбирает нужный экран и применяет тему ко всему приложению. */
@Composable
fun DomRuTechnicsApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    DomRuTechnicsTheme(themeMode = state.themeMode) {
        if (state.showLogin) {
            AddLoginScreen(
                hasSavedAccounts = state.accounts.isNotEmpty(),
                isBusy = state.loginBusy,
                errorMessage = state.loginError,
                demoAvailable = ru.domru.technics.BuildConfig.DEBUG,
                onAdd = viewModel::addLoginAndPassword,
                onCancel = viewModel::cancelAddingLogin,
                onOpenDemo = viewModel::openDemo,
            )
        } else {
            WorkScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onAddAccount = viewModel::showAddLoginAndPassword,
                onRemoveAccount = viewModel::removeAccount,
                onRefreshAccounts = viewModel::refreshAccountAccesses,
                onBeginPasswordRenewal = viewModel::beginPasswordRenewal,
                onCancelPasswordRenewal = viewModel::cancelPasswordRenewal,
                onRenewPassword = viewModel::renewPassword,
                onThemeModeChange = viewModel::setThemeMode,
                onRefreshAddresses = viewModel::refreshAddresses,
                onSearchChange = viewModel::updateSearchQuery,
                onSearchResultClick = viewModel::openSearchResult,
                onLocalityClick = viewModel::toggleLocality,
                onStreetClick = viewModel::toggleStreet,
                onHouseClick = viewModel::toggleHouse,
                onEntranceClick = viewModel::toggleEntrance,
                onOpenDoor = viewModel::openDoor,
                onRequestCode = viewModel::requestTemporalCode,
                onRetryCamera = viewModel::retryCamera,
                onCloseCode = viewModel::closeTemporalCode,
            )
        }
    }
}
