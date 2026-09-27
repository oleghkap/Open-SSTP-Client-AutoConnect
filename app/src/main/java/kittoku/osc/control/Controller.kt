package kittoku.osc.control

import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.client.SSTP_REQUEST_TIMEOUT
import kittoku.osc.client.SstpClient
import kittoku.osc.client.ppp.IpcpClient
import kittoku.osc.client.ppp.Ipv6cpClient
import kittoku.osc.client.ppp.LCPClient
import kittoku.osc.client.ppp.PPPClient
import kittoku.osc.client.ppp.PPP_NEGOTIATION_TIMEOUT
import kittoku.osc.client.ppp.auth.ChapClient
import kittoku.osc.client.ppp.auth.ChapMSCHAPV2Client
import kittoku.osc.client.ppp.auth.EAPClient
import kittoku.osc.client.ppp.auth.EAPMSAuthClient
import kittoku.osc.client.ppp.auth.PAPClient
import kittoku.osc.debug.assertAlways
import kittoku.osc.io.OutgoingManager
import kittoku.osc.io.incoming.IncomingManager
import kittoku.osc.preference.AUTH_PROTOCOL_EAP_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOL_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOl_PAP
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.resetReconnectionLife
import kittoku.osc.terminal.SSL_REQUEST_INTERVAL
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_ABORT
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull


internal class Controller(internal val bridge: SharedBridge, private val generation: Long) {
    private var observer: NetworkObserver? = null

    private var sstpClient: SstpClient? = null
    private var pppClient: PPPClient? = null
    private var incomingManager: IncomingManager? = null
    private var outgoingManager: OutgoingManager? = null

    private var lcpClient: LCPClient? = null
    private var papClient: PAPClient? = null
    private var chapClient: ChapClient? = null
    private var eapClient: EAPClient? = null
    private var ipcpClient: IpcpClient? = null
    private var ipv6cpClient: Ipv6cpClient? = null

    private var jobMain: Job? = null
    private var jobKill: Job? = null

    private val mutex = Mutex()

    private val isReconnectionEnabled = getBooleanPrefValue(OscPrefKey.RECONNECTION_ENABLED, bridge.prefs)
    private val isReconnectionAvailable: Boolean
        get() = getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, bridge.prefs) > 0

    private fun attachHandler() {
        bridge.handler = CoroutineExceptionHandler { _, throwable ->
            kill(isReconnectionEnabled, cleanup = {
                val header = "OSC: ERR_UNEXPECTED"
                bridge.service.logWriter?.report(header + "\n" + throwable.stackTraceToString())
                bridge.service.notifyError(header)
            })
        }
    }

    internal fun launchJobMain() {
        attachHandler()

        jobMain = bridge.service.scope.launch(bridge.handler) {
            bridge.attachSSLTerminal()
            bridge.attachIPTerminal()

            bridge.sslTerminal!!.initialize()
            if (!expectProceeded(Where.SSL, SSL_REQUEST_INTERVAL)) {
                return@launch
            }

            IncomingManager(bridge).also {
                it.launchJobMain()
                incomingManager = it
            }

            OutgoingManager(bridge).also {
                it.launchJobMain()
                outgoingManager = it
            }

            SstpClient(bridge).also {
                sstpClient = it
                it.launchJobMain()
            }

            if (!expectProceeded(Where.SSTP, SSTP_REQUEST_TIMEOUT)) {
                return@launch
            }

            PPPClient(bridge).also {
                pppClient = it
                it.launchJobMain()
            }

            if (!expectProceeded(Where.PPP, PPP_NEGOTIATION_TIMEOUT)) {
                return@launch
            }

            LCPClient(bridge).also {
                lcpClient = it
                it.launchJobMain()
            }

            if (!expectProceeded(Where.LCP, PPP_NEGOTIATION_TIMEOUT)) {
                return@launch
            }

            when (getIntPrefValue(OscPrefKey.AUTH_PROTOCOL, bridge.prefs)) {
                AUTH_PROTOCOl_PAP -> PAPClient(bridge).also {
                    papClient = it
                    it.launchJobMain()
                }
                AUTH_PROTOCOL_MSCHAPv2 -> ChapClient(bridge).also {
                    chapClient = it
                    it.launchJobMain()
                }
                AUTH_PROTOCOL_EAP_MSCHAPv2 -> ChapMSCHAPV2Client(bridge).also {
                    chapClient = it
                    it.launchJobMain()
                }
                else -> EAPClient(bridge).also {
                    eapClient = it
                    it.launchJobMain()
                }
            }

            if (!expectProceeded(Where.AUTH, PPP_NEGOTIATION_TIMEOUT)) {
                return@launch
            }

            IpcpClient(bridge).also {
                ipcpClient = it
                it.launchJobMain()
            }

            if (!expectProceeded(Where.IPCP, PPP_NEGOTIATION_TIMEOUT)) {
                return@launch
            }

            if (getBooleanPrefValue(OscPrefKey.IPV6CP_ENABLED, bridge.prefs)) {
                Ipv6cpClient(bridge).also {
                    ipv6cpClient = it
                    it.launchJobMain()
                }
                if (!expectProceeded(Where.IPV6CP, PPP_NEGOTIATION_TIMEOUT)) {
                    return@launch
                }
            }

            bridge.service.notifyConnected()
            resetReconnectionLife(bridge.prefs)
            bridge.service.setRootState(true)
            bridge.service.logWriter?.write("VPN connection established")
        }
    }

    private suspend fun expectProceeded(where: Where, timeout: Long): Boolean {
        val received = withTimeoutOrNull(timeout) {
            bridge.channel.receive()
        } ?: ControlMessage(where, Result.ERR_TIMEOUT)

        if (received.result == Result.PROCEEDED) {
            assertAlways(received.from == where)
            return true
        }

        val lastPacketType = if (received.result == Result.ERR_DISCONNECT_REQUESTED) {
            SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
        } else {
            SSTP_MESSAGE_TYPE_CALL_ABORT
        }

        kill(isReconnectionEnabled, cleanup = {
            sstpClient?.sendLastPacket(lastPacketType)

            val header = "${received.from.name}: ${received.result.name}"
            var log = header
            if (received.supplement != null) {
                log += "\n${received.supplement}"
            }

            bridge.service.logWriter?.report(log)
            bridge.service.notifyError(header)
        })

        return false
    }

    internal fun disconnect() { // use if the user want to normally disconnect
        kill(false, cleanup = {
            sstpClient?.sendLastPacket(SSTP_MESSAGE_TYPE_CALL_DISCONNECT)
        })
    }

    internal fun kill(isReconnectionRequested: Boolean, cleanup: (suspend () -> Unit)?, stopService: Boolean = true): Job? {
        if (!mutex.tryLock()) return jobKill

        jobKill = bridge.service.scope.launch {
            try {
                observer?.close()

                jobMain?.cancel()
                cancelClients()

                cleanup?.invoke()

                closeTerminals()

                if (isReconnectionRequested && isReconnectionAvailable) {
                    bridge.service.launchJobReconnect(generation)
                } else if (stopService) {
                    bridge.service.close()
                }
            } finally {
                mutex.unlock()
            }
        }
        return jobKill
    }

    internal suspend fun awaitKill() {
        jobKill?.join()
    }

    private fun cancelClients() {
        lcpClient?.cancel()
        papClient?.cancel()
        chapClient?.cancel()
        eapClient?.cancel()
        ipcpClient?.cancel()
        ipv6cpClient?.cancel()
        sstpClient?.cancel()
        pppClient?.cancel()
        incomingManager?.cancel()
        outgoingManager?.cancel()
    }

    private fun closeTerminals() {
        bridge.sslTerminal?.close()
        bridge.ipTerminal?.close()
    }
}
