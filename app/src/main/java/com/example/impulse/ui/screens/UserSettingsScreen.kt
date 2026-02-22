package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    clientName: String,
    onClientNameChange: (String) -> Unit,
    onBack: () -> Unit
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf(clientName) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 56.dp), // Компенсация высоты AppBar
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Заголовок с кнопкой назад
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "Настройки пользователя",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Имя в чате",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = clientName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Button(
                        onClick = {
                            tempName = clientName
                            showNameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Изменить имя")
                    }
                }
            }
        }

        // Диалог для изменения имени
        if (showNameDialog) {
            var nameError by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }

            AlertDialog(
                onDismissRequest = { showNameDialog = false },
                title = { Text("Изменить имя") },
                text = {
                    Column {
                        Text("Введите новое имя для отображения в чате:")
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
                            label = { Text("Имя пользователя") },
                            placeholder = { Text("Введите имя") },
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
                                nameError = "Имя не может быть пустым"
                            } else if (tempName.length > 30) {
                                nameError = "Имя слишком длинное (макс. 30 символов)"
                            } else {
                                onClientNameChange(tempName)
                                showNameDialog = false
                            }
                        }
                    ) {
                        Text("Сохранить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNameDialog = false }) {
                        Text("Отмена")
                    }
                }
            )

            // Устанавливаем фокус на поле ввода при открытии диалога
            LaunchedEffect(showNameDialog) {
                if (showNameDialog) {
                    focusRequester.requestFocus()
                }
            }
        }
    }
}