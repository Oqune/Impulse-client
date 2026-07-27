package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.impulse.R
import com.example.impulse.util.BiometricHelper

@Composable
fun BiometricLockScreen(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isAuthenticating by remember { mutableStateOf(false) }

    val biometricHelper = remember { BiometricHelper(context) }

    val startAuthentication = remember<(androidx.fragment.app.FragmentActivity) -> Unit> {
        { activity ->
            isAuthenticating = true
            biometricHelper.authenticate(
                activity = activity,
                onSuccess = {
                    isAuthenticating = false
                    onUnlock()
                },
                onError = { error ->
                    isAuthenticating = false
                    errorMessage = error
                },
                onFailed = {
                    isAuthenticating = false
                    errorMessage = context.getString(R.string.biometric_failed)
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        val activity = context as? androidx.fragment.app.FragmentActivity
        if (activity != null && biometricHelper.isBiometricAvailable()) {
            startAuthentication(activity)
        } else {
            errorMessage = context.getString(R.string.biometric_unavailable)
        }
    }

    DecorativeBackground(
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(R.string.biometric_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = stringResource(R.string.biometric_desc),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            if (isAuthenticating) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (errorMessage != null) {
                OutlinedButton(
                    onClick = {
                        errorMessage = null
                        val activity = context as? androidx.fragment.app.FragmentActivity
                        if (activity != null) {
                            startAuthentication(activity)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAuthenticating,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.biometric_retry))
                }
            }
        }
    }
    }
}
