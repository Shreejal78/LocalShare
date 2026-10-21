package com.example.model

enum class SharingMode {
    LOCAL
}

enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

enum class TunnelStatus {
    DISCONNECTED
}

data class ServerInfo(
    val status: ServerStatus = ServerStatus.STOPPED,
    val sharingMode: SharingMode = SharingMode.LOCAL,
    val port: Int = 8080,
    val ipAddress: String? = null,
    val globalUrl: String? = null,
    val globalAccessToken: String? = null,
    val tunnelStatus: TunnelStatus = TunnelStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val activeConnections: Int = 0,
    val totalRequests: Long = 0,
    val totalBytesServed: Long = 0L,
    val startedAtMillis: Long? = null
) {
    val localUrl: String?
        get() = if (ipAddress != null && status == ServerStatus.RUNNING) {
            "http://$ipAddress:$port"
        } else null

    val loopbackUrl: String
        get() = "http://127.0.0.1:$port"

    val activeUrl: String
        get() = localUrl ?: loopbackUrl

    val url: String?
        get() = if (status == ServerStatus.RUNNING) activeUrl else null

    val formattedTotalBytesServed: String
        get() {
            if (totalBytesServed <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(totalBytesServed.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format(java.util.Locale.US, "%.1f %s", totalBytesServed / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }

    fun getFormattedLiveUptime(currentMillis: Long = System.currentTimeMillis()): String {
        if (startedAtMillis == null || status != ServerStatus.RUNNING) return "00:00:00"
        val diffSec = maxOf(0L, (currentMillis - startedAtMillis) / 1000)
        val hours = diffSec / 3600
        val mins = (diffSec % 3600) / 60
        val secs = diffSec % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, mins, secs)
    }

    val uptimeFormatted: String
        get() = getFormattedLiveUptime()
}
