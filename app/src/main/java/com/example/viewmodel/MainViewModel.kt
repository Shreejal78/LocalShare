package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppSettings
import com.example.data.SettingsRepository
import com.example.model.ServerInfo
import com.example.model.ServerLog
import com.example.model.ServerStatus
import com.example.model.SharedItem
import com.example.model.SharingMode
import com.example.network.NetworkStatus
import com.example.network.NetworkUtils
import com.example.qr.QrCodeGenerator
import com.example.server.FileRegistry
import com.example.server.ServerManager
import com.example.service.SharingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val settingsRepository = SettingsRepository(context)

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    val fileRegistry = FileRegistry(context)
    val sharedItems: StateFlow<List<SharedItem>> = fileRegistry.items

    private val serverManager = ServerManager(
        context = context,
        fileRegistry = fileRegistry,
        getSettings = { settings.value }
    )

    val serverInfo: StateFlow<ServerInfo> = serverManager.serverInfo
    val logs: StateFlow<List<ServerLog>> = serverManager.logs

    private val _networkStatus = MutableStateFlow(NetworkUtils.getNetworkStatus(context))
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _showWifiRequiredDialog = MutableStateFlow(false)
    val showWifiRequiredDialog: StateFlow<Boolean> = _showWifiRequiredDialog.asStateFlow()

    private var lastGeneratedQrUrl: String? = null

    init {
        SharingForegroundService.activeServerManager = serverManager

        serverManager.onStatusChanged = { info ->
            if (info.status == ServerStatus.RUNNING) {
                val url = info.activeUrl
                SharingForegroundService.startService(
                    context = context,
                    url = url,
                    serverManager = serverManager
                )
                generateQr(url)
            } else if (info.status == ServerStatus.STOPPED || info.status == ServerStatus.ERROR) {
                SharingForegroundService.stopService(context)
                _qrBitmap.value = null
                lastGeneratedQrUrl = null
            }
        }

        viewModelScope.launch {
            settings.collect { currentSettings ->
                if (currentSettings.startOnLaunch && serverInfo.value.status == ServerStatus.STOPPED) {
                    if (NetworkUtils.isWifiOrHotspotActive(context)) {
                        startServer()
                    }
                }
            }
        }
    }

    fun dismissWifiRequiredDialog() {
        _showWifiRequiredDialog.value = false
    }

    fun setSharingMode(mode: SharingMode) {
        // Global sharing removed - local-only architecture
    }

    fun revokeGlobalLink() {
        // Global sharing removed - local-only architecture
    }

    fun refreshNetwork() {
        val status = NetworkUtils.getNetworkStatus(context)
        _networkStatus.value = status
        serverManager.refreshNetworkIp()

        val currentInfo = serverInfo.value
        if (currentInfo.status == ServerStatus.RUNNING) {
            val url = currentInfo.activeUrl
            generateQr(url)
        }
    }

    fun startServer(): Boolean {
        refreshNetwork()
        val isNetworkActive = NetworkUtils.isWifiOrHotspotActive(context)
        if (!isNetworkActive) {
            _showWifiRequiredDialog.value = true
            return false
        }
        _showWifiRequiredDialog.value = false
        serverManager.startServer()
        return true
    }

    fun stopServer() {
        serverManager.stopServer()
    }

    fun toggleServer() {
        if (serverInfo.value.status == ServerStatus.RUNNING || serverInfo.value.status == ServerStatus.STARTING) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            fileRegistry.addFileUris(uris)
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            fileRegistry.addFolderUri(treeUri)
        }
    }

    fun removeItem(id: String) {
        fileRegistry.removeItem(id)
    }

    fun clearAllItems() {
        fileRegistry.clearAll()
    }

    fun clearLogs() {
        serverManager.clearLogs()
    }

    fun updatePort(port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updatePort(port)
            if (serverInfo.value.status == ServerStatus.RUNNING) {
                serverManager.restartServer()
            }
        }
    }

    fun setPassword(enabled: Boolean, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setPasswordProtection(enabled, pass)
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateThemeMode(theme)
        }
    }

    fun setMaxConnections(max: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateMaxConnections(max)
        }
    }

    fun setStartOnLaunch(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateStartOnLaunch(enabled)
        }
    }

    fun resetAllSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.resetSettings()
        }
    }

    private fun generateQr(url: String) {
        if (url == lastGeneratedQrUrl && _qrBitmap.value != null) return
        lastGeneratedQrUrl = url

        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = QrCodeGenerator.generateQrBitmap(
                content = url,
                size = 512,
                foregroundColor = android.graphics.Color.parseColor("#0F172A"),
                backgroundColor = android.graphics.Color.WHITE
            )
            _qrBitmap.value = bitmap
        }
    }

    override fun onCleared() {
        super.onCleared()
        serverManager.stopServer()
    }
}
