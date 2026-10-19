package com.taskly.android.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sign in / registration / two-factor code / password reset (AUTH-1…4). */
@Composable
fun AuthScreen(vm: MainViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var totp by rememberSaveable { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    val needsTotp by vm.needsTotp.collectAsState()
    val error by vm.error.collectAsState()
    val resetSent by vm.resetSent.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name_title), style = MaterialTheme.typography.headlineMedium)
        val field = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        OutlinedTextField(
            value = email, onValueChange = { email = it }, singleLine = true,
            label = { Text(stringResource(R.string.auth_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = field.testTag("auth_email"),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true,
            label = { Text(stringResource(R.string.auth_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = field.testTag("auth_password"),
        )
        if (needsTotp && !register) {
            Text(stringResource(R.string.auth_totp_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = totp, onValueChange = { totp = it.filter(Char::isDigit).take(8) }, singleLine = true,
                label = { Text(stringResource(R.string.auth_totp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = field.testTag("auth_totp"),
            )
        }
        error?.let { Text(stringResource(R.string.auth_error, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("auth_error")) }
        if (resetSent) Text(stringResource(R.string.auth_reset_sent), modifier = Modifier.testTag("auth_reset_sent"))
        Button(
            onClick = { vm.login(email.trim(), password, register, totp.ifBlank { null }) },
            enabled = email.isNotBlank() && password.isNotEmpty(),
            modifier = field.testTag("auth_submit"),
        ) { Text(stringResource(if (register) R.string.auth_register else R.string.auth_sign_in)) }
        TextButton(onClick = { register = !register; vm.clearError() }, modifier = Modifier.testTag("auth_toggle")) {
            Text(stringResource(if (register) R.string.auth_to_sign_in else R.string.auth_to_register))
        }
        if (!register) {
            TextButton(onClick = { vm.forgotPassword(email.trim()) }, enabled = email.isNotBlank(), modifier = Modifier.testTag("auth_forgot")) {
                Text(stringResource(R.string.auth_forgot))
            }
        }
    }
}

/** Reminder defaults (synced with the account) and the Android permissions they need (AND-N1…N3). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, system: SystemActions) {
    val settings by vm.settings.collectAsState()
    val loadError by vm.settingsError.collectAsState()
    Column(Modifier.fillMaxSize().testTag("settings")) {
        BackTopBar(stringResource(R.string.settings_title), onBack = { vm.screen.value = MainViewModel.Screen.TASKS })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Permissions first: without them reminders cannot be shown on time
            if (system.notificationsGranted) {
                Text(stringResource(R.string.settings_notifications_on), modifier = Modifier.testTag("notifications_on"))
            } else {
                OutlinedButton(onClick = system.requestNotifications, modifier = Modifier.testTag("request_notifications")) {
                    Text(stringResource(R.string.settings_notifications))
                }
            }
            if (system.exactAlarmsAllowed) {
                Text(stringResource(R.string.settings_exact_on))
            } else {
                OutlinedButton(onClick = system.openExactAlarmSettings, modifier = Modifier.testTag("request_exact")) {
                    Text(stringResource(R.string.settings_exact))
                }
                Text(stringResource(R.string.settings_exact_off_hint), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodySmall)

            if (loadError) Text(stringResource(R.string.settings_load_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings_error"))
            val s = settings ?: return@Column

            Text(stringResource(R.string.settings_reminders), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_push), modifier = Modifier.weight(1f))
                Switch(
                    checked = s.pushReminders,
                    onCheckedChange = { vm.saveSettings(JsonObject(mapOf("pushReminders" to JsonPrimitive(it)))) },
                    modifier = Modifier.testTag("push_switch"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_digest), modifier = Modifier.weight(1f))
                Switch(
                    checked = s.digestEnabled,
                    onCheckedChange = { vm.saveSettings(JsonObject(mapOf("digestEnabled" to JsonPrimitive(it)))) },
                )
            }
            Text(stringResource(R.string.settings_offsets))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in listOf(0, 5, 15, 30, 60, 1440)) {
                    val on = m in s.reminderOffsets
                    FilterChip(
                        selected = on,
                        enabled = on || s.reminderOffsets.size < 5, // the server keeps up to 5
                        onClick = {
                            val next = (if (on) s.reminderOffsets - m else s.reminderOffsets + m).distinct().sorted()
                            vm.saveSettings(JsonObject(mapOf("reminderOffsets" to JsonArray(next.map { JsonPrimitive(it) }))))
                        },
                        label = { Text(stringResource(R.string.settings_minutes, m)) },
                        modifier = Modifier.testTag("offset_$m"),
                    )
                }
            }
            var dayTime by remember(s.reminderDayTime) { mutableStateOf(s.reminderDayTime) }
            val valid = Regex("""^([01]\d|2[0-3]):[0-5]\d$""").matches(dayTime)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = dayTime, onValueChange = { dayTime = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_day_time)) },
                    isError = !valid,
                    modifier = Modifier.weight(1f).testTag("day_time"),
                )
                if (valid && dayTime != s.reminderDayTime) {
                    TextButton(onClick = { vm.saveSettings(JsonObject(mapOf("reminderDayTime" to JsonPrimitive(dayTime)))) }, modifier = Modifier.testTag("day_time_save")) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
            if (!valid) Text(stringResource(R.string.settings_time_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
