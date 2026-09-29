package kittoku.osc.activity

import android.Manifest
import android.content.Intent
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.databinding.ActivityMainBinding
import kittoku.osc.extension.firstEditText
import kittoku.osc.fragment.HomeFragment
import kittoku.osc.fragment.SettingFragment
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.ACTIVE_PROFILE_KEY
import kittoku.osc.preference.EDITING_PROFILE_KEY
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.importProfile
import kittoku.osc.preference.serializeProfile
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var homeFragment: HomeFragment
    private lateinit var settingFragment: SettingFragment
    private var settingsDirty = false
    private var suppressPreferenceDirty = true
    private val handler = Handler(Looper.getMainLooper())
    private val dialogResource: Int by lazy { EditTextPreference(this).dialogLayoutResource }

    private val profileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) homeFragment.refreshProfiles()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.also {
            val profile = contentResolver.openInputStream(it)?.let { stream ->
                BufferedInputStream(stream).use { input -> deserializeProfile(input.reader(Charsets.UTF_8).readText()) }
            }
            if (profile == null) {
                Toast.makeText(this, R.string.toast_import_failed, Toast.LENGTH_SHORT).show()
            } else {
                disconnectVpn()
                suppressPreferenceDirty = true
                importProfile(profile, prefs)
                prefs.edit().remove(ACTIVE_PROFILE_KEY).remove(EDITING_PROFILE_KEY).apply()
                suppressPreferenceDirty = false
                settingsDirty = false
                invalidateOptionsMenu()
                homeFragment.refreshProfiles()
                Toast.makeText(this, R.string.toast_profile_imported, Toast.LENGTH_SHORT).show()
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
            override fun createFragment(position: Int): Fragment = if (position == 0) homeFragment else settingFragment
        }.also { binding.pager.adapter = it }

        binding.tabBar.visibility = android.view.View.GONE
        binding.pager.isUserInputEnabled = false

        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (!suppressPreferenceDirty && key != null && OscPrefKey.entries.any { it.name == key } &&
                key !in setOf(OscPrefKey.ROOT_STATE.name, OscPrefKey.HOME_STATUS.name, OscPrefKey.HOME_CONNECTOR.name)) {
                settingsDirty = true
                invalidateOptionsMenu()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        showHome()
    }

    fun openSettingsForProfile(profileKey: String) {
        val json = prefs.getString(profileKey, null)
        val profile = json?.let(::deserializeProfile) ?: return
        disconnectVpn()
        suppressPreferenceDirty = true
        importProfile(profile, prefs)
        prefs.edit().putString(EDITING_PROFILE_KEY, profileKey).remove(ACTIVE_PROFILE_KEY).apply()
        suppressPreferenceDirty = false
        settingsDirty = false
        invalidateOptionsMenu()
        findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).setCurrentItem(1, false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)
    }

    fun createNewProfile() {
        disconnectVpn()
        showProfileNameDialog {
            suppressPreferenceDirty = true
            importProfile(null, prefs)
            val key = PROFILE_KEY_HEADER + it
            prefs.edit().putString(EDITING_PROFILE_KEY, key).remove(ACTIVE_PROFILE_KEY).apply()
            suppressPreferenceDirty = false
            settingsDirty = true
            invalidateOptionsMenu()
            findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).setCurrentItem(1, false)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
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
        disconnectVpn()
        suppressPreferenceDirty = true
        importProfile(profile, prefs)
        prefs.edit().putString(ACTIVE_PROFILE_KEY, profileKey).putString(EDITING_PROFILE_KEY, profileKey).apply()
        suppressPreferenceDirty = false
        settingsDirty = false
        invalidateOptionsMenu()
        homeFragment.refreshProfiles()
        connectVpn()
    }

    fun showHome() {
        if (::prefs.isInitialized) {
            findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)?.setCurrentItem(0, false)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        title = getString(R.string.app_name)
        if (::homeFragment.isInitialized && homeFragment.isAdded) homeFragment.refreshProfiles()
    }

    override fun onSupportNavigateUp(): Boolean {
        showHome()
        return true
    }

    override fun onBackPressed() {
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
        if (pager.currentItem == 1) showHome() else super.onBackPressed()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        MenuInflater(this).inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.save_profile)?.isVisible = settingsDirty
        menu.findItem(R.id.load_profile)?.isVisible = pagerOnHome()
        menu.findItem(R.id.import_profile)?.isVisible = pagerOnHome()
        menu.findItem(R.id.export_profile)?.isVisible = pagerOnHome()
        menu.findItem(R.id.reload_defaults)?.isVisible = pagerOnHome()
        return super.onPrepareOptionsMenu(menu)
    }

    private fun pagerOnHome() = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).currentItem == 0

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
        settingsDirty = false
        invalidateOptionsMenu()
        homeFragment.refreshProfiles()
        if (prefs.getString(ACTIVE_PROFILE_KEY, null) == key) {
            disconnectVpn()
            handler.postDelayed({ connectVpn() }, 350L)
        }
        Toast.makeText(this, R.string.toast_profile_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showProfileNameDialog(onSaved: (String) -> Unit) {
        val inflated = layoutInflater.inflate(dialogResource, null)
        val editText = inflated.firstEditText()
        val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.hint = hostname
        editText.requestFocus()
        AlertDialog.Builder(this)
            .setView(inflated)
            .setMessage(R.string.dialog_profile_name)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val name = editText.text.toString().trim().ifEmpty { hostname.trim().ifEmpty { getString(R.string.default_profile_name) } }
                onSaved(name)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
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
                settingsDirty = true
                invalidateOptionsMenu()
            }
            .setNegativeButton(R.string.button_no, null).show()
    }

    private fun connectVpn() {
        val intent = Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun disconnectVpn() {
        startService(Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT))
    }
}
