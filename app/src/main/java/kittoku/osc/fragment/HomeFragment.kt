package kittoku.osc.fragment

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import kittoku.osc.R
import kittoku.osc.activity.MainActivity
import kittoku.osc.preference.ACTIVE_PROFILE_KEY
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER

class HomeFragment : Fragment() {
    private lateinit var container: LinearLayout
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            if (isAdded && ::container.isInitialized) {
                refreshProfiles()
                statusHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup?,
        state: Bundle?
    ): View {
        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        return container
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        refreshProfiles()
        statusHandler.postDelayed(statusUpdater, 1000L)
    }

    override fun onDestroyView() {
        statusHandler.removeCallbacks(statusUpdater)
        super.onDestroyView()
    }

    fun refreshProfiles() {
        if (!::container.isInitialized) return
        container.removeAllViews()
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val profiles = prefs.all.filter {
            it.key.startsWith(PROFILE_KEY_HEADER) && it.value is String
        }
        val activeKey = prefs.getString(ACTIVE_PROFILE_KEY, null)
        val status = prefs.getString(OscPrefKey.HOME_STATUS.name, "").orEmpty()

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

        profiles.toList()
            .sortedBy { it.first.substringAfter(PROFILE_KEY_HEADER).lowercase() }
            .forEach { entry ->
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
                    setOnClickListener {
                        (activity as MainActivity).openProfileForProfile(entry.first)
                    }
                }

                val toggle = SwitchCompat(requireContext()).apply {
                    isChecked = activeKey == entry.first
                    setOnCheckedChangeListener { _, checked ->
                        val hostActivity = activity as? MainActivity ?: return@setOnCheckedChangeListener
                        hostActivity.activateProfile(entry.first, checked)
                    }
                }

                row.setOnClickListener {
                    (activity as MainActivity).openProfileForProfile(entry.first)
                }
                row.addView(text)
                row.addView(toggle)
                container.addView(row, LinearLayout.LayoutParams(-1, -2))

                if (activeKey == entry.first && status.isNotBlank()) {
                    val statsTitle = TextView(requireContext()).apply {
                        text = getString(R.string.connection_statistics)
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(16, 4, 16, 2)
                    }
                    val stats = TextView(requireContext()).apply {
                        text = status
                        textSize = 12f
                        setPadding(16, 0, 16, 14)
                        setTextIsSelectable(true)
                    }
                    container.addView(statsTitle, LinearLayout.LayoutParams(-1, -2))
                    container.addView(stats, LinearLayout.LayoutParams(-1, -2))
                }
            }

        val addButton = Button(requireContext()).apply {
            text = getString(R.string.add_profile)
            setOnClickListener { (activity as MainActivity).createNewProfile() }
        }
        container.addView(addButton, LinearLayout.LayoutParams(-1, -2))
    }
}
