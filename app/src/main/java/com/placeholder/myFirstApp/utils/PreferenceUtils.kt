package com.placeholder.myFirstApp.utils

import android.content.Context
import androidx.preference.PreferenceManager

object PreferenceUtils {
    fun isSoundEffectsEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("soundEffects", false)
    }

    fun getPreferredGameMode(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("gameMode", "none") ?: "none"
    }
}
