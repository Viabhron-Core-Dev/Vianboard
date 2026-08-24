package com.example.keyboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.launch

data class PromptTemplateItem(
    val id: String,
    val title: String,
    val template: String,
    val category: String = "General"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalDictionaryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { ClipboardDatabase.getDatabase(context).personalDictionaryDao() }
    val scope = rememberCoroutineScope()
    val words by dao.getAllWords().collectAsState(initial = emptyList())
    
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Words & Shortcuts", "Prompt Templates")
    
    var showAddWordDialog by remember { mutableStateOf(false) }
    var showAddPromptDialog by remember { mutableStateOf(false) }

    // Persistent prompt templates in memory / SharedPreferences
    val promptPrefs = remember { context.getSharedPreferences("prompt_templates_prefs", android.content.Context.MODE_PRIVATE) }
    var promptTemplates by remember {
        val defaultPrompts = listOf(
            PromptTemplateItem("1", "Formal Email Opener", "Dear [Name],\n\nI hope this email finds you well. I am reaching out regarding...", "Email"),
            PromptTemplateItem("2", "Summarize Key Points", "Please summarize the main takeaways of the following text in bullet points:\n\n[Text]", "AI Assistant"),
            PromptTemplateItem("3", "Grammar & Tone Polish", "Please revise and polish the following paragraph to be clear, concise, and professional:\n\n[Text]", "Editing"),
            PromptTemplateItem("4", "Code Explanation", "Explain how the following code functions step by step, and highlight potential edge cases:\n\n```\n[Code]\n```", "Coding")
        )
        mutableStateOf(defaultPrompts)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Personal Dictionary & Prompts") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = {
                                selectedTabIndex = index
                                TheLogKeeper.getInstance(context).log("UI_ACTION", "PersonalDictionaryScreen", "SWITCH_TAB | tab=$title")
                            },
                            text = { Text(title) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (selectedTabIndex == 0) {
                    showAddWordDialog = true
                } else {
                    showAddPromptDialog = true
                }
            }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = if (selectedTabIndex == 0) "Add Word" else "Add Prompt Template"
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (selectedTabIndex == 0) {
                // Tab 0: Words & Shortcuts
                if (words.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No custom words yet. Tap + to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(words) { item ->
                            ListItem(
                                headlineContent = { Text(item.word) },
                                supportingContent = { if (!item.shortcut.isNullOrBlank()) Text("Shortcut: ${item.shortcut}") },
                                trailingContent = {
                                    IconButton(onClick = {
                                        scope.launch {
                                            dao.delete(item)
                                            TheLogKeeper.getInstance(context).log("USER_ACTION", "PersonalDictionary", "WORD_DELETED | word=${item.word}")
                                        }
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                // Tab 1: Prompt Templates
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "Reusable Prompt & Text Templates",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Tap + to create custom prompt shortcuts or boilerplate text expansions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    items(promptTemplates) { prompt ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(prompt.title, style = MaterialTheme.typography.titleSmall)
                                    IconButton(onClick = {
                                        promptTemplates = promptTemplates.filter { it.id != prompt.id }
                                        TheLogKeeper.getInstance(context).log("USER_ACTION", "PromptTemplates", "PROMPT_DELETED | id=${prompt.id}")
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete Template", modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    prompt.template,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAddWordDialog) {
            AddWordDialog(
                onDismiss = { showAddWordDialog = false },
                onAdd = { word, shortcut ->
                    scope.launch {
                        dao.insert(PersonalDictionaryItem(word = word, shortcut = shortcut.takeIf { it.isNotBlank() }))
                        TheLogKeeper.getInstance(context).log("USER_ACTION", "PersonalDictionary", "WORD_ADDED | word=$word | shortcut=$shortcut")
                        showAddWordDialog = false
                    }
                }
            )
        }

        if (showAddPromptDialog) {
            AddPromptDialog(
                onDismiss = { showAddPromptDialog = false },
                onAdd = { title, content ->
                    val newPrompt = PromptTemplateItem(
                        id = System.currentTimeMillis().toString(),
                        title = title,
                        template = content
                    )
                    promptTemplates = promptTemplates + newPrompt
                    TheLogKeeper.getInstance(context).log("USER_ACTION", "PromptTemplates", "PROMPT_ADDED | title=$title")
                    showAddPromptDialog = false
                }
            )
        }
    }
}

@Composable
fun AddWordDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var word by remember { mutableStateOf("") }
    var shortcut by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Word") },
        text = {
            Column {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = shortcut,
                    onValueChange = { shortcut = it },
                    label = { Text("Shortcut (Optional)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(word.trim(), shortcut.trim()) },
                enabled = word.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddPromptDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Prompt Template") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Template Title") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Prompt Body / Text") },
                    minLines = 3,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(title.trim(), template.trim()) },
                enabled = title.isNotBlank() && template.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
