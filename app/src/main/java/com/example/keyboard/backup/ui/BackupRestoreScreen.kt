package com.example.keyboard.backup.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.backup.BackupRestoreManager
import com.example.keyboard.backup.BackupStats
import com.example.logkeeper.TheLogKeeper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logKeeper = remember { TheLogKeeper.getInstance(context) }

    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }
    var lastRestoreStats by remember { mutableStateOf<BackupStats?>(null) }
    var lastExportSuccess by remember { mutableStateOf<String?>(null) }

    // Export Document Launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            processingMessage = "Exporting Viaboard database & settings..."
            val result = BackupRestoreManager.writeBackupToUri(context, uri)
            isProcessing = false
            if (result.isSuccess) {
                lastExportSuccess = "Backup successfully created and saved!"
                Toast.makeText(context, "Backup exported successfully", Toast.LENGTH_SHORT).show()
                logKeeper.log("INFO", "BackupScreen", "BACKUP_EXPORT_SUCCESS | uri=$uri")
            } else {
                Toast.makeText(context, "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                logKeeper.log("ERROR", "BackupScreen", "BACKUP_EXPORT_ERROR | err=${result.exceptionOrNull()?.message}")
            }
        }
    }

    // Import Document Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            processingMessage = "Restoring databases, prompts, and preferences..."
            val result = BackupRestoreManager.restoreBackupFromUri(context, uri)
            isProcessing = false
            if (result.isSuccess) {
                val stats = result.getOrNull()
                lastRestoreStats = stats
                Toast.makeText(context, "Backup restored successfully!", Toast.LENGTH_SHORT).show()
                logKeeper.log("INFO", "BackupScreen", "BACKUP_RESTORE_SUCCESS | clips=${stats?.clipboardCount} | personal=${stats?.personalDictCount}")
            } else {
                Toast.makeText(context, "Restore failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                logKeeper.log("ERROR", "BackupScreen", "BACKUP_RESTORE_ERROR | err=${result.exceptionOrNull()?.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
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
                    Text(processingMessage, style = MaterialTheme.typography.bodyMedium)
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
                Text(
                    text = "Full App Backup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Export your entire Viaboard setup into a single portable JSON file. Includes clipboard clips, pinned items, custom words, prompt templates, learned n-grams, desktop shortcuts layout, toolbar configuration, and personal settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val fileName = "viaboard_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
                        exportLauncher.launch(fileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export Full App Backup (.json)")
                }

                if (lastExportSuccess != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(lastExportSuccess!!, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Restore from Backup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Select a previously exported Viaboard backup file (.json) to restore your data and configurations seamlessly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore from Backup (.json)")
                }

                if (lastRestoreStats != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Restore Completed Successfully",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("• Clipboard Clips: ${lastRestoreStats!!.clipboardCount}", fontSize = 13.sp)
                            Text("• Personal Words & Prompts: ${lastRestoreStats!!.personalDictCount}", fontSize = 13.sp)
                            Text("• Custom Dictionary Words: ${lastRestoreStats!!.dictWordsCount}", fontSize = 13.sp)
                            Text("• Learned N-Grams: ${lastRestoreStats!!.wordsCount + lastRestoreStats!!.bigramsCount}", fontSize = 13.sp)
                            Text("• Restored Settings: ${lastRestoreStats!!.prefsCount} keys", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
