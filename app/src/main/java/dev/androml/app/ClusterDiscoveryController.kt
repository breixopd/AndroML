package dev.androml.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredClusterEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
)

/**
 * mDNS discovery for the local network. Results are hints only: the cluster never trusts an
 * endpoint until the user imports an invite and pins its certificate.
 */
class ClusterDiscoveryController(context: Context) : Closeable {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val _services = MutableStateFlow<List<DiscoveredClusterEndpoint>>(emptyList())
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val closed = AtomicBoolean(false)
    /** NSD can report the same service repeatedly while a resolve is pending. */
    private val resolvingServices = ConcurrentHashMap<String, Long>()
    private val resolveSequence = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())

    val services: StateFlow<List<DiscoveredClusterEndpoint>> = _services.asStateFlow()

    @Synchronized
    fun startDiscovery() {
        check(!closed.get()) { "cluster discovery is closed" }
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                val key = serviceInfo.serviceName.take(128)
                val resolveToken = if (closed.get()) null else tryBeginResolve(key)
                if (resolveToken == null) return
                mainHandler.postDelayed(
                    { resolvingServices.remove(key, resolveToken) },
                    RESOLVE_TIMEOUT_MILLIS,
                )
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolvingServices.remove(key, resolveToken)
                        }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            resolvingServices.remove(key, resolveToken)
                            if (closed.get()) return
                            val host = resolved.host?.hostAddress ?: return
                            if (resolved.port !in 1024..65_535) return
                            _services.value = (_services.value + DiscoveredClusterEndpoint(
                                serviceName = resolved.serviceName.take(128),
                                host = host.take(253),
                                port = resolved.port,
                            )).distinctBy { "${it.host}:${it.port}" }.take(MAX_SERVICES)
                        }
                    })
                }.onFailure { resolvingServices.remove(key, resolveToken) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                resolvingServices.remove(serviceInfo.serviceName.take(128))
                _services.value = _services.value.filterNot { it.serviceName == serviceInfo.serviceName }
            }
        }
        discoveryListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stopDiscovery() {
        discoveryListener?.let { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        discoveryListener = null
        resolvingServices.clear()
        _services.value = emptyList()
    }

    @Synchronized
    fun unregister() {
        registrationListener?.let { listener -> runCatching { nsd.unregisterService(listener) } }
        registrationListener = null
    }

    @Synchronized
    fun register(nodeId: String, port: Int) {
        check(!closed.get()) { "cluster discovery is closed" }
        require(port in 1024..65_535)
        registrationListener?.let { listener -> runCatching { nsd.unregisterService(listener) } }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { registrationListener = null }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { registrationListener = null }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { registrationListener = null }
        }
        registrationListener = listener
        nsd.registerService(
            NsdServiceInfo().apply {
                serviceName = "AndroML-${nodeId.take(48)}"
                serviceType = SERVICE_TYPE
                this.port = port
            },
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopDiscovery()
        unregister()
    }

    private companion object {
        const val SERVICE_TYPE = "_androml._tcp."
        const val MAX_SERVICES = 32
        const val MAX_IN_FLIGHT_RESOLVES = 8
        const val RESOLVE_TIMEOUT_MILLIS = 15_000L
    }

    private fun tryBeginResolve(key: String): Long? = synchronized(resolvingServices) {
        if (resolvingServices.size >= MAX_IN_FLIGHT_RESOLVES || resolvingServices.containsKey(key)) {
            return null
        }
        resolveSequence.incrementAndGet().also { resolvingServices[key] = it }
    }
}
