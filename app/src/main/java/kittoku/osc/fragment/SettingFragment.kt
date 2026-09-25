package kittoku.osc.fragment

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import kittoku.osc.R
import kittoku.osc.activity.BLANK_ACTIVITY_TYPE_APPS
import kittoku.osc.activity.BlankActivity
import kittoku.osc.activity.EXTRA_KEY_TYPE
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.setURIPrefValue
import kittoku.osc.preference.custom.DirectoryPreference
import kittoku.osc.preference.custom.RouteSelectedAppsPreference
import kittoku.osc.service.AutoConnectService

internal class SettingFragment : PreferenceFragmentCompat() {
    private lateinit var prefs: SharedPreferences
    private lateinit var certDirPref: DirectoryPreference
    private lateinit var logDirPref: DirectoryPreference
    private lateinit var selectAppsPref: RouteSelectedAppsPreference

    private val certDirLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        setURIPrefValue(if (r.resultCode == Activity.RESULT_OK) r.data?.data else null, OscPrefKey.SSL_CERT_DIR, prefs)
        certDirPref.updateView()
    }

    private val logDirLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        setURIPrefValue(if (r.resultCode == Activity.RESULT_OK) r.data?.data else null, OscPrefKey.LOG_DIR, prefs)
        logDirPref.updateView()
    }

    private val selectAppsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { selectAppsPref.updateView() }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        certDirPref = findPreference(OscPrefKey.SSL_CERT_DIR.name)!!
        logDirPref = findPreference(OscPrefKey.LOG_DIR.name)!!
        selectAppsPref = findPreference(OscPrefKey.ROUTE_SELECTED_APPS.name)!!
        certDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            certDirLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)); true
        }
        logDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            logDirLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)); true
        }
        selectAppsPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            selectAppsLauncher.launch(Intent(requireContext(), BlankActivity::class.java).apply {
                putExtra(EXTRA_KEY_TYPE, BLANK_ACTIVITY_TYPE_APPS)
            }); true
        }
        setupAutoConnect()
    }

    private fun setupAutoConnect() {
        listOf(
            OscPrefKey.AUTO_CONNECT_DISABLED,
            OscPrefKey.AUTO_CONNECT_MOBILE,
            OscPrefKey.AUTO_DISCONNECT_MOBILE,
            OscPrefKey.AUTO_CONNECT_WIFI_ALLOW,
            OscPrefKey.AUTO_CONNECT_WIFI_DENY,
            OscPrefKey.AUTO_DISCONNECT_WIFI
        ).forEach { key ->
            findPreference<SwitchPreferenceCompat>(key.name)?.setOnPreferenceChangeListener { _, v ->
                prefs.edit().putBoolean(key.name, v as Boolean).apply()
                if (v) requestBackgroundPermissions(
                    key == OscPrefKey.AUTO_CONNECT_WIFI_ALLOW || key == OscPrefKey.AUTO_CONNECT_WIFI_DENY
                )
                syncAutoConnect()
                true
            }
        }

        findPreference<MultiSelectListPreference>(OscPrefKey.AUTO_WIFI_ALLOW_SSIDS.name)?.let { p ->
            p.onPreferenceClickListener = Preference.OnPreferenceClickListener { refreshWifi(p); true }
            updateSummary(p)
            p.setOnPreferenceChangeListener { _, v -> updateSummary(p, (v as Set<*>).size); true }
        }

        findPreference<MultiSelectListPreference>(OscPrefKey.AUTO_WIFI_DENY_SSIDS.name)?.let { p ->
            p.onPreferenceClickListener = Preference.OnPreferenceClickListener { refreshWifi(p); true }
            updateSummary(p)
            p.setOnPreferenceChangeListener { _, v -> updateSummary(p, (v as Set<*>).size); true }
        }

        syncAutoConnect()
    }

    private fun syncAutoConnect() {
        if (!::prefs.isInitialized) return
        val enabled =
            prefs.getBoolean(OscPrefKey.AUTO_CONNECT_MOBILE.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_DISCONNECT_MOBILE.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_ALLOW.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_DENY.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_DISCONNECT_WIFI.name, false)

        if (enabled) AutoConnectService.ensureRunning(requireContext().applicationContext)
        else AutoConnectService.stop(requireContext().applicationContext)
    }

    private fun requestBackgroundPermissions(wifiRequired: Boolean) {
        val a = activity ?: return
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.POST_NOTIFICATIONS

        if (wifiRequired) {
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(a, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
            ) missing += Manifest.permission.NEARBY_WIFI_DEVICES

            if (ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                missing += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)

        if (Build.VERSION.SDK_INT >= 23) {
            val pm = a.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(a.packageName)) runCatching {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.packageName)
                ))
            }
        }
    }

    private fun refreshWifi(p: MultiSelectListPreference) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.entries = arrayOf("Grant Location permission to read Wi-Fi SSIDs")
            p.entryValues = arrayOf("__permission__")
            return
        }

        val wm = requireContext().getSystemService(android.net.wifi.WifiManager::class.java)
        runCatching { wm?.startScan() }
        val saved = p.values
        val scanned = runCatching {
            wm?.scanResults.orEmpty().map { it.SSID.trim() }
                .filter { it.isNotEmpty() && it != "<unknown ssid>" }
        }.getOrDefault(emptyList())

        val ssids = (scanned + saved).filter { it.isNotBlank() }.distinct().sorted()
        if (ssids.isEmpty()) {
            p.entries = arrayOf("No Wi-Fi SSIDs found in the latest scan")
            p.entryValues = arrayOf("__empty__")
        } else {
            p.entries = ssids.toTypedArray()
            p.entryValues = ssids.toTypedArray()
        }
    }

    private fun updateSummary(p: MultiSelectListPreference, count: Int = p.values.size) {
        p.summary = if (count == 0) "No networks selected" else count.toString() + " network(s) selected"
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) syncAutoConnect()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1107
    }
}