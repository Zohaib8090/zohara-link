package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.model.DiscoveredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

class NsdDiscoveryManager(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val serviceType = "_zohara-link._tcp"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    // Some phones only deliver multicast (mDNS) packets while an app holds this lock.
    private val multicastLock: WifiManager.MulticastLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("zohara-link-mdns")
            ?.apply { setReferenceCounted(false) }

    fun startDiscovery() {
        if (_isScanning.value) return
        _discoveredDevices.value = emptyList()
        try { multicastLock?.acquire() } catch (e: Exception) { Log.w(TAG, "multicast lock: ${e.message}") }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started: $regType")
                _isScanning.value = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service discovery success: $service")
                if (service.serviceType.contains("zohara-link") || service.serviceType == serviceType) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value.filterNot { it.serviceName == service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
                _isScanning.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code: $errorCode")
                _isScanning.value = false
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code: $errorCode")
                _isScanning.value = false
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            _isScanning.value = false
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed $errorCode for ${serviceInfo.serviceName}")
                // 3 = another resolve is still running (Android resolves one service at a time): try again shortly.
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    main.postDelayed({ if (_isScanning.value) resolveService(serviceInfo) }, 800)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // Only an IPv4 address can be used: the computer may also announce an IPv6 link-local one, which cannot be
                // connected to without knowing the network interface, so taking "the first address" found nothing.
                val addresses = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses else listOfNotNull(serviceInfo.host)
                val host = addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: return
                val port = serviceInfo.port
                val name = serviceInfo.serviceName
                Log.d(TAG, "Resolved service: $name at $host:$port")

                val device = DiscoveredDevice(
                    serviceName = name,
                    hostAddress = host,
                    port = port
                )
                val current = _discoveredDevices.value.toMutableList()
                val idx = current.indexOfFirst { it.hostAddress == host && it.port == port }
                if (idx >= 0) {
                    current[idx] = device
                } else {
                    current.add(device)
                }
                _discoveredDevices.value = current
            }
        })
    }

    /**
     * Fallback when mDNS does not work on this Wi-Fi (some routers and phones block it): try the Zohara Link port on every
     * address of this phone's own network. It only finds computers whose daemon is running and reachable, the same ones
     * typing the address would reach.
     */
    fun scanLocalNetwork(port: Int = 42424) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _isScanning.value = true
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val link = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
                    ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return@launch
                val own = (link.address as Inet4Address).address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                val prefix = link.prefixLength.coerceIn(22, 30) // never scan more than about 1000 addresses
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val network = own and mask
                val count = 1L shl (32 - prefix)
                val gate = Semaphore(64)
                val hits = (1 until count - 1).map { i ->
                    val ip = network + i
                    async {
                        if (ip == own) return@async null
                        val text = "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
                        gate.withPermit {
                            try {
                                Socket().use { s -> s.connect(InetSocketAddress(text, port), 500) }
                                text
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()

                val current = _discoveredDevices.value.toMutableList()
                for (ip in hits) {
                    if (current.none { it.hostAddress == ip && it.port == port }) {
                        current.add(DiscoveredDevice(serviceName = "Zohara computer ($ip)", hostAddress = ip, port = port))
                    }
                }
                _discoveredDevices.value = current
            } catch (e: Exception) {
                Log.w(TAG, "Network scan failed: ${e.message}")
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (e: Exception) { Log.w(TAG, "multicast lock: ${e.message}") }
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
        _isScanning.value = false
    }

    companion object {
        private const val TAG = "NsdDiscoveryManager"
    }
}
