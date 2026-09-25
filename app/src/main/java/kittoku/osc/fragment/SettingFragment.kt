package kittoku.osc.fragment

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.ContextCompat
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.PreferenceFragmentCompat
import kittoku.osc.R
import kittoku.osc.activity.BLANK_ACTIVITY_TYPE_APPS
import kittoku.osc.activity.BlankActivity
import kittoku.osc.activity.EXTRA_KEY_TYPE
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.setURIPrefValue
import kittoku.osc.preference.custom.DirectoryPreference
import kittoku.osc.preference.custom.RouteSelectedAppsPreference


internal class SettingFragment : PreferenceFragmentCompat() {
    private lateinit var prefs: SharedPreferences

    private lateinit var certDirPref: DirectoryPreference
    private lateinit var logDirPref: DirectoryPreference
    private lateinit var selectAppsPref: RouteSelectedAppsPreference

    private val certDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.SSL_CERT_DIR, prefs)

        certDirPref.updateView()
    }

    private val logDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.LOG_DIR, prefs)

        logDirPref.updateView()
    }

    private val selectAppsLauncher = registerForActivityResult(StartActivityForResult()) {
        selectAppsPref.updateView()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        prefs = preferenceManager.sharedPreferences!!

        certDirPref = findPreference(OscPrefKey.SSL_CERT_DIR.name)!!
        logDirPref = findPreference(OscPrefKey.LOG_DIR.name)!!
        selectAppsPref = findPreference(OscPrefKey.ROUTE_SELECTED_APPS.name)!!

        setCertDirListener()
        setLogDirListener()
        setSelectAppsListener()
        setupAutoConnect()
    }

    private fun setCertDirListener() {
        certDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also {
                it.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                certDirLauncher.launch(it)
            }

            true
        }
    }

    private fun setLogDirListener() {
        logDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also {
                it.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                logDirLauncher.launch(it)
            }

            true
        }
    }

    private fun setSelectAppsListener() {
        selectAppsPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(requireContext(), BlankActivity::class.java).also {
                it.putExtra(EXTRA_KEY_TYPE, BLANK_ACTIVITY_TYPE_APPS)
                selectAppsLauncher.launch(it)
            }

            true
        }
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
            findPreference<SwitchPreferenceCompat>(key.name)?.setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                prefs.edit().putBoolean(key.name, enabled).apply()
                if (enabled) {
                    requestBackgroundPermissions(
                        key == OscPrefKey.AUTO_CONNECT_WIFI_ALLOW ||
                            key == OscPrefKey.AUTO_CONNECT_WIFI_DENY
                    )
                }
                syncAutoConnect()
                true
            }
        }

        findPreference<MultiSelectListPreference>(OscPrefKey.AUTO_WIFI_ALLOW_SSIDS.name)?.let { preference ->
            preference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                refreshWifi(preference)
                false
            }
            updateSummary(preference)
        }

        findPreference<MultiSelectListPreference>(OscPrefKey.AUTO_WIFI_DENY_SSIDS.name)?.let { preference ->
            preference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                refreshWifi(preference)
                false
            }
            updateSummary(preference)
        }
    }

    private fun syncAutoConnect() {
        val enabled =
            prefs.getBoolean(OscPrefKey.AUTO_CONNECT_MOBILE.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_DISCONNECT_MOBILE.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_ALLOW.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_CONNECT_WIFI_DENY.name, false) ||
            prefs.getBoolean(OscPrefKey.AUTO_DISCONNECT_WIFI.name, false)

        val context = requireContext().applicationContext
        if (enabled) AutoConnectService.ensureRunning(context) else AutoConnectService.stop(context)
    }

    private fun requestBackgroundPermissions(wifiRequired: Boolean) {
        val a = activity ?: return
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (wifiRequired) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(a, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
            ) {
                missing += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            if (ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }

        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)

        if (Build.VERSION.SDK_INT >= 23) {
            val pm = a.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(a.packageName)) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + a.packageName)
                        )
                    )
                }
            }
        }
    }

    private fun refreshWifi(preference: MultiSelectListPreference) {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            preference.entries = arrayOf("Grant Location permission to read Wi-Fi SSIDs")
            preference.entryValues = arrayOf("__permission__")
            return
        }

        val wifiManager = requireContext().getSystemService(android.net.wifi.WifiManager::class.java)
        runCatching { wifiManager?.startScan() }

        val saved = preference.values
        val scanned = runCatching {
            wifiManager?.scanResults.orEmpty()
                .map { it.SSID.trim() }
                .filter { it.isNotEmpty() && it != "<unknown ssid>" }
        }.getOrDefault(emptyList())

        val ssids = (scanned + saved).filter { it.isNotBlank() }.distinct().sorted()

        if (ssids.isEmpty()) {
            preference.entries = arrayOf("No Wi-Fi SSIDs found in the latest scan")
            preference.entryValues = arrayOf("__empty__")
        } else {
            preference.entries = ssids.toTypedArray()
            preference.entryValues = ssids.toTypedArray()
        }
    }

    private fun updateSummary(preference: MultiSelectListPreference) {
        val count = preference.values.size
        preference.summary = if (count == 0) "No networks selected" else "$count network(s) selected"
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1107
    }

}

