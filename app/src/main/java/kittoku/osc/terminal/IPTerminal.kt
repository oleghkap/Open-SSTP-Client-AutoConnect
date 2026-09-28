package kittoku.osc.terminal

import android.net.IpPrefix
import android.os.Build
import android.os.ParcelFileDescriptor
import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.extension.toHexByteArray
import kittoku.osc.preference.LIST_TYPE_ALLOWED
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

internal class IPTerminal(private val bridge: SharedBridge) {
    private var fd: ParcelFileDescriptor? = null

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    private val doEnableAppBasedRule = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, bridge.prefs)
    private val isAllowedList = getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, bridge.prefs) == LIST_TYPE_ALLOWED
    private val doAddDefaultRoute = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE, bridge.prefs)
    private val doRoutePrivateAddresses = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ROUTE_PRIVATE_ADDRESSES, bridge.prefs)
    private val doUseCustomDNSServer = getBooleanPrefValue(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, bridge.prefs)
    private val doAddCustomRoutes = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_CUSTOM_ROUTES, bridge.prefs)

    internal suspend fun initialize() {
        if (bridge.PPP_IPv4_ENABLED) {
            if (bridge.currentIPv4.contentEquals(ByteArray(4))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv4, Result.ERR_INVALID_ADDRESS))
                return
            }

            InetAddress.getByAddress(bridge.currentIPv4).also {
                bridge.builder.addAddress(it, 32)
            }

            if (doUseCustomDNSServer) {
                bridge.builder.addDnsServer(getStringPrefValue(OscPrefKey.DNS_CUSTOM_ADDRESS, bridge.prefs))
            }

            if (!bridge.currentProposedDNS.contentEquals(ByteArray(4))) {
                InetAddress.getByAddress(bridge.currentProposedDNS).also {
                    bridge.builder.addDnsServer(it)
                }
            }

            setIPv4BasedRouting()
        }

        if (bridge.PPP_IPv6_ENABLED) {
            if (bridge.currentIPv6.contentEquals(ByteArray(16))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv6, Result.ERR_INVALID_ADDRESS))
                return
            }

            ByteArray(16).also { // for link local addresses
                "FE80".toHexByteArray().copyInto(it)
                ByteArray(6).copyInto(it, destinationOffset = 2)
                bridge.currentIPv6.copyInto(it, destinationOffset = 8)
                bridge.builder.addAddress(InetAddress.getByAddress(it), 64)
            }

            setIPv6BasedRouting()
        }

        if (doAddCustomRoutes) {
            addCustomRoutes()
        }

        if (doEnableAppBasedRule) {
            addAppBasedRules()
        }

        if (bridge.PPP_DO_SET_MTU) {
            bridge.builder.setMtu(bridge.PPP_MTU)
        }
        bridge.builder.setBlocking(true)

        fd = bridge.builder.establish()!!.also {
            inputStream = FileInputStream(it.fileDescriptor)
            outputStream = FileOutputStream(it.fileDescriptor)
        }

        bridge.controlMailbox.send(ControlMessage(Where.IP, Result.PROCEEDED))
    }

    private fun setIPv4BasedRouting() {
        if (doAddDefaultRoute) {
            bridge.builder.addRoute("0.0.0.0", 0)
        }

        if (doRoutePrivateAddresses) {
            bridge.builder.addRoute("10.0.0.0", 8)
            bridge.builder.addRoute("172.16.0.0", 12)
            bridge.builder.addRoute("192.168.0.0", 16)
        }
    }

    private fun setIPv6BasedRouting() {
        if (doAddDefaultRoute) {
            bridge.builder.addRoute("::", 0)
        }

        if (doRoutePrivateAddresses) {
            bridge.builder.addRoute("fc00::", 7)
        }
    }

    private fun addAppBasedRules() {
        bridge.selectedApps.forEach {
            if (isAllowedList) {
                bridge.builder.addAllowedApplication(it.packageName)
            } else {
                bridge.builder.addDisallowedApplication(it.packageName)
            }
        }
    }

    private suspend fun addCustomRoutes(): Boolean {
        val routes = getStringPrefValue(OscPrefKey.ROUTE_CUSTOM_ROUTES, bridge.prefs)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val parsedRoutes = mutableListOf<Triple<Boolean, String, Int>>()

        for (routeLine in routes) {
            val excluded = routeLine.startsWith("!")
            val route = if (excluded) routeLine.substring(1).trim() else routeLine
            val parsed = route.split("/")

            if (parsed.size != 2) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }

            val address = parsed[0].trim()
            val prefix = parsed[1].trim().toIntOrNull()

            if (address.isEmpty() || prefix == null) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }

            parsedRoutes += Triple(excluded, address, prefix)
        }

        // Add ordinary routes first. This preserves the existing manual-route behavior.
        for ((excluded, address, prefix) in parsedRoutes) {
            if (excluded) continue

            try {
                bridge.builder.addRoute(address, prefix)
            } catch (_: IllegalArgumentException) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }
        }

        // Apply exclusions last so a leading '!' always means "bypass the VPN",
        // even if a broader manual route appears elsewhere in the list.
        for ((excluded, address, prefix) in parsedRoutes) {
            if (!excluded) continue

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }

            try {
                val ipPrefix = IpPrefix(InetAddress.getByName(address), prefix)
                bridge.builder.excludeRoute(ipPrefix)
            } catch (_: IllegalArgumentException) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            } catch (_: java.net.UnknownHostException) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }
        }

        return true
    }

    internal fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        // nothing will be written until initialized
        // the position won't be changed
        outputStream?.write(buffer.array(), start, size)
    }

    internal fun readPacket(buffer: ByteBuffer) {
        buffer.clear()
        buffer.position(inputStream?.read(buffer.array(), 0, bridge.PPP_MTU) ?: buffer.position())
        buffer.flip()
    }

    internal fun close() {
        fd?.close()
    }
}
