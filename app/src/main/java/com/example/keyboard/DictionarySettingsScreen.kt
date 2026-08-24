package com.example.keyboard

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import com.example.keyboard.engine.KeyboardEngineCoordinator
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySettingsScreen(onClose: () -> Unit, onOpenPersonalDictionary: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE) }
    
    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }
    
    var autoCorrectAggressiveness by remember { 
        mutableStateOf(prefs.getFloat("autocorrect_aggressiveness", 1.0f)) 
    }
    var nextWordPrediction by remember { 
        mutableStateOf(prefs.getBoolean("next_word_prediction", true)) 
    }

    val supportedLanguages = listOf(
        "en" to "English",
        "fr" to "French (Français)",
        "es" to "Spanish (Español)",
        "de" to "German (Deutsch)",
        "it" to "Italian (Italiano)",
        "pt" to "Portuguese (Português)"
    )

    val secondaryOptions = listOf("none" to "None (Disabled)") + supportedLanguages

    var primaryLanguage by remember {
        mutableStateOf(prefs.getString("primary_language", "en") ?: "en")
    }
    var secondaryLanguage by remember {
        mutableStateOf(prefs.getString("secondary_language", "none") ?: "none")
    }

    var primaryExpanded by remember { mutableStateOf(false) }
    var secondaryExpanded by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val coordinator = remember { KeyboardEngineCoordinator.getInstance(context) }
    val personalDao = remember { ClipboardDatabase.getDatabase(context).personalDictionaryDao() }

    // HeliBoard Backup ZIP Launcher (.zip)
    val backupZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            TheLogKeeper.getInstance(context).log("INFO", "DictionarySettings", "HELIPACK_ZIP_TRIGGERED | uri=[$it]")
            scope.launch {
                try {
                    withContext(Dispatchers.Main) {
                        isProcessing = true
                        processingMessage = "Extracting HeliBoard backup (.zip)..."
                    }
                    
                    var dictCount = 0
                    var userWordCount = 0
                    
                    withContext(Dispatchers.IO) {
                        val dictDir = File(context.filesDir, "dictionaries")
                        if (!dictDir.exists()) dictDir.mkdirs()
                        
                        context.contentResolver.openInputStream(it)?.use { rawStream ->
                            val zipInput = ZipInputStream(rawStream)
                            var entry = zipInput.nextEntry
                            
                            while (entry != null) {
                                val name = entry.name
                                val lowerName = name.lowercase()
                                
                                if (!entry.isDirectory) {
                                    if (lowerName.endsWith(".dict")) {
                                        val simpleName = File(name).name
                                        val destFile = File(dictDir, "imported_${System.currentTimeMillis()}_$simpleName")
                                        destFile.outputStream().use { out ->
                                            zipInput.copyTo(out)
                                        }
                                        val success = coordinator.loadBinaryDictionary(destFile)
                                        if (success) dictCount++
                                        TheLogKeeper.getInstance(context).log(
                                            "INFO", 
                                            "DictionarySettings", 
                                            "ZIP_DICT_LOADED | file=$simpleName | success=$success"
                                        )
                                    } else if (lowerName.contains("user_dict") || lowerName.contains("personal_dict") ||
                                        lowerName.endsWith(".tsv") || lowerName.endsWith(".txt")) {
                                        val byteOut = ByteArrayOutputStream()
                                        val buffer = ByteArray(4096)
                                        var len: Int
                                        while (zipInput.read(buffer).also { len = it } > 0) {
                                            byteOut.write(buffer, 0, len)
                                        }
                                        val content = byteOut.toString("UTF-8")
                                        content.lines().forEach { line ->
                                            val trimmed = line.trim()
                                            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                                val parts = trimmed.split("\t", " ")
                                                if (parts.isNotEmpty()) {
                                                    val word = parts[0]
                                                    val shortcut = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else null
                                                    personalDao.insert(PersonalDictionaryItem(word = word, shortcut = shortcut, frequency = 250))
                                                    userWordCount++
                                                }
                                            }
                                        }
                                    }
                                }
                                zipInput.closeEntry()
                                entry = zipInput.nextEntry
                            }
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        Toast.makeText(
                            context,
                            "Imported $dictCount dictionaries and $userWordCount user words!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        Toast.makeText(context, "Failed to import backup: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Binary Dict File Launcher (.dict)
    val binaryDictLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    withContext(Dispatchers.Main) {
                        isProcessing = true
                        processingMessage = "Importing binary dictionary..."
                    }
                    val success = withContext(Dispatchers.IO) {
                        val dictDir = File(context.filesDir, "dictionaries")
                        if (!dictDir.exists()) dictDir.mkdirs()
                        val destFile = File(dictDir, "imported_${System.currentTimeMillis()}.dict")
                        context.contentResolver.openInputStream(it)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        coordinator.loadBinaryDictionary(destFile)
                    }
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        if (success) {
                            Toast.makeText(context, "Binary dictionary imported successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to parse binary dictionary", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dictionary & Prediction") },
                navigationIcon = {
                    IconButton(onClick = { if (!isProcessing) onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(processingMessage)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("Multilingual & Dual Dictionary", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Enable dual language prediction like HeliBoard. The keyboard suggests words from your primary language while seamlessly predicting words from your secondary language when typing foreign terms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                // Primary Language
                ExposedDropdownMenuBox(
                    expanded = primaryExpanded,
                    onExpandedChange = { primaryExpanded = !primaryExpanded }
                ) {
                    OutlinedTextField(
                        value = supportedLanguages.find { it.first == primaryLanguage }?.second ?: primaryLanguage,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Primary Language") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = primaryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = primaryExpanded,
                        onDismissRequest = { primaryExpanded = false }
                    ) {
                        supportedLanguages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    primaryLanguage = code
                                    prefs.edit().putString("primary_language", code).apply()
                                    primaryExpanded = false
                                    TheLogKeeper.getInstance(context).log("INFO", "DictSettings", "PRIMARY_LANG_CHANGED | lang=$code")
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Secondary Language
                ExposedDropdownMenuBox(
                    expanded = secondaryExpanded,
                    onExpandedChange = { secondaryExpanded = !secondaryExpanded }
                ) {
                    OutlinedTextField(
                        value = secondaryOptions.find { it.first == secondaryLanguage }?.second ?: secondaryLanguage,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Secondary Language (Multilingual Typing)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = secondaryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = secondaryExpanded,
                        onDismissRequest = { secondaryExpanded = false }
                    ) {
                        secondaryOptions.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    secondaryLanguage = code
                                    prefs.edit().putString("secondary_language", code).apply()
                                    secondaryExpanded = false
                                    TheLogKeeper.getInstance(context).log("INFO", "DictSettings", "SECONDARY_LANG_CHANGED | lang=$code")
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))

                Text("Correction & Prediction", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                
                Text("Auto-Correct Aggressiveness", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = autoCorrectAggressiveness,
                    onValueChange = { 
                        autoCorrectAggressiveness = it
                        prefs.edit().putFloat("autocorrect_aggressiveness", it).apply()
                    },
                    valueRange = 0f..1f,
                    steps = 10
                )
                val levelText = when {
                    autoCorrectAggressiveness < 0.2f -> "Off"
                    autoCorrectAggressiveness < 0.5f -> "Mild"
                    autoCorrectAggressiveness < 0.8f -> "Moderate"
                    else -> "Aggressive"
                }
                Text("Current sensitivity: $levelText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Next-Word Prediction")
                        Text(
                            "Use bigrams and context to suggest the next word.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = nextWordPrediction,
                        onCheckedChange = {
                            nextWordPrediction = it
                            prefs.edit().putBoolean("next_word_prediction", it).apply()
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))

                Text("HeliBoard Import & Backups", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Import entire HeliBoard backups (.zip) containing binary dictionaries (.dict) and personal dictionary entries (.tsv / .txt).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { backupZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import HeliBoard Backup (.zip)")
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = { binaryDictLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Binary Dictionary (.dict)")
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                
                Text("Personal Dictionary & Prompts", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Review or edit your imported words, shortcuts, and text prompt templates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedButton(
                    onClick = onOpenPersonalDictionary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Words & Prompts")
                }
            }
        }
    }
}
