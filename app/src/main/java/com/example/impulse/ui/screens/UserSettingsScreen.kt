package com.example.impulse.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.R
import com.example.impulse.security.SecureKeyManager
import com.example.impulse.security.SecureStorage
import com.example.impulse.ui.theme.ImpulseCard
import com.example.impulse.ui.theme.ImpulseSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserSettingsContent(
    modifier: Modifier = Modifier,
    clientName: String,
    onClientNameChange: (String) -> Unit,
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf(clientName) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pubKeyHash = remember {
        val pub = SecureStorage(context).getBytes(SecureStorage.KEY_KEM_PUBLIC)
        if (pub != null) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            com.example.impulse.util.bytesToHex(md.digest(pub)).take(8)
        } else ""
    }

    var showResetKeysDialog by remember { mutableStateOf(false) }

    // Export state
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportedPassword by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // Import state
    var showImportDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            isExporting = true
            scope.launch {
                try {
                    val password = withContext(Dispatchers.IO) {
                        SecureKeyManager.exportKeyBackup(context, uri)
                    }
                    exportedPassword = password
                    showExportPasswordDialog = true
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.backup_error_export, e.message ?: ""), Toast.LENGTH_LONG).show()
                } finally {
                    isExporting = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importUri = uri
            importPassword = ""
            showImportDialog = true
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Profile card ──────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = clientName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.user_chat_name),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = clientName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (pubKeyHash.isNotEmpty()) {
                        Text(
                            text = pubKeyHash,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // ── Name card ──────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.user_displayed_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = clientName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (pubKeyHash.isNotEmpty()) {
                    Text(
                        text = pubKeyHash,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        tempName = clientName
                        showNameDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.user_change_name))
                }
            }
        }

        // ── Backup card ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.backup_encryption_keys)) {
                Text(
                    text = stringResource(R.string.backup_encryption_keys_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { exportLauncher.launch("key_backup.enc") },
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isExporting) stringResource(R.string.backup_exporting) else stringResource(R.string.backup_export))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/octet-stream")) },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isImporting) stringResource(R.string.backup_importing) else stringResource(R.string.backup_import))
                }
            }
        }

        // ── Danger zone ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.user_danger_zone)) {
                Text(
                    text = stringResource(R.string.user_reset_keys_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showResetKeysDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.user_reset_keys))
                }
            }
        }
    }

    // ── Name dialog ────────────────────────────────────────────────
    if (showNameDialog) {
        var nameError by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }

        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            title = { Text(stringResource(R.string.user_change_name_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.user_change_name_desc))
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = tempName,
                        onValueChange = {
                            tempName = it
                            nameError = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        label = { Text(stringResource(R.string.user_username_label)) },
                        placeholder = { Text(stringResource(R.string.user_enter_name)) },
                        isError = nameError.isNotEmpty(),
                        supportingText = {
                            if (nameError.isNotEmpty()) {
                                Text(nameError)
                            }
                        },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempName.isBlank()) {
                            nameError = context.getString(R.string.user_name_empty_error)
                        } else if (tempName.length > 30) {
                            nameError = context.getString(R.string.user_name_too_long_error)
                        } else {
                            onClientNameChange(tempName)
                            showNameDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )

        LaunchedEffect(showNameDialog) {
            if (showNameDialog) {
                focusRequester.requestFocus()
            }
        }
    }

    // ── Reset keys dialog ──────────────────────────────────────────
    if (showResetKeysDialog) {
        AlertDialog(
            onDismissRequest = { showResetKeysDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    stringResource(R.string.user_reset_keys_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(stringResource(R.string.user_reset_keys_warning))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetKeysDialog = false
                        val storage = SecureStorage(context)
                        storage.remove(SecureStorage.KEY_KEM_PRIVATE)
                        storage.remove(SecureStorage.KEY_KEM_PUBLIC)
                        storage.remove(SecureStorage.KEY_DSA_PRIVATE)
                        storage.remove(SecureStorage.KEY_DSA_PUBLIC)
                        com.example.impulse.security.SecureKeyManager.clearInMemoryKeys()
                        Toast.makeText(context, context.getString(R.string.user_reset_keys_done), Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                ) {
                    Text(stringResource(R.string.common_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetKeysDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ── Export password dialog ─────────────────────────────────────
    if (showExportPasswordDialog && exportedPassword != null) {
        AlertDialog(
            onDismissRequest = {
                showExportPasswordDialog = false
                exportedPassword = null
            },
            shape = RoundedCornerShape(16.dp),
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text(
                    stringResource(R.string.backup_password_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.backup_password_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = exportedPassword!!,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("backup_password", exportedPassword)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.backup_password_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.backup_copy_password))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportPasswordDialog = false
                    exportedPassword = null
                }) {
                    Text(stringResource(R.string.backup_done))
                }
            },
        )
    }

    // ── Import password dialog ─────────────────────────────────────
    if (showImportDialog && importUri != null) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importUri = null
                importPassword = ""
            },
            shape = RoundedCornerShape(16.dp),
            icon = {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text(
                    stringResource(R.string.backup_restore_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.backup_restore_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text(stringResource(R.string.backup_restore_password_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Text(
                        text = stringResource(R.string.backup_restore_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        isImporting = true
                        val uri = importUri!!
                        val pw = importPassword
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    SecureKeyManager.importKeyBackup(context, uri, pw)
                                }
                                Toast.makeText(context, context.getString(R.string.backup_keys_restored), Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, context.getString(R.string.backup_error_import, e.message ?: ""), Toast.LENGTH_LONG).show()
                            } finally {
                                isImporting = false
                                importUri = null
                            }
                        }
                    },
                    enabled = importPassword.isNotBlank(),
                ) {
                    Text(stringResource(R.string.backup_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importUri = null
                    importPassword = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
