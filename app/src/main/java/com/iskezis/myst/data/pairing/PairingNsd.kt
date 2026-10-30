package com.iskezis.myst.data.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var registrationPending = false
    private var discoveryPending = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(
        serviceName: String,
        port: Int,
        onRegistered: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        registrationPending = true
        startRegistration(serviceName.take(60), port, onRegistered, onError, attempt = 0)
    }

    private fun startRegistration(
        serviceName: String,
        port: Int,
        onRegistered: (String) -> Unit,
        onError: (String) -> Unit,
        attempt: Int,
    ) {
        if (!registrationPending) return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (registrationListener !== this) return
                registrationPending = false
                registered = true
                AppLog.i(
                    AppLog.TAG_PAIRING,
                    "NSD registrado: ${serviceInfo.serviceName} porta=$port",
                )
                onRegistered(serviceInfo.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (registrationListener !== this) return
                registered = false
                AppLog.w(
                    AppLog.TAG_PAIRING,
                    "Falha registrando NSD (tentativa ${attempt + 1}): $errorCode",
                )
                // Reiniciar o canal persistente logo após o anterior pode
                // encontrar o NsdManager ainda com o registro antigo ativo.
                if (registrationPending && attempt < MAX_NSD_RETRIES &&
                    errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                ) {
                    handler.postDelayed({
                        startRegistration(
                            serviceName,
                            port,
                            onRegistered,
                            onError,
                            attempt + 1,
                        )
                    }, NSD_RETRY_MS)
                } else {
                    registrationPending = false
                    onError("nsd_register_$errorCode")
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                if (registrationListener !== this) return
                registered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (registrationListener !== this) return
                registered = false
                AppLog.w(AppLog.TAG_PAIRING, "Falha removendo NSD: $errorCode")
            }
        }
        registrationListener = listener
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            AppLog.failure(AppLog.TAG_PAIRING, "Não foi possível registrar o NSD", it)
            if (registrationPending && attempt < MAX_NSD_RETRIES) {
                handler.postDelayed({
                    startRegistration(serviceName, port, onRegistered, onError, attempt + 1)
                }, NSD_RETRY_MS)
            } else {
                registrationPending = false
                onError("nsd_register")
            }
        }
    }

    fun discover(
        onFound: (Target) -> Unit,
        onLost: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stopDiscovery()
        discoveryPending = true
        startDiscovery(onFound, onLost, onError, attempt = 0)
    }

    private fun startDiscovery(
        onFound: (Target) -> Unit,
        onLost: (String) -> Unit,
        onError: (String) -> Unit,
        attempt: Int,
    ) {
        if (!discoveryPending) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (discoveryListener !== this) return
                discoveryPending = false
                AppLog.i(AppLog.TAG_PAIRING, "NSD procurando $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (discoveryListener !== this) return
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
                if (discoveryListener !== this) return
                onLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (discoveryListener !== this) return
                AppLog.i(AppLog.TAG_PAIRING, "NSD descoberta encerrada")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (discoveryListener !== this) return
                AppLog.w(
                    AppLog.TAG_PAIRING,
                    "Falha iniciando NSD (tentativa ${attempt + 1}): $errorCode",
                )
                if (discoveryPending && attempt < MAX_NSD_RETRIES &&
                    errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                ) {
                    handler.postDelayed({
                        startDiscovery(onFound, onLost, onError, attempt + 1)
                    }, NSD_RETRY_MS)
                } else {
                    discoveryPending = false
                    onError("nsd_discovery_$errorCode")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (discoveryListener !== this) return
                AppLog.w(AppLog.TAG_PAIRING, "Falha encerrando NSD: $errorCode")
            }
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            AppLog.failure(AppLog.TAG_PAIRING, "Não foi possível iniciar descoberta NSD", it)
            if (discoveryPending && attempt < MAX_NSD_RETRIES) {
                handler.postDelayed({
                    startDiscovery(onFound, onLost, onError, attempt + 1)
                }, NSD_RETRY_MS)
            } else {
                discoveryPending = false
                onError("nsd_discovery")
            }
        }
    }

    fun stopDiscovery() {
        discoveryPending = false
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsd.stopServiceDiscovery(listener) }
            .onFailure { AppLog.w(AppLog.TAG_PAIRING, "Descoberta já estava parada") }
    }

    fun stop() {
        stopDiscovery()
        registrationPending = false
        val listener = registrationListener ?: return
        registrationListener = null
        // Tenta remover mesmo se o callback de sucesso ainda não chegou: um
        // registro pendente deixado para trás faz o próximo pareamento falhar
        // com FAILURE_ALREADY_ACTIVE.
        runCatching { nsd.unregisterService(listener) }
            .onFailure { AppLog.w(AppLog.TAG_PAIRING, "NSD já estava removido") }
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
        private const val MAX_NSD_RETRIES = 3
        private const val NSD_RETRY_MS = 450L

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
