package com.KonstPan.buzzerTrivia.activities

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.KonstPan.buzzerTrivia.R
import com.KonstPan.buzzerTrivia.fragments.SettingsFragment

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        //use the settings fragment on the activity
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragmentContainerView, SettingsFragment())
            .commit()
    }

    //close the activity
    fun closeActivity(view: View) = finish()
}