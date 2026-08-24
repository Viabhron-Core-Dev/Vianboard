package com.example.keyboard.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.keyboard.desktop.DesktopShortcutsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopShortcutsSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var enabledShortcuts by remember {
        mutableStateOf(DesktopShortcutsManager.getEnabledShortcuts(context))
    }

    fun updateAndSave(newShortcuts: List<String>) {
        enabledShortcuts = newShortcuts
        DesktopShortcutsManager.saveEnabledShortcuts(context, newShortcuts)
    }

    val availableToAdd = DesktopShortcutsManager.ALL_AVAILABLE_SHORTCUTS.filter { item ->
        !enabledShortcuts.contains(item.id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desktop Shortcuts") },
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Navigation & Shortcut Pad",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Directional arrows (↑, ↓, ←, →) and core editing keys are always available as large buttons. You can add up to ${DesktopShortcutsManager.MAX_CUSTOM_SHORTCUTS} custom shortcut buttons to keep keys fat, spacious, and tactile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Shortcuts (${enabledShortcuts.size} / ${DesktopShortcutsManager.MAX_CUSTOM_SHORTCUTS})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (enabledShortcuts.isNotEmpty()) {
                        TextButton(onClick = { updateAndSave(DesktopShortcutsManager.DEFAULT_SHORTCUTS) }) {
                            Text("Reset to Arrows Only")
                        }
                    }
                }
            }

            if (enabledShortcuts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No custom shortcuts active.\nKeyboard will show huge, full-sized arrow keys.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                itemsIndexed(enabledShortcuts, key = { _, id -> id }) { index, id ->
                    val shortcut = DesktopShortcutsManager.getShortcutItem(id)
                    if (shortcut != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row {
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val list = enabledShortcuts.toMutableList()
                                                val temp = list[index - 1]
                                                list[index - 1] = list[index]
                                                list[index] = temp
                                                updateAndSave(list)
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < enabledShortcuts.size - 1) {
                                                val list = enabledShortcuts.toMutableList()
                                                val temp = list[index + 1]
                                                list[index + 1] = list[index]
                                                list[index] = temp
                                                updateAndSave(list)
                                            }
                                        },
                                        enabled = index < enabledShortcuts.size - 1,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(width = 56.dp, height = 36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = shortcut.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = shortcut.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = shortcut.sublabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val list = enabledShortcuts.toMutableList()
                                        list.removeAt(index)
                                        updateAndSave(list)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove shortcut",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    text = "Add More Shortcuts",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (availableToAdd.isEmpty()) {
                item {
                    Text(
                        text = "All available shortcuts are added.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(availableToAdd.size, key = { availableToAdd[it].id }) { i ->
                    val shortcut = availableToAdd[i]
                    val isAtMaxLimit = enabledShortcuts.size >= DesktopShortcutsManager.MAX_CUSTOM_SHORTCUTS

                    ListItem(
                        leadingContent = {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(width = 56.dp, height = 36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = shortcut.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        },
                        headlineContent = { Text(shortcut.name) },
                        supportingContent = { Text(shortcut.sublabel) },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    if (!isAtMaxLimit) {
                                        updateAndSave(enabledShortcuts + shortcut.id)
                                    }
                                },
                                enabled = !isAtMaxLimit
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add shortcut",
                                    tint = if (!isAtMaxLimit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        },
                        modifier = Modifier.clickable(enabled = !isAtMaxLimit) {
                            updateAndSave(enabledShortcuts + shortcut.id)
                        }
                    )
                    HorizontalDivider()
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
