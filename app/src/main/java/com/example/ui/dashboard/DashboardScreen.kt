package com.example.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ServerInfo
import com.example.model.ServerStatus
import com.example.network.NetworkType
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val serverInfo by viewModel.serverInfo.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val sharedItems by viewModel.sharedItems.collectAsState()
    val qrBitmap by viewModel.qrBitmap.collectAsState()
    val showWifiRequiredDialog by viewModel.showWifiRequiredDialog.collectAsState()

    LaunchedEffect(showWifiRequiredDialog) {
        if (showWifiRequiredDialog) {
            Toast.makeText(context, "Wi-Fi or Hotspot must be enabled to share files locally.", Toast.LENGTH_LONG).show()
        }
    }

    var showQrDialog by remember { mutableStateOf(false) }

    // Live Uptime Ticker
    var currentTimeMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(serverInfo.status, serverInfo.startedAtMillis) {
        if (serverInfo.status == ServerStatus.RUNNING && serverInfo.startedAtMillis != null) {
            while (isActive) {
                currentTimeMillis = System.currentTimeMillis()
                delay(1000L)
            }
        } else {
            currentTimeMillis = System.currentTimeMillis()
        }
    }
    val liveUptime = remember(currentTimeMillis, serverInfo.status, serverInfo.startedAtMillis) {
        serverInfo.getFormattedLiveUptime(currentTimeMillis)
    }

    // File pickers
    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            for (uri in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: Exception) {}
            }
            viewModel.addFiles(uris)
        }
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (_: Exception) {}
            viewModel.addFolder(treeUri)
        }
    }

    val activeUrl = serverInfo.activeUrl

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Wifi,
                                contentDescription = "Logo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "LocalShare",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    // Status Pill
                    val (statusColor, statusText) = when (serverInfo.status) {
                        ServerStatus.RUNNING -> Pair(EmeraldSuccess, "RUNNING")
                        ServerStatus.STARTING -> Pair(CyanAccent, "STARTING")
                        ServerStatus.ERROR -> Pair(RoseError, "ERROR")
                        else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, "OFFLINE")
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(2.dp)) }

            // 1. Server Control Hero Card
            item {
                ServerHeroCard(
                    serverInfo = serverInfo,
                    onToggleServer = { viewModel.toggleServer() },
                    onCopyUrl = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("LocalShare URL", activeUrl))
                        Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onShareUrl = {
                        val directUrl = serverInfo.localUrl ?: activeUrl
                        val shareText = "Connect to my shared files on LocalShare: $directUrl"
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, shareText)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share LocalShare URL"))
                    },
                    onOpenBrowser = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(activeUrl))
                        try {
                            context.startActivity(browserIntent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "No web browser found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShowQr = { showQrDialog = true }
                )
            }

            // 2. Network Diagnostic Card
            item {
                LocalNetworkDiagnosticCard(
                    networkStatus = networkStatus,
                    serverInfo = serverInfo,
                    onRefreshNetwork = { viewModel.refreshNetwork() },
                    onNavigateToSettings = onNavigateToSettings
                )
            }

            // 3. Stats Section with Live Uptime
            item {
                val totalBytes = sharedItems.filter { !it.isDirectory }.sumOf { it.size }
                val formattedTotalSize = remember(totalBytes) {
                    if (totalBytes <= 0) "0 B"
                    else {
                        val units = arrayOf("B", "KB", "MB", "GB", "TB")
                        val digitGroups = (Math.log10(totalBytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
                        String.format(java.util.Locale.US, "%.1f %s", totalBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatMetricCard(
                        label = "Files",
                        value = "${sharedItems.count { !it.isDirectory }}",
                        sub = formattedTotalSize,
                        modifier = Modifier.weight(1f)
                    )
                    StatMetricCard(
                        label = "Folders",
                        value = "${sharedItems.count { it.isDirectory }}",
                        sub = "Directories",
                        modifier = Modifier.weight(1f)
                    )
                    StatMetricCard(
                        label = "Clients",
                        value = "${serverInfo.activeConnections}",
                        sub = if (serverInfo.status == ServerStatus.RUNNING) "Uptime: $liveUptime" else "Offline",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4. Quick Actions (Add Files & Add Folder)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { pickFilesLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("add_files_button")
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Files", fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = { pickFolderLauncher.launch(null) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("add_folder_button")
                    ) {
                        Icon(imageVector = Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Folder", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 5. Shared Items Preview header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Shared Items (${sharedItems.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (sharedItems.isNotEmpty()) {
                        TextButton(onClick = onNavigateToFiles) {
                            Text("Manage All")
                        }
                    }
                }
            }

            // Empty items prompt or list preview
            if (sharedItems.isEmpty()) {
                item {
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No items shared yet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Select files or folders to share them across devices on your local network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(sharedItems.take(4).size) { idx ->
                    val item = sharedItems[idx]
                    QuickItemRow(
                        item = item,
                        onRemove = { viewModel.removeItem(item.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }

    if (showQrDialog) {
        QrCodeDialog(
            url = activeUrl,
            qrBitmap = qrBitmap,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showWifiRequiredDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissWifiRequiredDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Filled.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Wi-Fi or Hotspot Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Wi-Fi or Hotspot must be enabled to share files locally.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Turn on Wi-Fi or activate your Mobile Hotspot so other devices on the same network can access your shared files.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissWifiRequiredDialog()
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Please open Android settings to enable Wi-Fi or Hotspot", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.testTag("open_wifi_settings_button")
                ) {
                    Text("Wi-Fi Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissWifiRequiredDialog() },
                    modifier = Modifier.testTag("dismiss_wifi_dialog_button")
                ) {
                    Text("Cancel")
                }
            },
            modifier = Modifier.testTag("wifi_required_dialog")
        )
    }
}

@Composable
fun ServerHeroCard(
    serverInfo: ServerInfo,
    onToggleServer: () -> Unit,
    onCopyUrl: () -> Unit,
    onShareUrl: () -> Unit,
    onOpenBrowser: () -> Unit,
    onShowQr: () -> Unit
) {
    val isRunning = serverInfo.status == ServerStatus.RUNNING
    val isStarting = serverInfo.status == ServerStatus.STARTING
    val activeUrl = serverInfo.activeUrl
    val context = LocalContext.current

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRunning) "Local Sharing Online" else if (isStarting) "Starting Server..." else "Sharing Offline",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isRunning) "Accessible via browser on local Wi-Fi or hotspot" else "Tap start to begin sharing files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onToggleServer,
                    enabled = !isStarting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) RoseError else MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("toggle_server_button")
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRunning) "Stop" else "Start")
                    }
                }
            }

            // Error display if any
            if (serverInfo.errorMessage != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = serverInfo.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // URL Container
            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "LOCAL WI-FI URL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (isRunning) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = EmeraldSuccess.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "LAN ACTIVE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldSuccess,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = if (isRunning) activeUrl else "http://--.--.--.--:${serverInfo.port}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Responsive 4-Action Toolbar
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val isNarrow = maxWidth < 280.dp

                        if (isNarrow) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    UrlActionButton(
                                        icon = Icons.Filled.ContentCopy,
                                        label = "Copy URL",
                                        onClick = onCopyUrl,
                                        enabled = isRunning,
                                        isPrimaryAccent = true,
                                        testTag = "copy_url_button",
                                        modifier = Modifier.weight(1f)
                                    )
                                    UrlActionButton(
                                        icon = Icons.Filled.QrCode,
                                        label = "QR Code",
                                        onClick = onShowQr,
                                        enabled = isRunning,
                                        testTag = "qr_code_button",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    UrlActionButton(
                                        icon = Icons.Filled.Share,
                                        label = "Share",
                                        onClick = onShareUrl,
                                        enabled = isRunning,
                                        testTag = "share_url_button",
                                        modifier = Modifier.weight(1f)
                                    )
                                    UrlActionButton(
                                        icon = Icons.Filled.OpenInBrowser,
                                        label = "Open",
                                        onClick = onOpenBrowser,
                                        enabled = isRunning,
                                        testTag = "open_browser_button",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                UrlActionButton(
                                    icon = Icons.Filled.ContentCopy,
                                    label = "Copy",
                                    onClick = onCopyUrl,
                                    enabled = isRunning,
                                    isPrimaryAccent = true,
                                    testTag = "copy_url_button",
                                    modifier = Modifier.weight(1f)
                                )
                                UrlActionButton(
                                    icon = Icons.Filled.QrCode,
                                    label = "QR Code",
                                    onClick = onShowQr,
                                    enabled = isRunning,
                                    testTag = "qr_code_button",
                                    modifier = Modifier.weight(1f)
                                )
                                UrlActionButton(
                                    icon = Icons.Filled.Share,
                                    label = "Share",
                                    onClick = onShareUrl,
                                    enabled = isRunning,
                                    testTag = "share_url_button",
                                    modifier = Modifier.weight(1f)
                                )
                                UrlActionButton(
                                    icon = Icons.Filled.OpenInBrowser,
                                    label = "Open",
                                    onClick = onOpenBrowser,
                                    enabled = isRunning,
                                    testTag = "open_browser_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (!isRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Start the server above to copy, share, or open the link.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LocalNetworkDiagnosticCard(
    networkStatus: com.example.network.NetworkStatus,
    serverInfo: ServerInfo,
    onRefreshNetwork: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (networkStatus.type == NetworkType.WIFI) Icons.Filled.Wifi
                        else if (networkStatus.type == NetworkType.HOTSPOT) Icons.Filled.WifiTethering
                        else Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Local Network Status",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onRefreshNetwork,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh IP",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "• IP Address: ${serverInfo.ipAddress ?: "Not detected"} | Port: ${serverInfo.port}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            val tipText = when (networkStatus.type) {
                NetworkType.WIFI -> "💡 Connected to Wi-Fi: Other devices on this network can open your files. If devices cannot connect, enable your phone's Wi-Fi Hotspot to bypass router client isolation."
                NetworkType.HOTSPOT -> "💡 Wi-Fi Hotspot active: Other devices can connect directly to your hotspot network for high-speed direct sharing without an external router."
                NetworkType.CELLULAR -> "⚠️ Mobile Cellular Data: Connect this device and other devices to the same local Wi-Fi, or turn on your phone's Wi-Fi hotspot."
                else -> "⚠️ No active local network detected. Please connect to Wi-Fi or turn on phone hotspot."
            }

            Text(
                text = tipText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onNavigateToSettings,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(
                        text = "Configure Server Port & Options →",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun UrlActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isPrimaryAccent: Boolean = false,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        isPrimaryAccent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }

    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        isPrimaryAccent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
    }

    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        isPrimaryAccent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .heightIn(min = 52.dp)
            .testTag(testTag)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = if (isPrimaryAccent) FontWeight.Bold else FontWeight.Medium
                ),
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun StatMetricCard(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun QuickItemRow(
    item: com.example.model.SharedItem,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (item.isDirectory) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                    )
            ) {
                Icon(
                    imageVector = if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.formattedSize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "Remove file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
