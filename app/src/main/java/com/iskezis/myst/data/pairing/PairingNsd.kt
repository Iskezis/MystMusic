package com.iskezis.myst.data.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.iskezis.myst.util.AppLog

/**
 * Descoberta do MystMusic na rede local via NSD/DNS-SD. A TV anuncia o
 * serviço enquanto a tela de pareamento está aberta; o celular procura e
 * resolve o endereço. Nenhum pacote sai da rede Wi-Fi local.
 */
class PairingNsd(context: Context) {

    data class Target(
        val name: String,
        val host: String,
        val port: Int,
    )

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val multicastLock = wifi?.createMulticastLock("MystMusicPairing")?.apply {
        setReferenceCounted(false)
        runCatching { acquire() }
    }

    private var registered = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(
        serviceName: String,
        port: Int,
        onRegistered: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName.take(60)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered = true
                AppLog.i(
                    AppLog.TAG_PAIRING,
                    "NSD registrado: ${serviceInfo.serviceName} porta=$port",
                )
                onRegistered(serviceInfo.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                AppLog.w(AppLog.TAG_PAIRING, "Falha registrando NSD: $errorCode")
                onError("nsd_register_$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                AppLog.w(AppLog.TAG_PAIRING, "Falha removendo NSD: $errorCode")
            }
        }
        registrationListener = listener
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            AppLog.failure(AppLog.TAG_PAIRING, "Não foi possível registrar o NSD", it)
            onError("nsd_register")
        }
    }

    fun discover(
        onFound: (Target) -> Unit,
        onLost: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AppLog.i(AppLog.TAG_PAIRING, "NSD procurando $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isOurService(serviceInfo.serviceType)) return
                runCatching {
                    nsd.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int,
                            ) {
                                AppLog.w(
                                    AppLog.TAG_PAIRING,
                                    "Falha resolvendo ${serviceInfo.serviceName}: $errorCode",
                                )
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val host = resolved.host?.hostAddress
                                if (host.isNullOrBlank() || resolved.port <= 0) {
                                    onError("nsd_resolve_empty")
                                    return
                                }
                                AppLog.i(
                                    AppLog.TAG_PAIRING,
                                    "TV encontrada: ${resolved.serviceName} em $host:${resolved.port}",
                                )
                                onFound(
                                    Target(
                                        name = resolved.serviceName.ifBlank { "MystMusic TV" },
                                        host = host,
                                        port = resolved.port,
                                    ),
                                )
                            }
                        },
                    )
                }.onFailure {
                    AppLog.w(
                        AppLog.TAG_PAIRING,
                        "Resolve em andamento; nova tentativa na próxima descoberta",
                    )
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.i(AppLog.TAG_PAIRING, "NSD descoberta encerrada")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(AppLog.TAG_PAIRING, "Falha iniciando NSD: $errorCode")
                onError("nsd_discovery_$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(AppLog.TAG_PAIRING, "Falha encerrando NSD: $errorCode")
            }
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            AppLog.failure(AppLog.TAG_PAIRING, "Não foi possível iniciar descoberta NSD", it)
            onError("nsd_discovery")
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsd.stopServiceDiscovery(listener) }
            .onFailure { AppLog.w(AppLog.TAG_PAIRING, "Descoberta já estava parada") }
    }

    fun stop() {
        stopDiscovery()
        val listener = registrationListener ?: return
        registrationListener = null
        if (registered) {
            runCatching { nsd.unregisterService(listener) }
                .onFailure { AppLog.w(AppLog.TAG_PAIRING, "NSD já estava removido") }
        }
        registered = false
    }

    fun close() {
        stop()
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_mystmusic._tcp."

        /**
         * Alguns fabricantes devolvem o tipo sem o ponto final (ou com domínio
         * extra). Comparar normalizado evita esconder uma TV válida.
         */
        fun isOurService(type: String?): Boolean {
            val clean = type.orEmpty().trim().trimEnd('.').lowercase()
            return clean == SERVICE_TYPE.trimEnd('.').lowercase()
        }
    }
}
