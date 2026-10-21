package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

enum class NetworkType {
    WIFI,
    ETHERNET,
    HOTSPOT,
    CELLULAR,
    LOOPBACK_ONLY,
    DISCONNECTED
}

data class NetworkStatus(
    val type: NetworkType,
    val ipAddress: String?,
    val isLanReachable: Boolean,
    val description: String
)

object NetworkUtils {

    /**
     * Checks if either Wi-Fi or a Wi-Fi Hotspot is currently enabled and active on the device.
     */
    fun isWifiOrHotspotActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork: Network? = cm?.activeNetwork
        val caps: NetworkCapabilities? = cm?.getNetworkCapabilities(activeNetwork)

        // 1. Active Wi-Fi or Ethernet connection via ConnectivityManager
        if (caps != null) {
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (hasWifi || hasEthernet) {
                return true
            }
        }

        // 2. Wi-Fi Manager enabled check (Wi-Fi radio is toggled ON)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            if (wm?.isWifiEnabled == true) {
                val ip = getLocalIpAddress()
                if (ip != null && !ip.startsWith("127.")) {
                    return true
                }
            }
        } catch (_: Exception) {}

        // 3. Hotspot check via reflection (isWifiApEnabled / getWifiApState)
        if (isHotspotActive(wm)) {
            return true
        }

        // 4. Hotspot network interface inspection (ap0, softap, swlan, rndis)
        if (hasActiveHotspotInterface()) {
            return true
        }

        // 5. Hotspot IP range detection (e.g. 192.168.43.x)
        val localIp = getLocalIpAddress()
        if (localIp != null && isHotspotSubnet(localIp)) {
            return true
        }

        return false
    }

    private fun isHotspotActive(wm: WifiManager?): Boolean {
        if (wm == null) return false
        try {
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            if ((method.invoke(wm) as? Boolean) == true) {
                return true
            }
        } catch (_: Exception) {}

        try {
            val stateMethod = wm.javaClass.getDeclaredMethod("getWifiApState")
            stateMethod.isAccessible = true
            val state = stateMethod.invoke(wm) as? Int
            // 12 = WIFI_AP_STATE_ENABLING, 13 = WIFI_AP_STATE_ENABLED
            if (state == 12 || state == 13) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private fun hasActiveHotspotInterface(): Boolean {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                val isHotspotName = name.startsWith("ap") ||
                        name.startsWith("softap") ||
                        name.startsWith("swlan") ||
                        name.startsWith("wlan1") ||
                        name.startsWith("rndis")
                if (isHotspotName) {
                    val addresses = Collections.list(iface.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            if (!host.startsWith("127.") && !host.startsWith("169.254.")) {
                                return true
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun isHotspotSubnet(ip: String?): Boolean {
        if (ip == null) return false
        return ip.startsWith("192.168.43.") ||
                ip.startsWith("192.168.44.") ||
                ip.startsWith("192.168.49.") ||
                ip.startsWith("192.168.50.")
    }

    fun getNetworkStatus(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork: Network? = cm?.activeNetwork
        val caps: NetworkCapabilities? = cm?.getNetworkCapabilities(activeNetwork)

        val localIp = getLocalIpAddress()
        val isReady = isWifiOrHotspotActive(context)

        if (!isReady) {
            return NetworkStatus(
                type = NetworkType.DISCONNECTED,
                ipAddress = null,
                isLanReachable = false,
                description = "Wi-Fi or Hotspot must be enabled to share files locally."
            )
        }

        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val hasCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        return when {
            hasWifi && localIp != null -> {
                NetworkStatus(
                    type = NetworkType.WIFI,
                    ipAddress = localIp,
                    isLanReachable = true,
                    description = "Connected to Wi-Fi. Other devices on this network can connect."
                )
            }
            hasEthernet && localIp != null -> {
                NetworkStatus(
                    type = NetworkType.ETHERNET,
                    ipAddress = localIp,
                    isLanReachable = true,
                    description = "Connected via Ethernet. LAN devices can connect."
                )
            }
            isHotspotSubnet(localIp) || hasActiveHotspotInterface() -> {
                NetworkStatus(
                    type = NetworkType.HOTSPOT,
                    ipAddress = localIp,
                    isLanReachable = true,
                    description = "Mobile Hotspot active. Connected client devices can connect."
                )
            }
            hasCellular -> {
                NetworkStatus(
                    type = NetworkType.CELLULAR,
                    ipAddress = localIp,
                    isLanReachable = false,
                    description = "Connected to Mobile Data. Turn on Wi-Fi or Hotspot to share files locally."
                )
            }
            localIp != null -> {
                NetworkStatus(
                    type = NetworkType.WIFI,
                    ipAddress = localIp,
                    isLanReachable = true,
                    description = "Local IP detected ($localIp)."
                )
            }
            else -> {
                NetworkStatus(
                    type = NetworkType.LOOPBACK_ONLY,
                    ipAddress = "127.0.0.1",
                    isLanReachable = false,
                    description = "No local Wi-Fi IP detected. Only reachable on this device."
                )
            }
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First priority: wlan0, ap0, eth0, or en0
            val preferredInterfaces = interfaces.filter { iface ->
                !iface.isLoopback && iface.isUp && (
                    iface.name.startsWith("wlan", ignoreCase = true) ||
                    iface.name.startsWith("ap", ignoreCase = true) ||
                    iface.name.startsWith("eth", ignoreCase = true) ||
                    iface.name.startsWith("rndis", ignoreCase = true) ||
                    iface.name.startsWith("en", ignoreCase = true)
                )
            }

            for (iface in preferredInterfaces) {
                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }

            // Fallback: any non-loopback IPv4 address
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.") && !host.startsWith("169.254.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore socket/network enumeration exceptions
        }
        return null
    }
}
