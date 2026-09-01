package com.maxgab.ghai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.maxgab.ghai.data.AppTheme
import com.maxgab.ghai.data.SettingsRepository
import com.maxgab.ghai.data.model.EffortLevel
import com.maxgab.ghai.ui.ChatUiState
import com.maxgab.ghai.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: ChatUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    var openRouterKey by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        openRouterKey = viewModel.getOpenRouterKey()
        githubToken = viewModel.getGithubToken()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            SettingsSection(title = "Credenciales") {
                SecretField(
                    label = "API key de OpenRouter",
                    value = openRouterKey,
                    onValueChange = { openRouterKey = it; viewModel.setOpenRouterKey(it) }
                )
                SecretField(
                    label = "Token de GitHub",
                    value = githubToken,
                    onValueChange = { githubToken = it; viewModel.setGithubToken(it) }
                )
                Text(
                    "El token de GitHub se usa con acceso total a lo que ese token permita: repos, " +
                        "Actions, issues, pull requests, releases, colaboradores, etc. La IA lo usará " +
                        "sin pedir confirmación cuando se lo pidas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.6f)
                )
            }

            SettingsSection(title = "Modelo") {
                ModelPicker(
                    current = state.settings.model,
                    onSelect = { viewModel.updateSettings { setModel(it) } }
                )
                Text(
                    "Modelo usado por defecto: Nemotron gratuito de OpenRouter. Puedes pegar cualquier " +
                        "otro ID de modelo compatible con function calling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.6f)
                )
            }

            SettingsSection(title = "Generación") {
                LabeledRow("Esfuerzo de razonamiento") {
                    EffortSelector(current = state.settings.effort, onSelect = { viewModel.updateSettings { setEffort(it) } })
                }
                LabeledSlider(
                    label = "Temperatura: ${"%.1f".format(state.settings.temperature)}",
                    value = state.settings.temperature.toFloat(),
                    range = 0f..1.5f,
                    onChange = { viewModel.updateSettings { setTemperature(it.toDouble()) } }
                )
                LabeledSlider(
                    label = "Máx. reintentos: ${state.settings.maxRetries}",
                    value = state.settings.maxRetries.toFloat(),
                    range = 1f..10f,
                    steps = 8,
                    onChange = { viewModel.updateSettings { setMaxRetries(it.toInt()) } }
                )
                LabeledSlider(
                    label = "Máx. pasos de herramienta por turno: ${state.settings.maxToolIterations}",
                    value = state.settings.maxToolIterations.toFloat(),
                    range = 5f..60f,
                    steps = 10,
                    onChange = { viewModel.updateSettings { setMaxToolIterations(it.toInt()) } }
                )
                SwitchRow(
                    label = "Nombrar chats automáticamente con IA",
                    checked = state.settings.autoTitleSessions,
                    onCheckedChange = { viewModel.updateSettings { setAutoTitleSessions(it) } }
                )
            }

            SettingsSection(title = "Apariencia") {
                ThemeSelector(current = state.settings.theme, onSelect = { viewModel.updateSettings { setTheme(it) } })
            }

            SettingsSection(title = "Uso de OpenRouter") {
                UsageSection(state)
            }

            SettingsSection(title = "Datos") {
                OutlinedButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Borrar todos los chats")
                }
            }

            Text(
                "GH AI · v1.0.0",
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Borrar todos los chats") },
            text = { Text("Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAllSessions(); showClearDialog = false }) { Text("Borrar") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row {
                IconButton(onClick = { visible = !visible }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Ocultar" else "Mostrar"
                    )
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar")
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ModelPicker(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember(current) { mutableStateOf(current) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onSelect(it) },
            label = { Text("ID del modelo") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Outlined.Visibility, contentDescription = "Presets")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SettingsRepository.MODEL_PRESETS.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = { text = preset; onSelect(preset); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun EffortSelector(current: EffortLevel, onSelect: (EffortLevel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EffortLevel.entries.forEach { level ->
            val selected = level == current
            Box(
                modifier = Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(level) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    level.label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ThemeSelector(current: AppTheme, onSelect: (AppTheme) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf(AppTheme.SYSTEM to "Sistema", AppTheme.LIGHT to "Claro", AppTheme.DARK to "Oscuro")
        options.forEach { (theme, label) ->
            val selected = theme == current
            Box(
                modifier = Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(theme) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun UsageSection(state: ChatUiState) {
    val usage = state.usage
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${usage.requestsToday} / ${usage.dailyLimit} peticiones usadas hoy",
            style = MaterialTheme.typography.bodyMedium
        )
        LinearProgressIndicator(
            progress = { usage.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        Text(
            "OpenRouter limita las cuentas gratuitas a ${usage.dailyLimit} peticiones diarias. " +
                "El contador se reinicia a medianoche (hora local) y se calcula localmente en el dispositivo.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.6f)
        )
    }
}
