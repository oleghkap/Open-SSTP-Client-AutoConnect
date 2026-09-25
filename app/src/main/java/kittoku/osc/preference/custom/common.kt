package kittoku.osc.preference.custom

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kittoku.osc.preference.DEFAULT_INT_MAP
import kittoku.osc.preference.DEFAULT_STRING_MAP
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue


internal interface OscPreference {
    val oscPrefKey: OscPrefKey
    val parentKey: OscPrefKey?
    val preferenceTitle: String
    fun updateView()
}



private fun localizedPreferenceTitle(context: Context, title: String): String = when (title) {
    "Current Status" -> context.getString(R.string.pref_current_status)
    "Select Allowed/Disallowed Apps" -> context.getString(R.string.pref_select_apps)
    "Hostname" -> context.getString(R.string.pref_hostname)
    "Username" -> context.getString(R.string.pref_username)
    "Custom SNI Hostname" -> context.getString(R.string.pref_custom_sni)
    "Static IPv4 Address" -> context.getString(R.string.pref_static_ipv4)
    "Proxy Server Hostname" -> context.getString(R.string.pref_proxy_hostname)
    "Proxy Username (optional)" -> context.getString(R.string.pref_proxy_username)
    "Custom DNS Server Address" -> context.getString(R.string.pref_dns_address)
    "Edit Custom Routes" -> context.getString(R.string.pref_custom_routes)
    "Port Number" -> context.getString(R.string.pref_port)
    "Proxy Server Port Number" -> context.getString(R.string.pref_proxy_port)
    "MRU" -> context.getString(R.string.pref_mru)
    "MTU" -> context.getString(R.string.pref_mtu)
    "Timeout Period (second)" -> context.getString(R.string.pref_timeout)
    "Retry Count" -> context.getString(R.string.pref_retry_count)
    "Retry Interval (second)" -> context.getString(R.string.pref_retry_interval)
    "SSL Version" -> context.getString(R.string.pref_ssl_version)
    "List Type" -> context.getString(R.string.pref_list_type)
    "Select Cipher Suites" -> context.getString(R.string.pref_cipher_suites)
    "Select Authentication Protocols" -> context.getString(R.string.pref_auth_protocols)
    "Specify Trusted Certificates" -> context.getString(R.string.pref_trusted_certificates)
    "Enable Only Selected Cipher Suites" -> context.getString(R.string.pref_selected_cipher)
    "Use Custom SNI" -> context.getString(R.string.pref_custom_sni_switch)
    "Request Static IPv4 Address" -> context.getString(R.string.pref_static_ipv4_switch)
    "Use HTTP Proxy" -> context.getString(R.string.pref_http_proxy)
    "Request DNS Server Address" -> context.getString(R.string.pref_dns_request)
    "Use Custom DNS Server" -> context.getString(R.string.pref_custom_dns)
    "Add Custom Routes" -> context.getString(R.string.pref_custom_routes_switch)
    "Enable App-Based Rule" -> context.getString(R.string.pref_app_rule)
    "Show Background Apps" -> context.getString(R.string.pref_background_apps)
    "Enable Reconnection" -> context.getString(R.string.pref_reconnection)
    "Save Log" -> context.getString(R.string.pref_save_log)
    "Verify Hostname" -> context.getString(R.string.pref_verify_hostname)
    "Enable IPv4" -> context.getString(R.string.pref_ipv4)
    "Enable IPv6" -> context.getString(R.string.pref_ipv6)
    "Add Default Route" -> context.getString(R.string.pref_default_route)
    "Route Private/Unique-Local Addresses" -> context.getString(R.string.pref_private_addresses)
    "Move to this app's project page" -> context.getString(R.string.pref_project_page)
    "Password" -> context.getString(R.string.pref_password)
    "Proxy Password (optional)" -> context.getString(R.string.pref_proxy_password)
    "Select Trusted Certificates" -> context.getString(R.string.pref_ssl_certs)
    "Select Log Directory" -> context.getString(R.string.pref_log_dir)
    else -> title
}

internal fun <T> T.initialize() where T : Preference, T : OscPreference {
    title = localizedPreferenceTitle(context, preferenceTitle)
    isSingleLineTitle = false

    parentKey?.also { dependency = it.name }

    updateView()
}

internal abstract class OscEditTextPreference(context: Context, attrs: AttributeSet) : EditTextPreference(context, attrs), OscPreference {
    protected open val inputType = InputType.TYPE_CLASS_TEXT
    protected open val hint: String? = null
    protected open val provider: SummaryProvider<out Preference> = SimpleSummaryProvider.getInstance()

    override fun updateView() {
        text = when (oscPrefKey) {
            in DEFAULT_INT_MAP.keys -> getIntPrefValue(oscPrefKey, sharedPreferences!!).toString()
            in DEFAULT_STRING_MAP -> getStringPrefValue(oscPrefKey, sharedPreferences!!)
            else -> throw NotImplementedError(oscPrefKey.name)
        }
    }

    override fun onAttached() {
        setOnBindEditTextListener {
            it.inputType = inputType
            it.hint = hint
            it.setSelection(it.text.length)
        }

        summaryProvider = provider

        initialize()
    }
}
