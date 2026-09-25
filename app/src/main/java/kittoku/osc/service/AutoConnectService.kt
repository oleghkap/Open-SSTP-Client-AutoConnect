package kittoku.osc.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.*
import android.net.wifi.WifiInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kittoku.osc.R
import kittoku.osc.activity.MainActivity
import kittoku.osc.preference.OscPrefKey

internal class AutoConnectService : Service() {
    private enum class T { WIFI, MOBILE }
    private data class N(val network: Network, val type: T, val ssid: String?, val validated: Boolean)

    private lateinit var cm: ConnectivityManager
    private lateinit var nm: NotificationManager
    private val h = Handler(Looper.getMainLooper())
    private val networks = mutableMapOf<Network, N>()
    private var registered = false
    private var active: N? = null

    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(n: Network) = update(n)
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = update(n, c)
            override fun onLost(n: Network) { networks.remove(n); evaluate() }
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = update(n)
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = update(n, c)
            override fun onLost(n: Network) { networks.remove(n); evaluate() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Auto-connect", NotificationManager.IMPORTANCE_LOW))
        val notification = notification("Auto-connect monitor")
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (!enabled()) { stopSelf(); return START_NOT_STICKY }
        if (!registered) {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            runCatching { cm.registerNetworkCallback(req, callback); registered = true }
        }
        return START_STICKY
    }

    private fun update(network: Network, supplied: NetworkCapabilities? = null) {
        val c = supplied ?: cm.getNetworkCapabilities(network) ?: return
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            networks.remove(network); return
        }
        val type = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> T.WIFI
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> T.MOBILE
            else -> { networks.remove(network); return }
        }
        val ssid = if (type == T.WIFI) wifiSsid(c) else null
        networks[network] = N(network, type, ssid, c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        evaluate()
    }

    private fun wifiSsid(c: NetworkCapabilities): String? {
        val info = c.transportInfo as? WifiInfo
        val s = info?.ssid?.trim('"')?.trim()
        if (!s.isNullOrEmpty() && s != UNKNOWN) return s
        return null
    }

    private fun evaluate() {
        h.removeCallbacksAndMessages(TOKEN)
        h.postAtTime({
            val now = networks.values.sortedWith(
                compareByDescending<N> { it.validated }.thenByDescending { it.type == T.WIFI }
            ).firstOrNull()
            val old = active
            active = now

            if (now == null) {
                if (old?.type == T.MOBILE && pref(OscPrefKey.AUTO_DISCONNECT_MOBILE)) disconnect("mobile network lost")
                if (old?.type == T.WIFI && pref(OscPrefKey.AUTO_DISCONNECT_WIFI)) disconnect("Wi-Fi network lost")
                status("Автоподключение включено")
                return@postAtTime
            }

            val changed = old == null || old.type != now.type || (now.type == T.WIFI && old.ssid != now.ssid)
            val oldMustDisconnect = old != null && old.type != now.type &&
                ((old.type == T.MOBILE && pref(OscPrefKey.AUTO_DISCONNECT_MOBILE)) ||
                 (old.type == T.WIFI && pref(OscPrefKey.AUTO_DISCONNECT_WIFI)))

            if (oldMustDisconnect) disconnect(old!!.type.name.lowercase() + " network lost")
            if (!changed) return@postAtTime

            if (now.type == T.MOBILE) {
                status("Автоподключение включено")
                if (pref(OscPrefKey.AUTO_CONNECT_MOBILE)) {
                    if (oldMustDisconnect) h.postDelayed({ connect("Mobile") }, 300)
                    else connect("Mobile")
                }
            } else {
                status("Автоподключение включено")
                if (wifiAllowed(now.ssid)) {
                    if (oldMustDisconnect) h.postDelayed({ connect("Wi-Fi") }, 300)
                    else connect("Wi-Fi")
                }
            }
        }, TOKEN, 150)
    }

    private fun wifiAllowed(ssid: String?): Boolean {
        if (pref(OscPrefKey.AUTO_CONNECT_DISABLED) || ssid.isNullOrEmpty()) return false
        val allow = pref(OscPrefKey.AUTO_CONNECT_WIFI_ALLOW)
        val deny = pref(OscPrefKey.AUTO_CONNECT_WIFI_DENY)
        val allowed = !allow || ssid in set(OscPrefKey.AUTO_WIFI_ALLOW_SSIDS)
        val denied = deny && ssid in set(OscPrefKey.AUTO_WIFI_DENY_SSIDS)
        return allowed && !denied
    }

    private fun connect(reason: String) {
        if (pref(OscPrefKey.AUTO_CONNECT_DISABLED) || pref(OscPrefKey.ROOT_STATE)) return
        if (prefs().getString(OscPrefKey.HOME_HOSTNAME.name, "").isNullOrBlank()) return
        status("Автоподключение включено")
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, SstpVpnService::class.java).setAction(ACTION_CONNECT))
        }
    }

    private fun disconnect(reason: String) {
        if (!pref(OscPrefKey.ROOT_STATE)) return
        status("Автоподключение включено")
        runCatching { startService(Intent(this, SstpVpnService::class.java).setAction(ACTION_DISCONNECT)) }
    }

    private fun enabled() =
        pref(OscPrefKey.AUTO_CONNECT_MOBILE) || pref(OscPrefKey.AUTO_DISCONNECT_MOBILE) ||
        pref(OscPrefKey.AUTO_CONNECT_WIFI_ALLOW) || pref(OscPrefKey.AUTO_CONNECT_WIFI_DENY) ||
        pref(OscPrefKey.AUTO_DISCONNECT_WIFI)

    private fun prefs() = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
    private fun pref(k: OscPrefKey) = prefs().getBoolean(k.name, false)
    private fun set(k: OscPrefKey): Set<String> = prefs().getStringSet(k.name, emptySet()).orEmpty()

    private fun status(text: String) {
        nm.notify(ID, notification(text))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
        .setContentTitle("Open SSTP Client")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        h.removeCallbacksAndMessages(null)
        networks.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CHANNEL = "AUTO_CONNECT"
        private const val ID = 100
        private const val TOKEN = "AUTO_CONNECT_EVALUATE"
        private const val UNKNOWN = "<unknown ssid>"
        private const val ACTION_CONNECT = "kittoku.osc.connect"
        private const val ACTION_DISCONNECT = "kittoku.osc.disconnect"

        fun ensureRunning(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AutoConnectService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoConnectService::class.java))
        }
    }
}