package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.impulse.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.security.SecureStorage

@Composable
fun UserSettingsContent(
    modifier: Modifier = Modifier,
    clientName: String,
    onClientNameChange: (String) -> Unit,
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf(clientName) }
    val context = LocalContext.current
    val pubKeyHash = remember {
        val pub = SecureStorage(context).getBytes(SecureStorage.KEY_KEM_PUBLIC)
        if (pub != null) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(pub).joinToString("") { "%02x".format(it) }.take(8)
        } else ""
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
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
    }

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
}
