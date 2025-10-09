package com.placeholder.myFirstApp.fragments

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.placeholder.myFirstApp.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        handleTheme()
        handleAbout()
    }

    //handle theme changes
    private fun handleTheme() {
        val themePref = findPreference<ListPreference>("appTheme")
        themePref?.setOnPreferenceChangeListener { _, newValue ->
            val theme = (newValue as String).toIntOrNull() ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppCompatDelegate.setDefaultNightMode(theme)
            requireActivity().recreate()
            true
        }
    }

    private fun handleAbout() {
        val aboutPref = findPreference<Preference>("aboutSection")
        aboutPref?.setOnPreferenceClickListener {
            val context = requireContext()
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                "Unknown"
            }
            AlertDialog.Builder(context)
                .setTitle("About")
                .setMessage("Buzzer Trivia App\nVersion: $version\n\nCreated by:\nKonstantinos Panagiotopoulos")
                .setPositiveButton("OK", null)
                .show()
            true
        }
    }
}