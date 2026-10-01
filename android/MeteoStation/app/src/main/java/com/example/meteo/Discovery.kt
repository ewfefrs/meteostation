package com.example.meteo

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Поиск станции в локальной сети через mDNS/DNS-SD (служба _http._tcp с именем «meteostation»). */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun find(timeoutMs: Long = 8000): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<String?> { cont ->
            val done = AtomicBoolean(false)
            lateinit var listener: NsdManager.DiscoveryListener

            fun finish(result: String?) {
                if (done.compareAndSet(false, true)) {
                    runCatching { nsd.stopServiceDiscovery(listener) }
                    if (cont.isActive) cont.resume(result)
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceName.contains("meteo", ignoreCase = true)) return
                    @Suppress("DEPRECATION")
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val address = info.host
                            // станция отвечает по IPv4; IPv6 link-local в URL не годится
                            if (address is Inet4Address) finish(address.hostAddress)
                        }
                    })
                }
            }
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }
    }
}
