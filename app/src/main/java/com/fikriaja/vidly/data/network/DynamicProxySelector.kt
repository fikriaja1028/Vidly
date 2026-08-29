
package com.fikriaja.vidly.data.network

import com.fikriaja.vidly.data.local.PreferencesManager
import com.fikriaja.vidly.utils.VidlyLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.*

/**
 * A ProxySelector that dynamically checks preferences without blocking the calling thread.
 */
class DynamicProxySelector(
    preferencesManager: PreferencesManager
) : ProxySelector() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var currentProxy: Proxy = Proxy.NO_PROXY

    init {
        scope.launch {
            combine(
                preferencesManager.isProxyEnabled,
                preferencesManager.proxyHost,
                preferencesManager.proxyPort
            ) { enabled, host, port ->
                if (enabled && host.isNotBlank()) {
                    try {
                        val address = InetSocketAddress.createUnresolved(host, port)
                        Proxy(Proxy.Type.HTTP, address)
                    } catch (e: Exception) {
                        Proxy.NO_PROXY
                    }
                } else {
                    Proxy.NO_PROXY
                }
            }.collect {
                currentProxy = it
            }
        }
    }

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
        // FIX(BUG #14): previously only localhost/127.0.0.1 bypassed the proxy â€”
        // LAN hosts (router admin pages, NAS, local media servers) were forced
        // through the proxy and unreachable. Bypass the proxy for loopback and
        // all RFC1918 private ranges.
        if (isLocalOrPrivateHost(host)) {
            return listOf(Proxy.NO_PROXY)
        }
        
        return listOf(currentProxy)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // FIX(BUG #14): connectFailed was an empty no-op, which violates the
        // ProxySelector contract. At minimum log the failure so dead proxies are
        // diagnosable from logcat.
        VidlyLog.w("DynamicProxySelector", "Proxy connect failed for $uri via $sa: ${ioe?.message}")
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1" || normalized.endsWith(".local")) {
            return true
        }
        // Only dotted-quad hostnames can be private IPv4 addresses; a resolved
        // InetAddress is not used here because the host must stay unresolved.
        val parts = normalized.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 || (it.endsWith(".0") && it.dropLast(2).toIntOrNull() in 0..255) }) {
            val first = parts[0].toIntOrNull() ?: return false
            val second = parts[1].toIntOrNull() ?: return false
            return when {
                first == 10 -> true                                   // 10.0.0.0/8
                first == 172 && second in 16..31 -> true              // 172.16.0.0/12
                first == 192 && second == 168 -> true                 // 192.168.0.0/16
                first == 169 && second == 254 -> true                 // link-local
                else -> false
            }
        }
        return false
    }
}
