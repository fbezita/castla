package com.castla.mirror.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.castla.mirror.R
import com.castla.mirror.setup.SetupProgressPolicy
import com.castla.mirror.setup.SetupUiState

@Composable
fun ShizukuSetupScreen(
    state: SetupUiState,
    onInstall: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenGuide: () -> Unit,
    onGrantPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF10141A))
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.title_shizuku_required),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFB300),
        )
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF171C24), RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            val activeStep = SetupProgressPolicy.activeStep(state)
            SetupStepRow(0, R.string.setup_step_install, activeStep)
            SetupStepRow(1, R.string.setup_step_start, activeStep)
            SetupStepRow(2, R.string.setup_step_permission, activeStep)
            SetupStepRow(3, R.string.setup_step_connect, activeStep)

            Spacer(Modifier.height(20.dp))
            when (state) {
                SetupUiState.NotInstalled -> {
                    SetupDescription(R.string.desc_shizuku_install_required)
                    PrimarySetupButton(R.string.btn_how_to_install_shizuku, onInstall)
                }

                SetupUiState.NotRunning -> {
                    SetupDescription(R.string.desc_shizuku_setup_steps)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onOpenShizuku,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                        ) {
                            Text(stringResource(R.string.btn_open_shizuku), color = Color.Black)
                        }
                        OutlinedButton(
                            onClick = onOpenGuide,
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, Color(0xFFFFB300)),
                        ) {
                            Text(stringResource(R.string.btn_setup_guide), color = Color(0xFFFFB300))
                        }
                    }
                }

                SetupUiState.PermissionRequired -> {
                    SetupDescription(R.string.desc_shizuku_permission_required)
                    PrimarySetupButton(R.string.btn_grant_shizuku_permission, onGrantPermission)
                }

                SetupUiState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFFFFB300),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.desc_shizuku_connecting),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is SetupUiState.Failed -> {
                    SetupDescription(R.string.desc_shizuku_connection_failed)
                    Text(
                        text = stringResource(R.string.desc_shizuku_connection_recovery),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimarySetupButton(R.string.btn_retry, onRetry)
                }

                SetupUiState.Ready -> Unit
            }
        }
    }
}

@Composable
private fun SetupStepRow(index: Int, labelResId: Int, activeStep: Int) {
    val completed = index < activeStep
    val active = index == activeStep
    val accent = when {
        completed -> Color(0xFF69F0AE)
        active -> Color(0xFFFFB300)
        else -> Color.White.copy(alpha = 0.28f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(accent.copy(alpha = if (completed || active) 0.2f else 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (completed) "✓" else "${index + 1}",
                color = accent,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(labelResId),
            color = if (active || completed) Color.White else Color.White.copy(alpha = 0.45f),
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SetupDescription(resId: Int) {
    Text(
        text = stringResource(resId),
        color = Color.White.copy(alpha = 0.82f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun PrimarySetupButton(labelResId: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
    ) {
        Text(stringResource(labelResId), color = Color.Black, fontWeight = FontWeight.Bold)
    }
}
