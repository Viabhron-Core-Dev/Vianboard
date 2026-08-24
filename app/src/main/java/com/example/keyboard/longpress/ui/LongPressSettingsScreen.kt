package com.example.keyboard.longpress.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.keyboard.longpress.LongPressSettingsManager
import com.example.keyboard.longpress.PopupAction
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPressSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var isEditingComma by remember { mutableStateOf(false) }
    var isEditingSymbols by remember { mutableStateOf(false) }
    var isEditingUrlDomains by remember { mutableStateOf(false) }

    var longPressDelay by remember {
        mutableStateOf(LongPressSettingsManager.getLongPressDelay(context).toFloat())
    }
    var showHints by remember {
        mutableStateOf(LongPressSettingsManager.getShowHints(context))
    }

    when {
        isEditingComma -> {
            PopupKeyEditorDialog(
                title = "Comma (,) Key Popups",
                allActions = LongPressSettingsManager.ALL_COMMA_ACTIONS,
                initialKeys = LongPressSettingsManager.getCommaKeys(context),
                defaultKeys = LongPressSettingsManager.DEFAULT_COMMA,
                onSave = { newKeys ->
                    LongPressSettingsManager.saveCommaKeys(context, newKeys)
                    isEditingComma = false
                },
                onCancel = { isEditingComma = false }
            )
        }
        isEditingSymbols -> {
            PopupKeyEditorDialog(
                title = "Symbols (?123) Key Popups",
                allActions = LongPressSettingsManager.ALL_SYMBOLS_ACTIONS,
                initialKeys = LongPressSettingsManager.getSymbolsKeys(context),
                defaultKeys = LongPressSettingsManager.DEFAULT_SYMBOLS,
                onSave = { newKeys ->
                    LongPressSettingsManager.saveSymbolsKeys(context, newKeys)
                    isEditingSymbols = false
                },
                onCancel = { isEditingSymbols = false }
            )
        }
        isEditingUrlDomains -> {
            PopupKeyEditorDialog(
                title = "URL Domain Popups",
                allActions = LongPressSettingsManager.ALL_URL_DOMAINS,
                initialKeys = LongPressSettingsManager.getUrlDomains(context),
                defaultKeys = LongPressSettingsManager.DEFAULT_URL_DOMAINS,
                onSave = { newKeys ->
                    LongPressSettingsManager.saveUrlDomains(context, newKeys)
                    isEditingUrlDomains = false
                },
                onCancel = { isEditingUrlDomains = false }
            )
        }
        else -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Long Press & Key Popups") },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    item {
                        Text(
                            text = "Key Actions & Popups",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Comma (,) key popups") },
                            supportingContent = { Text("Emoji, Settings, Clipboard, Prompts, and One-Handed mode") },
                            modifier = Modifier.clickable { isEditingComma = true }
                        )
                        HorizontalDivider()
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Symbols (?123) key popups") },
                            supportingContent = { Text("Numpad, Desktop navigation, Alt symbols, and Prompts") },
                            modifier = Modifier.clickable { isEditingSymbols = true }
                        )
                        HorizontalDivider()
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("URL field (.com, .org) domains") },
                            supportingContent = { Text("Customize top-level domain popup list in browser & search fields") },
                            modifier = Modifier.clickable { isEditingUrlDomains = true }
                        )
                        HorizontalDivider()
                    }
                    item {
                        Text(
                            text = "Timing & Visuals",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
                        )
                    }
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Long-press delay",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${longPressDelay.roundToInt()} ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Duration to hold a key before showing secondary symbols or popup options",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = longPressDelay,
                                onValueChange = {
                                    longPressDelay = it
                                },
                                onValueChangeFinished = {
                                    LongPressSettingsManager.saveLongPressDelay(context, longPressDelay.roundToInt())
                                },
                                valueRange = 150f..700f,
                                steps = 10
                            )
                        }
                        HorizontalDivider()
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Show popup character hints") },
                            supportingContent = { Text("Display secondary symbols in key headers") },
                            trailingContent = {
                                Switch(
                                    checked = showHints,
                                    onCheckedChange = { checked ->
                                        showHints = checked
                                        LongPressSettingsManager.saveShowHints(context, checked)
                                    }
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun PopupKeyEditorDialog(
    title: String,
    allActions: List<PopupAction>,
    initialKeys: List<String>,
    defaultKeys: List<String>,
    onSave: (List<String>) -> Unit,
    onCancel: () -> Unit
) {
    var enabledKeys by remember { mutableStateOf(initialKeys.toSet()) }

    var orderedActions by remember {
        val initiallyEnabledIds = initialKeys.filter { id -> allActions.any { it.id == id } }
        val initiallyEnabled = initiallyEnabledIds.mapNotNull { id -> allActions.find { it.id == id } }
        val initiallyDisabled = allActions.filter { !initialKeys.contains(it.id) }
        mutableStateOf(initiallyEnabled + initiallyDisabled)
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(24.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    items(orderedActions.size, key = { orderedActions[it].id }) { index ->
                        val action = orderedActions[index]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Column {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val newList = orderedActions.toMutableList()
                                            val temp = newList[index - 1]
                                            newList[index - 1] = newList[index]
                                            newList[index] = temp
                                            orderedActions = newList
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                }
                                IconButton(
                                    onClick = {
                                        if (index < orderedActions.size - 1) {
                                            val newList = orderedActions.toMutableList()
                                            val temp = newList[index + 1]
                                            newList[index + 1] = newList[index]
                                            newList[index] = temp
                                            orderedActions = newList
                                        }
                                    },
                                    enabled = index < orderedActions.size - 1
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = action.symbol,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = action.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = enabledKeys.contains(action.id),
                                onCheckedChange = { isChecked ->
                                    enabledKeys = if (isChecked) {
                                        enabledKeys + action.id
                                    } else {
                                        enabledKeys - action.id
                                    }
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        enabledKeys = defaultKeys.toSet()
                        val defEnabled = defaultKeys.mapNotNull { id -> allActions.find { it.id == id } }
                        val defDisabled = allActions.filter { !defaultKeys.contains(it.id) }
                        orderedActions = defEnabled + defDisabled
                    }) {
                        Text("Reset Default")
                    }
                    Row(horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = {
                            val finalKeys = orderedActions.filter { enabledKeys.contains(it.id) }.map { it.id }
                            onSave(finalKeys)
                        }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
