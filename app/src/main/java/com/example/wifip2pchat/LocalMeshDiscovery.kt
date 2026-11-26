package com.example.wifip2pchat

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hostless LAN / Wi-Fi / Mobile-Hotspot peer discovery using Android NSD (mDNS).
 *
 * Publishes this device as `_lanvox._tcp.` on [Constants.CHAT_PORT] and discovers
 * other LanVox devices on the same network so peers can chat, share files, and call
 * directly without needing a Wi-Fi Direct Group Owner or central server.
 */
class LocalMeshDiscovery(
    context: Context,
    private val onPeerDiscovered: (peerId: String, peerName: String, hostIp: String, port: Int) -> Unit,
    private val onPeerLost: (peerId: String) -> Unit
) {
    companion object {
        private const val TAG = "LocalMeshDiscovery"
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var registeredServiceName: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)
    private val serviceNameToPeerId = ConcurrentHashMap<String, String>()
    private val started = AtomicBoolean(false)

    fun start(selfId: String, selfName: String) {
        val mgr = nsdManager ?: return
        if (!started.compareAndSet(false, true)) return

        acquireMulticastLock()
        registerService(mgr, selfId, selfName)
        discoverServices(mgr)
    }

    fun updateSelfName(selfId: String, newName: String) {
        if (!started.get()) return
        val mgr = nsdManager ?: return
        try {
            registrationListener?.let { mgr.unregisterService(it) }
        } catch (_: Exception) {}
        registrationListener = null
        registeredServiceName = null
        registerService(mgr, selfId, newName)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        val mgr = nsdManager
        resolveQueue.clear()
        isResolving.set(false)

        try {
            discoveryListener?.let { mgr?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.d(TAG, "stopServiceDiscovery ignored: ${e.message}")
        }
        discoveryListener = null

        try {
            registrationListener?.let { mgr?.unregisterService(it) }
        } catch (e: Exception) {
            Log.d(TAG, "unregisterService ignored: ${e.message}")
        }
        registrationListener = null
        registeredServiceName = null
        serviceNameToPeerId.clear()

        releaseMulticastLock()
    }

    private fun registerService(mgr: NsdManager, selfId: String, selfName: String) {
        val shortId = selfId.replace(":", "").takeLast(8).ifBlank { "node" }
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LANVOX-$shortId"
            serviceType = Constants.NSD_SERVICE_TYPE
            port = Constants.CHAT_PORT
            setAttribute("id", selfId)
            setAttribute("name", selfName.take(40))
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.d(TAG, "NSD service registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed ($errorCode)")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "NSD service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed ($errorCode)")
            }
        }
        registrationListener = listener
        try {
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService error: ${e.message}")
        }
    }

    private fun discoverServices(mgr: NsdManager) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != Constants.NSD_SERVICE_TYPE) return
                val ownName = registeredServiceName
                if (ownName != null && service.serviceName == ownName) return
                if (!service.serviceName.startsWith("LANVOX-", ignoreCase = true)) return

                resolveQueue.offer(service)
                drainResolveQueue()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val peerId = serviceNameToPeerId.remove(service.serviceName)
                if (peerId != null) {
                    Log.d(TAG, "NSD peer lost: ${service.serviceName} ($peerId)")
                    onPeerLost(peerId)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD startDiscovery failed ($errorCode)")
                try { mgr.stopServiceDiscovery(this) } catch (_: Exception) {}
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stopDiscovery failed ($errorCode)")
            }
        }
        discoveryListener = listener
        try {
            mgr.discoverServices(Constants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices error: ${e.message}")
        }
    }

    private fun drainResolveQueue() {
        val mgr = nsdManager ?: return
        if (!started.get()) return
        if (!isResolving.compareAndSet(false, true)) return

        val next = resolveQueue.poll()
        if (next == null) {
            isResolving.set(false)
            return
        }

        try {
            @Suppress("DEPRECATION")
            mgr.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "NSD resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    isResolving.set(false)
                    drainResolveQueue()
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    try {
                        val hostIp = resolved.host?.hostAddress
                        val attrs = resolved.attributes
                        val peerId = attrs["id"]?.let { String(it, Charsets.UTF_8) }
                            ?.takeIf { it.isNotBlank() }
                            ?: resolved.serviceName
                        val peerName = attrs["name"]?.let { String(it, Charsets.UTF_8) }
                            ?.takeIf { it.isNotBlank() }
                            ?: resolved.serviceName

                        if (peerId != PeerRegistry.selfId && !hostIp.isNullOrBlank()) {
                            serviceNameToPeerId[resolved.serviceName] = peerId
                            Log.d(TAG, "Resolved NSD peer $peerName ($peerId) at $hostIp:${resolved.port}")
                            onPeerDiscovered(peerId, peerName, hostIp, resolved.port)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling resolved NSD peer: ${e.message}")
                    } finally {
                        isResolving.set(false)
                        drainResolveQueue()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService exception: ${e.message}")
            isResolving.set(false)
            drainResolveQueue()
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wm.createMulticastLock("lanvox:nsd_mesh").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock unavailable: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }
}
