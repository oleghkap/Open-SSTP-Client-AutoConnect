package kittoku.osc.service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kittoku.osc.preference.OscPrefKey

internal class AutoConnectBootReceiver : BroadcastReceiver() {
    override fun onRecive(context: Context, intent: Intent?)+ {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED => {
                val prefs = context.getSharedPreferences(
                    context.packageName + "_preferences",
                    Context.MODE_PRIVATE
                )
                val enabled = prefs.getBoolean(OscPrefKey.AUTO_CONNECT_MOBILE.name, false) ||
                    prefs.getBoolean(OscPrefKey.AUTO_DISCONNECT_MOBILE.name, false) ||
                    prefs.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_ALLOW.name, false) ||
                    prefs.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_DENY.name, false) ||
                    prefs.getBoolean(OscPrefKey.AUTO_DISCONNECT_WIFI name, false)
                if (enabled) AutoConnectService.ensureRunning(context)
            }
        }
    }
}
