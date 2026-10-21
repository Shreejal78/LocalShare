package com.example.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ServerStatus
import com.example.ui.theme.RoseError
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val serverInfo by viewModel.serverInfo.collectAsState()
    val isServerRunning = serverInfo.status == ServerStatus.RUNNING || serverInfo.status == ServerStatus.STARTING

    var showPortDialog by remember { mutableStateOf(false) }
    var portInput by remember(settings.serverPort) { mutableStateOf(settings.serverPort.toString()) }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember(settings.serverPassword) { mutableStateOf(settings.serverPassword) }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section: Network & Server
            SettingsSectionHeader("SERVER CONFIGURATION")

            SettingsItemCard(
                icon = Icons.Filled.Router,
                title = "Server Port",
                subtitle = "Port ${settings.serverPort} (Default: 8080)",
                onClick = {
                    portInput = settings.serverPort.toString()
                    showPortDialog = true
                }
            )

            SettingsItemCard(
                icon = Icons.Filled.Speed,
                title = "Auto-Start Server",
                subtitle = "Automatically start sharing files when app is opened",
                trailing = {
                    Switch(
                        checked = settings.startOnLaunch,
                        onCheckedChange = { viewModel.setStartOnLaunch(it) },
                        modifier = Modifier.testTag("auto_start_switch")
                    )
                }
            )

            // Section: Security
            SettingsSectionHeader("SECURITY & ACCESS")

            SettingsItemCard(
                icon = Icons.Filled.Lock,
                title = "Password Protection",
                subtitle = if (settings.passwordProtected) "Enabled (Password required by clients)" else "Disabled (Anyone on Wi-Fi can view files)",
                trailing = {
                    Switch(
                        checked = settings.passwordProtected,
                        onCheckedChange = { checked ->
                            if (checked && settings.serverPassword.isBlank()) {
                                showPasswordDialog = true
                            } else {
                                viewModel.setPassword(checked, settings.serverPassword)
                            }
                        },
                        modifier = Modifier.testTag("password_switch")
                    )
                },
                onClick = {
                    passwordInput = settings.serverPassword
                    showPasswordDialog = true
                }
            )

            // Section: Appearance
            SettingsSectionHeader("APPEARANCE")

            SettingsItemCard(
                icon = Icons.Filled.Palette,
                title = "Theme",
                subtitle = when (settings.themeMode) {
                    "DARK" -> "Dark Mode (Default)"
                    "LIGHT" -> "Light Mode"
                    else -> "System Default"
                },
                onClick = { showThemeDialog = true }
            )

            // Section: System & About
            SettingsSectionHeader("ABOUT & MAINTENANCE")

            SettingsItemCard(
                icon = Icons.Filled.Info,
                title = "About LocalShare",
                subtitle = "Version 1.0 • Privacy & Architecture Info",
                onClick = { showAboutDialog = true }
            )

            SettingsItemCard(
                icon = Icons.Filled.Refresh,
                title = "Reset All Settings",
                subtitle = "Restore default port, theme, and security settings",
                onClick = { showResetDialog = true }
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Port Dialog
    if (showPortDialog) {
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("Configure Server Port") },
            text = {
                Column {
                    Text("Select a port between 1024 and 65535 (default 8080).", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { char -> char.isDigit() } },
                        singleLine = true,
                        label = { Text("Port Number") },
                        modifier = Modifier.fillMaxWidth().testTag("port_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = portInput.toIntOrNull()
                        if (p != null && p in 1024..65535) {
                            viewModel.updatePort(p)
                            showPortDialog = false
                            Toast.makeText(context, "Port set to $p", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Please enter a valid port (1024-65535)", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Password Dialog
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Set Server Password") },
            text = {
                Column {
                    Text("Remote devices connecting on the local network must enter this password to access shared files.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        singleLine = true,
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth().testTag("password_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pass = passwordInput.trim()
                        if (pass.isNotEmpty()) {
                            viewModel.setPassword(true, pass)
                            showPasswordDialog = false
                            Toast.makeText(context, "Password protection enabled", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.setPassword(false, "")
                            showPasswordDialog = false
                            Toast.makeText(context, "Password protection disabled", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Theme Selection Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose Theme") },
            text = {
                Column {
                    listOf("DARK" to "Dark Mode", "LIGHT" to "Light Mode", "SYSTEM" to "System Default").forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setTheme(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = settings.themeMode == mode,
                                onClick = {
                                    viewModel.setTheme(mode)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About LocalShare") },
            text = {
                Column {
                    Text("LocalShare is a privacy-first, local Wi-Fi peer-to-peer file sharing application.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Zero Cloud Servers: Files stream directly from your Android phone's storage through an embedded HTTP server.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Safe Storage Access: Only files and folders explicitly chosen by you through the Android Storage Access Framework are ever accessible.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Cross-Platform: Any device with a modern web browser (iOS, Windows, Mac, Linux, Android) can browse, stream, and download without installing software.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { showAboutDialog = false }) { Text("Got It") }
            }
        )
    }

    // Reset Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset All Settings?") },
            text = { Text("This will reset the port to 8080, clear passwords, and revert settings to factory defaults.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetAllSettings()
                        showResetDialog = false
                        Toast.makeText(context, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError)
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
fun SettingsItemCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailing()
            }
        }
    }
}
