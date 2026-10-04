package ru.domru.technics.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.domru.technics.model.TemporalCode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Код показывается крупно, чтобы его было удобно набрать у подъезда. */
@Composable
fun TemporalCodeDialog(
    code: TemporalCode,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = "Код доступа",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = code.value.toList().joinToString(" "),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(20.dp),
                        )
                        .padding(vertical = 22.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                )
                val validity = code.validUntilEpochMillis?.let(::formatTime)
                Text(
                    text = validity?.let { "Действует по данным сервера до $it" }
                        ?: "Срок действия сообщает сервер",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onCopy(code.value) }) {
                    Text("КОПИРОВАТЬ")
                }
                TextButton(onClick = onDismiss) {
                    Text("ГОТОВО")
                }
            }
        },
    )
}

/** Показывает только часы и минуты по часовому поясу телефона. */
private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale("ru")).format(Date(epochMillis))
