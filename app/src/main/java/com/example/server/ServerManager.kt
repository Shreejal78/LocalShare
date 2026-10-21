package com.example.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.example.data.AppSettings
import com.example.model.ServerInfo
import com.example.model.ServerLog
import com.example.model.ServerStatus
import com.example.model.SharingMode
import com.example.model.TunnelStatus
import com.example.network.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.BindException

class ServerManager(
    private val context: Context,
    val fileRegistry: FileRegistry,
    private val getSettings: () -> AppSettings
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _serverInfo = MutableStateFlow(ServerInfo())
    val serverInfo: StateFlow<ServerInfo> = _serverInfo.asStateFlow()

    private val _logs = MutableStateFlow<List<ServerLog>>(emptyList())
    val logs: StateFlow<List<ServerLog>> = _logs.asStateFlow()

    private var httpServer: LocalHttpServer? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    var onStatusChanged: ((ServerInfo) -> Unit)? = null

    fun startServer() {
        val current = _serverInfo.value
        if (current.status == ServerStatus.RUNNING || current.status == ServerStatus.STARTING) return

        // Connectivity Validation: Check if either Wi-Fi or Hotspot is active
        if (!NetworkUtils.isWifiOrHotspotActive(context)) {
            val err = "Wi-Fi or Hotspot must be enabled to share files locally."
            val errorInfo = _serverInfo.value.copy(
                status = ServerStatus.STOPPED,
                errorMessage = err
            )
            _serverInfo.value = errorInfo
            onStatusChanged?.invoke(errorInfo)
            addLog(
                ServerLog(
                    clientIp = "local",
                    method = "WARN",
                    path = "/",
                    statusCode = 400,
                    message = err
                )
            )
            return
        }

        val settings = getSettings()
        val port = settings.serverPort

        _serverInfo.value = current.copy(
            status = ServerStatus.STARTING,
            sharingMode = SharingMode.LOCAL,
            errorMessage = null
        )

        scope.launch {
            try {
                acquireLocks()

                val server = LocalHttpServer(
                    context = context,
                    port = port,
                    fileRegistry = fileRegistry,
                    getSettings = getSettings,
                    getServerInfo = { _serverInfo.value },
                    onLog = { log -> addLog(log) },
                    onConnectionChange = { activeCount ->
                        _serverInfo.value = _serverInfo.value.copy(activeConnections = activeCount)
                    }
                )

                server.start(30000, false)
                httpServer = server
                com.example.service.SharingForegroundService.activeHttpServer = server
                com.example.service.SharingForegroundService.activeServerManager = this@ServerManager

                val ip = NetworkUtils.getLocalIpAddress()
                val updatedInfo = _serverInfo.value.copy(
                    status = ServerStatus.RUNNING,
                    sharingMode = SharingMode.LOCAL,
                    port = port,
                    ipAddress = ip,
                    startedAtMillis = System.currentTimeMillis(),
                    errorMessage = null
                )
                _serverInfo.value = updatedInfo
                onStatusChanged?.invoke(updatedInfo)

                addLog(
                    ServerLog(
                        clientIp = "local",
                        method = "SYSTEM",
                        path = "/",
                        statusCode = 200,
                        message = "LocalShare HTTP server started on port $port"
                    )
                )
            } catch (e: BindException) {
                stopLocks()
                val err = "Port $port is already in use by another service. Please change the port in Settings."
                val errorInfo = _serverInfo.value.copy(status = ServerStatus.ERROR, port = port, errorMessage = err)
                _serverInfo.value = errorInfo
                onStatusChanged?.invoke(errorInfo)
                addLog(ServerLog(clientIp = "local", method = "ERROR", path = "/", statusCode = 500, message = err))
            } catch (e: IOException) {
                stopLocks()
                val err = "Failed to start server on port $port: ${e.message}"
                val errorInfo = _serverInfo.value.copy(status = ServerStatus.ERROR, port = port, errorMessage = err)
                _serverInfo.value = errorInfo
                onStatusChanged?.invoke(errorInfo)
                addLog(ServerLog(clientIp = "local", method = "ERROR", path = "/", statusCode = 500, message = err))
            } catch (e: Exception) {
                stopLocks()
                val err = "Unexpected error starting server: ${e.message}"
                val errorInfo = _serverInfo.value.copy(status = ServerStatus.ERROR, port = port, errorMessage = err)
                _serverInfo.value = errorInfo
                onStatusChanged?.invoke(errorInfo)
                addLog(ServerLog(clientIp = "local", method = "ERROR", path = "/", statusCode = 500, message = err))
            }
        }
    }

    val currentHttpServer: LocalHttpServer?
        get() = httpServer

    fun stopAllTunnels() {
        // Global sharing removed - local-only architecture
    }

    fun stopServer() {
        val current = _serverInfo.value
        if (current.status == ServerStatus.STOPPED && httpServer == null) return

        stopAllTunnels()

        try {
            httpServer?.stop()
            httpServer = null
        } catch (_: Exception) {}

        stopLocks()

        val stoppedInfo = _serverInfo.value.copy(
            status = ServerStatus.STOPPED,
            tunnelStatus = TunnelStatus.DISCONNECTED,
            globalUrl = null,
            activeConnections = 0,
            startedAtMillis = null
        )
        _serverInfo.value = stoppedInfo
        onStatusChanged?.invoke(stoppedInfo)

        addLog(
            ServerLog(
                clientIp = "local",
                method = "SYSTEM",
                path = "/",
                statusCode = 200,
                message = "LocalShare HTTP server stopped"
            )
        )
    }

    fun toggleServer() {
        if (_serverInfo.value.status == ServerStatus.RUNNING || _serverInfo.value.status == ServerStatus.STARTING) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun restartServer() {
        stopServer()
        startServer()
    }

    fun refreshNetworkIp() {
        if (_serverInfo.value.status == ServerStatus.RUNNING) {
            val ip = NetworkUtils.getLocalIpAddress()
            _serverInfo.value = _serverInfo.value.copy(ipAddress = ip)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun addLog(log: ServerLog) {
        val current = _logs.value.toMutableList()
        current.add(0, log) // newest first
        if (current.size > 200) {
            _logs.value = current.take(200)
        } else {
            _logs.value = current
        }

        if (log.method != "SYSTEM" && log.method != "ERROR") {
            _serverInfo.value = _serverInfo.value.copy(
                totalRequests = _serverInfo.value.totalRequests + 1
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "LocalShare:ServerWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LocalShare:ServerWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L)
                }
            }
        } catch (_: Exception) {}
    }

    private fun stopLocks() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (_: Exception) {}

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }
}
