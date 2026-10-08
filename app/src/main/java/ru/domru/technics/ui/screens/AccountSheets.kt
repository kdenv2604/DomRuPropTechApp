@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.domru.technics.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.domru.technics.BuildConfig
import ru.domru.technics.model.AccountProfile
import ru.domru.technics.model.AccountAccessStatus
import ru.domru.technics.model.allowsAccountRecoveryActions
import ru.domru.technics.model.ThemeMode
import ru.domru.technics.ui.theme.SuccessGreen
import ru.domru.technics.ui.theme.WarningAmber

/** Показывает сохранённые логины, их состояние и безопасные действия с ними. */
@Composable
fun AccountsSheet(
    accounts: List<AccountProfile>,
    accountStatuses: Map<String, AccountAccessStatus>,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onRenewPassword: (String) -> Unit,
    onRefreshAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Сохранённые логины", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Доступные через них двери собраны в один общий список.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))

            accounts.forEachIndexed { index, account ->
                AccountRow(
                    account = account,
                    status = accountStatuses[account.id] ?: AccountAccessStatus.CHECKING,
                    onRenewPassword = { onRenewPassword(account.id) },
                    onRemove = { onRemoveAccount(account.id) },
                )
                if (index != accounts.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 5.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = onRefreshAccounts,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("ПРОВЕРИТЬ ДОСТУПЫ")
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAddAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("ДОБАВИТЬ ЛОГИН И ПАРОЛЬ")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("НАСТРОЙКИ")
            }
        }
    }
}

/** Строка одного логина; опасные кнопки скрыты у исправного доступа. */
@Composable
private fun AccountRow(
    account: AccountProfile,
    status: AccountAccessStatus,
    onRenewPassword: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.padding(end = 12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = account.title.take(1).uppercase(),
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(account.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    account.loginHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AccountStatusLine(account = account, status = status)
            }
        }
        val passwordNeedsFirstSave = !account.isDemo && !account.hasSavedPassword
        val actionsAvailable = account.isDemo ||
            passwordNeedsFirstSave ||
            status.allowsAccountRecoveryActions()
        if (actionsAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!account.isDemo &&
                    (passwordNeedsFirstSave || status.allowsAccountRecoveryActions())
                ) {
                    TextButton(onClick = onRenewPassword) {
                        Text(if (passwordNeedsFirstSave) "СОХРАНИТЬ ПАРОЛЬ" else "НОВЫЙ ПАРОЛЬ")
                    }
                }
                if (account.isDemo || status.allowsAccountRecoveryActions()) {
                    TextButton(onClick = onRemove) {
                        Text(
                            if (account.isDemo) "ВЫЙТИ" else "УДАЛИТЬ",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** Цветом и коротким текстом объясняет текущее состояние аккаунта. */
@Composable
private fun AccountStatusLine(account: AccountProfile, status: AccountAccessStatus) {
    val actualStatus = if (account.isDemo) AccountAccessStatus.ACTIVE else status
    val text = when {
        account.isDemo -> "Демо-доступ"
        !account.hasSavedPassword && actualStatus == AccountAccessStatus.ACTIVE ->
            "Доступ действует — сохрани пароль для автовхода"
        !account.hasSavedPassword -> "Для автовхода нужно сохранить пароль"
        actualStatus == AccountAccessStatus.CHECKING -> "Проверяем доступ…"
        actualStatus == AccountAccessStatus.ACTIVE -> "Доступ действует"
        actualStatus == AccountAccessStatus.NEEDS_PASSWORD -> "Доступ потерян — нужен новый пароль"
        actualStatus == AccountAccessStatus.ACCESS_DENIED -> "Сервер запретил доступ"
        else -> "Не удалось проверить доступ"
    }
    val color = when {
        !account.isDemo && !account.hasSavedPassword -> WarningAmber
        else -> when (actualStatus) {
            AccountAccessStatus.ACTIVE -> SuccessGreen
            AccountAccessStatus.NEEDS_PASSWORD,
            AccountAccessStatus.ACCESS_DENIED,
            -> MaterialTheme.colorScheme.error
            AccountAccessStatus.UNAVAILABLE -> WarningAmber
            AccountAccessStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Row(
        modifier = Modifier.padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (actualStatus == AccountAccessStatus.CHECKING) {
            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
        } else {
            Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
        }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Просит текущий или новый пароль, но не разрешает случайно изменить сохранённый логин. */
@Composable
fun PasswordRenewalDialog(
    account: AccountProfile,
    busy: Boolean,
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(account.id) { mutableStateOf("") }
    val firstSave = !account.hasSavedPassword
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (firstSave) "Сохранить пароль" else "Новый пароль") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Логин: ${account.loginHint}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (firstSave) "Текущий пароль" else "Новый пароль") },
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    isError = errorMessage != null,
                )
                Text(
                    "Пароль проверяется сервером и сохраняется только в зашифрованном " +
                        "хранилище Android. Он нужен приложению для автоматического входа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank() && !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("ПРОВЕРИТЬ И СОХРАНИТЬ")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("ОТМЕНА")
            }
        },
    )
}

/** Только здесь человек может выбрать светлое или тёмное оформление. */
@Composable
fun SettingsSheet(
    selectedTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            ThemeMode.entries.forEach { mode ->
                ThemeOption(
                    title = when (mode) {
                        ThemeMode.SYSTEM -> "Как в телефоне"
                        ThemeMode.LIGHT -> "Светлое"
                        ThemeMode.DARK -> "Тёмное"
                    },
                    subtitle = when (mode) {
                        ThemeMode.SYSTEM -> "Меняется вместе с настройкой Android"
                        ThemeMode.LIGHT -> "Светлый фон и белые карточки"
                        ThemeMode.DARK -> "Тёмный фон и мягкий контраст"
                    },
                    selected = selectedTheme == mode,
                    onClick = { onThemeSelected(mode) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Версия ${BuildConfig.VERSION_NAME.removeSuffix("-debug")} · Сборка ${BuildConfig.VERSION_CODE}",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Один вариант темы с кружком, который отмечает текущий выбор. */
@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            ) {
                Text(
                    text = if (selected) "✓" else "",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Black,
                )
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
