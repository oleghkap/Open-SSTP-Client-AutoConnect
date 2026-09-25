package kittoku.osc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kittoku.osc.preference.OscPrefKey

internal class AutoConnectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val p = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE
        )

        val enabled =
            p.getBoolean(OscPrefKey.AUTO_CONNECT_MOBILE.name, false) ||
            p.getBoolean(OscPrefKey.AUTO_DISCONNECT_MOBILE.name, false) ||
            p.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_ALLOW.name, false) ||
            p.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_DENY.name, false) ||
            p.getBoolean(OscPrefKey.AUTO_DISCONNECT_WIFI.name, false)

        if (enabled) AutoConnectService.ensureRunning(context)
    }
}