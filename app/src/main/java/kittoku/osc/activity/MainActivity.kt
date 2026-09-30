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

internal const val EXTRA_PROFILE_KEY = "kittoku.osc.extra.PROFILE_KEY"

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var homeFragment: HomeFragment
    private lateinit var settingFragment: SettingFragment

    private var settingsDirty = false
    private var suppressPreferenceDirty = true
    private val handler = Handler(Looper.getMainLooper())

    private val profileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val profileKey = it.data?.getStringExtra(EXTRA_PROFILE_KEY)
                if (!profileKey.isNullOrBlank()) {
                    openProfileForProfile(profileKey)
                } else {
                    homeFragment.refreshProfiles()
                }
            }
        }

    private val vpnPreparationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService(ACTION_VPN_CONNECT)
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.also {
                val profile = contentResolver.openInputStream(it)?.let { stream ->
                    BufferedInputStream(stream).use { input ->
                        deserializeProfile(input.reader(Charsets.UTF_8).readText())
                    }
                }

                if (profile == null) {
                    Toast.makeText(this, R.string.toast_import_failed, Toast.LENGTH_SHORT).show()
                } else {
                    importExternalProfile(profile)
                }
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.also {
                contentResolver.openOutputStream(it)?.use { stream ->
                    BufferedOutputStream(stream).use { out ->
                        out.write(serializeProfile(prefs).toByteArray(Charsets.UTF_8))
                    }
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
        }.also {
            binding.pager.adapter = it
        }

        TabLayoutMediator(binding.tabBar, binding.pager) { tab, position ->
            tab.text = if (position == 0) {
                getString(R.string.tab_home)
            } else {
                getString(R.string.tab_settings)
            }
        }.attach()

        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (
                !suppressPreferenceDirty &&
                key != null &&
                OscPrefKey.entries.any { it.name == key } &&
                key !in setOf(
                    OscPrefKey.ROOT_STATE.name,
                    OscPrefKey.HOME_STATUS.name,
                    OscPrefKey.HOME_CONNECTOR.name
                )
            ) {
                settingsDirty = true
                invalidateOptionsMenu()
            }
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        showHome()
        suppressPreferenceDirty = false
        updateDirtyState()
    }

    fun openProfileForProfile(profileKey: String) {
        val json = prefs.getString(profileKey, null)
        val profile = json?.let(::deserializeProfile) ?: return

        suppressPreferenceDirty = true
        importProfile(profile, prefs)
        prefs.edit().putString(EDITING_PROFILE_KEY, profileKey).apply()
        suppressPreferenceDirty = false

        settingsDirty = false
        invalidateOptionsMenu()

        findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
            .setCurrentItem(1, false)

        handler.post {
            if (!isFinishing && settingFragment.isAdded) {
                settingFragment.refreshFromCurrentProfile()
            }
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.profile_title)
        invalidateOptionsMenu()
    }

    fun createNewProfile() {
        showProfileNameDialog { name ->
            val key = PROFILE_KEY_HEADER + name
            if (prefs.contains(key)) {
                Toast.makeText(
                    this,
                    R.string.toast_profile_name_exists,
                    Toast.LENGTH_SHORT
                ).show()
                return@showProfileNameDialog
            }

            suppressPreferenceDirty = true
            importProfile(null, prefs)
            prefs.edit().putString(EDITING_PROFILE_KEY, key).apply()
            suppressPreferenceDirty = false

            settingsDirty = true
            invalidateOptionsMenu()

            findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
                .setCurrentItem(1, false)

            handler.post {
                if (!isFinishing && settingFragment.isAdded) {
                    settingFragment.refreshFromCurrentProfile()
                }
            }

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

        val profile = prefs.getString(profileKey, null)?.let(::deserializeProfile)
            ?: return

        suppressPreferenceDirty = true
        importProfile(profile, prefs)
        prefs.edit()
            .putString(ACTIVE_PROFILE_KEY, profileKey)
            .putString(EDITING_PROFILE_KEY, profileKey)
            .apply()
        suppressPreferenceDirty = false

        settingsDirty = false
        invalidateOptionsMenu()

        handler.post {
            if (!isFinishing && homeFragment.isAdded) {
                homeFragment.refreshProfiles()
            }
        }

        connectVpn()
    }

    fun showHome() {
        if (::prefs.isInitialized) {
            findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
                ?.setCurrentItem(0, false)
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        title = getString(R.string.app_name)

        if (::homeFragment.isInitialized && homeFragment.isAdded) {
            homeFragment.refreshProfiles()
        }

        invalidateOptionsMenu()
    }

    override fun onSupportNavigateUp(): Boolean {
        showHome()
        return true
    }

    override fun onBackPressed() {
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
        if (pager.currentItem != 0) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        MenuInflater(this).inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val page = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).currentItem

        if (page == 1) {
            settingsDirty = calculateDirtyState()
        }

        menu?.findItem(R.id.save_profile)?.isVisible =
            page == 1 && settingsDirty && hasEditingProfile()

        menu?.findItem(R.id.load_profile)?.isVisible = true
        menu?.findItem(R.id.import_profile)?.isVisible = true
        menu?.findItem(R.id.export_profile)?.isVisible = true
        menu?.findItem(R.id.reload_defaults)?.isVisible = true

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.save_profile -> saveCurrentProfile()
            R.id.load_profile -> {
                profileLauncher.launch(
                    Intent(this, BlankActivity::class.java).putExtra(
                        EXTRA_KEY_TYPE,
                        BLANK_ACTIVITY_TYPE_PROFILES
                    )
                )
            }
            R.id.import_profile -> importLauncher.launch(arrayOf("application/json"))
            R.id.export_profile -> showExportDialog()
            R.id.reload_defaults -> showReloadDialog()
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    private fun hasEditingProfile(): Boolean {
        val key = prefs.getString(EDITING_PROFILE_KEY, null)
        return !key.isNullOrBlank()
    }

    private fun saveCurrentProfile() {
        val key = prefs.getString(EDITING_PROFILE_KEY, null)

        if (key.isNullOrBlank()) {
            showProfileNameDialog { name ->
                val targetKey = PROFILE_KEY_HEADER + name
                if (prefs.contains(targetKey)) {
                    Toast.makeText(
                        this,
                        R.string.toast_profile_name_exists,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    saveProfile(targetKey)
                }
            }
        } else {
            saveProfile(key)
        }
    }

    private fun saveProfile(key: String) {
        val serialized = serializeProfile(prefs)

        prefs.edit()
            .putString(key, serialized)
            .putString(EDITING_PROFILE_KEY, key)
            .apply()

        settingsDirty = false
        invalidateOptionsMenu()

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
                val name = editText.text
                    .toString()
                    .trim()
                    .ifEmpty {
                        hostname.trim().ifEmpty {
                            getString(R.string.default_profile_name)
                        }
                    }

                onSaved(name)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    fun showProfileActions(profileKey: String): Boolean {
        if (!prefs.contains(profileKey)) return true

        val name = profileKey.substringAfter(PROFILE_KEY_HEADER)

        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(
                arrayOf(
                    getString(R.string.profile_rename),
                    getString(R.string.button_delete)
                )
            ) { _, which ->
                if (which == 0) {
                    renameProfile(profileKey)
                } else {
                    confirmDeleteProfile(profileKey)
                }
            }
            .show()

        return true
    }

    private fun renameProfile(profileKey: String) {
        if (!prefs.contains(profileKey)) return

        val oldName = profileKey.substringAfter(PROFILE_KEY_HEADER)

        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(oldName)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_rename)
            .setView(editText)
            .setPositiveButton(R.string.button_save) { _, _ ->
                val newName = editText.text.toString().trim()

                if (newName.isEmpty() || newName == oldName) {
                    return@setPositiveButton
                }

                val newKey = PROFILE_KEY_HEADER + newName

                if (prefs.contains(newKey)) {
                    Toast.makeText(
                        this,
                        R.string.toast_profile_name_exists,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putString(newKey, prefs.getString(profileKey, null))
                    .remove(profileKey)
                    .apply {
                        if (prefs.getString(ACTIVE_PROFILE_KEY, null) == profileKey) {
                            putString(ACTIVE_PROFILE_KEY, newKey)
                        }
                        if (prefs.getString(EDITING_PROFILE_KEY, null) == profileKey) {
                            putString(EDITING_PROFILE_KEY, newKey)
                        }
                    }
                    .apply()

                homeFragment.refreshProfiles()
                updateDirtyState()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun confirmDeleteProfile(profileKey: String) {
        val name = profileKey.substringAfter(PROFILE_KEY_HEADER)

        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dialog_delete_profile, name))
            .setPositiveButton(R.string.button_delete) { _, _ ->
                deleteProfile(profileKey)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    fun deleteProfile(profileKey: String) {
        if (!prefs.contains(profileKey)) return

        val active = prefs.getString(ACTIVE_PROFILE_KEY, null) == profileKey
        val editing = prefs.getString(EDITING_PROFILE_KEY, null) == profileKey

        prefs.edit().apply {
            remove(profileKey)
            if (active) remove(ACTIVE_PROFILE_KEY)
            if (editing) remove(EDITING_PROFILE_KEY)
            apply()
        }

        if (active) {
            disconnectVpn()
        }

        if (editing) {
            val newActiveKey = prefs.getString(ACTIVE_PROFILE_KEY, null)
            val activeProfile = newActiveKey
                ?.let { prefs.getString(it, null) }
                ?.let(::deserializeProfile)

            suppressPreferenceDirty = true
            importProfile(activeProfile, prefs)
            suppressPreferenceDirty = false

            settingsDirty = false
            invalidateOptionsMenu()
        }

        homeFragment.refreshProfiles()

        if (
            editing &&
            findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).currentItem == 1
        ) {
            showHome()
        }

        Toast.makeText(this, R.string.toast_profile_deleted, Toast.LENGTH_SHORT).show()
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

        prefs.edit()
            .putString(targetKey, serializeProfile(prefs))
            .putString(EDITING_PROFILE_KEY, targetKey)
            .apply()

        suppressPreferenceDirty = false

        openProfileForProfile(targetKey)

        if (activeKey == targetKey) {
            restartVpn()
        }

        Toast.makeText(this, R.string.toast_profile_imported, Toast.LENGTH_SHORT).show()
    }

    private fun calculateDirtyState(): Boolean {
        val key = prefs.getString(EDITING_PROFILE_KEY, null) ?: return false

        if (key.isBlank()) return false

        val current = serializeProfile(prefs)
        val saved = prefs.getString(key, null)

        return saved == null || saved != current
    }

    private fun updateDirtyState() {
        settingsDirty = calculateDirtyState()
        invalidateOptionsMenu()
    }

    private fun showExportDialog() {
        val filename = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) + ".json"

        AlertDialog.Builder(this)
            .setMessage(R.string.dialog_export_warning)
            .setPositiveButton(R.string.button_proceed) { _, _ ->
                exportLauncher.launch(filename)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun showReloadDialog() {
        AlertDialog.Builder(this)
            .setMessage(R.string.dialog_reload_defaults)
            .setPositiveButton(R.string.button_yes) { _, _ ->
                val key = prefs.getString(EDITING_PROFILE_KEY, null)

                if (key.isNullOrBlank() || !prefs.contains(key)) {
                    return@setPositiveButton
                }

                suppressPreferenceDirty = true
                importProfile(null, prefs)
                suppressPreferenceDirty = false

                settingsDirty = true
                invalidateOptionsMenu()

                handler.post {
                    if (!isFinishing && settingFragment.isAdded) {
                        settingFragment.refreshFromCurrentProfile()
                    }
                }
            }
            .setNegativeButton(R.string.button_no, null)
            .show()
    }

    private fun connectVpn() {
        val preparationIntent = VpnService.prepare(this)

        if (preparationIntent != null) {
            vpnPreparationLauncher.launch(preparationIntent)
            return
        }

        startVpnService(ACTION_VPN_CONNECT)
    }

    private fun restartVpn() {
        startVpnService(ACTION_VPN_RESTART)
    }

    private fun startVpnService(action: String) {
        val intent = Intent(this, SstpVpnService::class.java).setAction(action)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun disconnectVpn() {
        startService(
            Intent(this, SstpVpnService::class.java)
                .setAction(ACTION_VPN_DISCONNECT)
        )
    }
}
