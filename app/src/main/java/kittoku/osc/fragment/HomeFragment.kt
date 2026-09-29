package kittoku.osc.fragment

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import kittoku.osc.R
import kittoku.osc.activity.MainActivity
import kittoku.osc.preference.ACTIVE_PROFILE_KEY
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.deserializeProfile
import androidx.preference.PreferenceManager

class HomeFragment : Fragment() {
    private lateinit var container: LinearLayout

    override fun onCreateView(inflater: android.view.LayoutInflater, parent: android.view.ViewGroup?, state: Bundle?): View {
        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        return container
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        refreshProfiles()
    }

    fun refreshProfiles() {
        if (!::container.isInitialized) return
        container.removeAllViews()
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val profiles = prefs.all.filter { it.key.startsWith(PROFILE_KEY_HEADER) && it.value is String }
        if (profiles.isEmpty()) {
            val add = TextView(requireContext()).apply {
                text = getString(R.string.add_profile)
                textSize = 20f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(24, 80, 24, 80)
                isClickable = true
                setOnClickListener { (activity as MainActivity).createNewProfile() }
            }
            container.addView(add, LinearLayout.LayoutParams(-1, -2))
            return
        }

        profiles.toList().sortedBy { it.first.substringAfter(PROFILE_KEY_HEADER).lowercase() }.forEach { entry ->
            val profileName = entry.first.substringAfter(PROFILE_KEY_HEADER)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 14, 8, 14)
            }
            val text = TextView(requireContext()).apply {
                this.text = profileName
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { (activity as MainActivity).openSettingsForProfile(entry.first) }
            }
            val toggle = SwitchCompat(requireContext()).apply {
                isChecked = prefs.getString(ACTIVE_PROFILE_KEY, null) == entry.first
                setOnCheckedChangeListener { _, checked ->
                    (activity as MainActivity).activateProfile(entry.first, checked)
                }
            }
            row.setOnClickListener { (activity as MainActivity).openSettingsForProfile(entry.first) }
            row.addView(text)
            row.addView(toggle)
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        val addButton = Button(requireContext()).apply {
            text = getString(R.string.add_profile)
            setOnClickListener { (activity as MainActivity).createNewProfile() }
        }
        container.addView(addButton, LinearLayout.LayoutParams(-1, -2))
    }
}
