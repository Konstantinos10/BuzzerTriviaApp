package com.KonstPan.buzzerTrivia.activities

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Base Activity class for the app which handles boilerplate logic. Every Activity in the app should extend this class.
*/
abstract class BaseActivity : AppCompatActivity() {
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {

        // Set the orientation to portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

        // Load and apply theme before calling super.onCreate
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val themePref = preferences.getString("appTheme", "-1")
        val themeMode = themePref?.toIntOrNull() ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppCompatDelegate.setDefaultNightMode(themeMode)
        // Call super.onCreate
        super.onCreate(savedInstanceState)
    }
}