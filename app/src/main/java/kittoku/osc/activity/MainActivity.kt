package kittoku.osc.activity

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.databinding.ActivityMainBinding
import kittoku.osc.fragment.HomeFragment
import kittoku.osc.fragment.SettingFragment
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.Profile
import kittoku.osc.preference.ACTIVE_PROFILE_KEY
import kittoku.osc.preference.EDITING_PROFILE_KEY
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.importProfile
import kittoku.osc.preference.serializeProfile
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.ACTION_VPN_RESTART
import kittoku.osc.service.SstpVpnService
import com.google.android.material.tabs.TabLayoutMediator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var homeFragment: HomeFragment
    private lateinit var settingFragment: SettingFragment
    private var settingsDirty = false
    private var suppressPreferenceDirty = true
    private val handler = Handler(Looper.getMainLooper())
    private val profileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) homeFragment.refreshProfiles()
    }

    private val vpnPreparationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            connectVpn()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.also {
            val profile = contentResolver.openInputStream(it)?.let { stream ->
                BufferedInputStream(stream).use { input -> deserializeProfile(input.reader(Charsets.UTF_8).readText()) }
            }
            if (profile == null) {
                Toast.makeText(this, R.string.toast_import_failed, Toast.LENGTH_SHORT).show()
            } else {
                importExternalProfile(profile)
            }
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.also {
            contentResolver.openOutputStream(it)?.use { stream ->
                BufferedOutputStream(stream).use { out -> out.write(serializeProfile(prefs).toByteArray(Charsets.UTF_8)) }
            }
            Toast.makeText(this, R.string.toast_profile_exported, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.fitsSystemWindows = true
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        homeFragment = HomeFragment()
        settingFragment = SettingFragment()

        object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> homeFragment
                1 -> settingFragment
                else -> throw IllegalArgumentException(position.toString())
            }
        }.also { binding.pager.adapter = it }

        TabLayoutMediator(binding.tabBar, binding.pager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.tab_home) else getString(R.string.tab_settings)
        }.attach()

        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (!suppressPreferenceDirty && key != null && OscPrefKey.entries.any { it.name == key } &&
                key !in setOf(OscPrefKey.ROOT_STATE.name, OscPrefKey.HOME_STATUS.name, OscPrefKey.HOME_CONNECTOR.name)) {
                updateDirtyState()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        showHome()
    }

    fun openProfileForProfile(profileKey: String) {
        val json = prefs.getString(profileKey, null)
        val profile = json?.let(::deserializeProfile) ?: return
        val activeKey = prefs.getString(ACTIVE_PROFILE_KEY, null)

        suppressPreferenceDirty = true
        importProfile(profile, prefs)
        val editor = prefs.edit().putString(EDITING_PROFILE_KEY, profileKey)
        if (activeKey != null) editor.putString(ACTIVE_PROFILE_KEY, activeKey)
        editor.apply()
        suppressPreferenceDirty = false
        updateDirtyState()

        findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).setCurrentItem(1, false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.profile_title)
        invalidateOptionsMenu()
    }

    fun createNewProfile() {
        val activeKey = prefs.getString(ACTIVE_PROFILE_KEY, null)
        showProfileNameDialog {
            suppressPreferenceDirty = true
            importProfile(null, prefs)
            val key = PROFILE_KEY_HEADER + it
            val editor = prefs.edit().putString(EDITING_PROFILE_KEY, key)
            if (activeKey != null) editor.putString(ACTIVE_PROFILE_KEY, activeKey)
            editor.apply()
            suppressPreferenceDirty = false
            settingsDirty = true
            invalidateOptionsMenu()
            findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).setCurrentItem(1, false)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.profile_title)
        }
    }

    fun activateProfile(profileKey: String, enabled: Boolean) {
        if (!enabled) {
            if (prefs.getString(ACTIVE_PROFILE_KEY, null) == profileKey) {
                prefs.edit().remove(ACTIVE_PROFILE_KEY).apply()
                disconnectVpn()
            }
            homeFragment.refreshProfiles()
            return
        }
        val profile = prefs.getString(profileKey, null)?.let(::deserializeProfile) ?: return
        if (profile == null) return
        val previousActiveKey = prefs.getString(ACTIVE_PROFILE_KEY, null)
        suppressPreferenceDirty = true
        importProfile(profile, prefs)
        prefs.edit().putString(ACTIVE_PROFILE_KEY, profileKey).putString(EDITING_PROFILE_KEY, profileKey).apply()
        suppressPreferenceDirty = false
        updateDirtyState()
        handler.post {
            if (!isFinishing && ::homeFragment.isInitialized && homeFragment.isAdded) {
                homeFragment.refreshProfiles()
            }
        }
        // Do not use ROOT_STATE here: it can remain true after a failed/crashed session.
        // Every profile activation must go through VpnService.prepare() so Android can
        // request VPN consent when necessary.
        if (previousActiveKey != profileKey) {
            connectVpn()
        } else {
            connectVpn()
        }
    }

    fun showHome() {
        if (::prefs.isInitialized) {
            findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)?.setCurrentItem(0, false)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        title = getString(R.string.app_name)
        if (::homeFragment.isInitialized && homeFragment.isAdded) homeFragment.refreshProfiles()
        invalidateOptionsMenu()
    }

    override fun onSupportNavigateUp(): Boolean {
        showHome()
        return true
    }

    override fun onBackPressed() {
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
        if (pager.currentItem != 0) showHome() else super.onBackPressed()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        MenuInflater(this).inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val page = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).currentItem
        menu.findItem(R.id.save_profile)?.isVisible = settingsDirty && page == 1
        menu.findItem(R.id.load_profile)?.isVisible = true
        menu.findItem(R.id.import_profile)?.isVisible = true
        menu.findItem(R.id.export_profile)?.isVisible = true
        menu.findItem(R.id.reload_defaults)?.isVisible = true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.save_profile -> saveCurrentProfile()
            R.id.load_profile -> profileLauncher.launch(Intent(this, BlankActivity::class.java).putExtra(EXTRA_KEY_TYPE, BLANK_ACTIVITY_TYPE_PROFILES))
            R.id.import_profile -> importLauncher.launch(arrayOf("application/json"))
            R.id.export_profile -> showExportDialog()
            R.id.reload_defaults -> showReloadDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun saveCurrentProfile() {
        val key = prefs.getString(EDITING_PROFILE_KEY, null)
        if (key.isNullOrBlank()) {
            showProfileNameDialog { saveProfile(PROFILE_KEY_HEADER + it) }
        } else {
            saveProfile(key)
        }
    }

    private fun saveProfile(key: String) {
        prefs.edit().putString(key, serializeProfile(prefs)).apply()
        updateDirtyState()
        homeFragment.refreshProfiles()
        if (prefs.getString(ACTIVE_PROFILE_KEY, null) == key) {
            restartVpn()
        }
        Toast.makeText(this, R.string.toast_profile_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showProfileNameDialog(onSaved: (String) -> Unit) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
        editText.hint = hostname
        editText.requestFocus()
        AlertDialog.Builder(this)
            .setView(editText)
            .setMessage(R.string.dialog_profile_name)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val name = editText.text.toString().trim().ifEmpty { hostname.trim().ifEmpty { getString(R.string.default_profile_name) } }
                onSaved(name)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun importExternalProfile(profile: Profile) {
        val activeKey = prefs.getString(ACTIVE_PROFILE_KEY, null)
        val editingKey = prefs.getString(EDITING_PROFILE_KEY, null)
            ?.takeIf { it.startsWith(PROFILE_KEY_HEADER) && prefs.contains(it) }
        val targetKey = editingKey ?: activeKey ?: run {
            val baseName = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                .trim()
                .ifEmpty { getString(R.string.default_profile_name) }
            var name = baseName
            var index = 2
            while (prefs.contains(PROFILE_KEY_HEADER + name)) {
                name = "$baseName ($index)"
                index++
            }
            PROFILE_KEY_HEADER + name
        }

        suppressPreferenceDirty = true
        importProfile(profile, prefs)
        val serialized = serializeProfile(prefs)
        prefs.edit()
            .putString(targetKey, serialized)
            .putString(EDITING_PROFILE_KEY, targetKey)
            .apply()
        suppressPreferenceDirty = false
        settingsDirty = false
        invalidateOptionsMenu()
        homeFragment.refreshProfiles()

        if (activeKey == targetKey) {
            connectVpn()
        }

        Toast.makeText(this, R.string.toast_profile_imported, Toast.LENGTH_SHORT).show()
    }

    private fun updateDirtyState() {
        val key = prefs.getString(EDITING_PROFILE_KEY, null)
        settingsDirty = if (key.isNullOrBlank() || !prefs.contains(key)) {
            true
        } else {
            prefs.getString(key, null) != serializeProfile(prefs)
        }
        invalidateOptionsMenu()
    }

    private fun showExportDialog() {
        val filename = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) + ".json"
        AlertDialog.Builder(this).setMessage(R.string.dialog_export_warning)
            .setPositiveButton(R.string.button_proceed) { _, _ -> exportLauncher.launch(filename) }
            .setNegativeButton(R.string.button_cancel, null).show()
    }

    private fun showReloadDialog() {
        AlertDialog.Builder(this).setMessage(R.string.dialog_reload_defaults)
            .setPositiveButton(R.string.button_yes) { _, _ ->
                suppressPreferenceDirty = true
                importProfile(null, prefs)
                suppressPreferenceDirty = false
                updateDirtyState()
            }
            .setNegativeButton(R.string.button_no, null).show()
    }

    private fun connectVpn() {
        val preparationIntent = VpnService.prepare(this)
        if (preparationIntent != null) {
            vpnPreparationLauncher.launch(preparationIntent)
            return
        }

        val intent = Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun restartVpn() {
        // Restart through the normal connect path so VpnService.prepare() is always checked.
        // This also avoids relying on stale ROOT_STATE after a failed/crashed session.
        connectVpn()
    }

    private fun disconnectVpn() {
        startService(Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT))
    }
}
