package com.placeholder.myFirstApp.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.placeholder.myFirstApp.R
import com.placeholder.myFirstApp.objects.MyToast

class MainActivity : BaseActivity() {

    // Bluetooth permission variables
    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    private var permissionCallback: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissionCallback == null) error("permissionCaller is null")

        // check if permissions are granted and notify the user accordingly
        if (permissions.all { it.value }) {
            MyToast.showText(this, "Bluetooth permissions granted")
            permissionCallback?.invoke()
            permissionCallback = null
        }
        else MyToast.showText(this, "Bluetooth permissions are required to use this app")
    }

    // onCreate function
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    // Starts an activity
    private fun beginActivity(cls: Class<*>) {
        val intent = Intent(this, cls)
        startActivity(intent)
    }

    // Starts a bluetooth Activity if permissions are granted and requests them if not
    private fun checkBluetoothPermissions(code: () -> Unit) =
        if (bluetoothPermissions.all { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED })
            code()
        else {
            permissionCallback = code
            permissionLauncher.launch(bluetoothPermissions)
        }


    // functions to start each activity
    fun openHostGameActivity(view: View) = checkBluetoothPermissions { beginActivity(HostGameActivity::class.java) }
    fun openClientGameActivity(view: View) = checkBluetoothPermissions { beginActivity(PlayerGameActivity::class.java) }
    fun openSettingsActivity(view: View) = beginActivity(SettingsActivity::class.java)
    fun openRulesActivity(view: View) = beginActivity(HelpActivity::class.java)

    // Closes the app
    fun exitApp(view: View) = finishAndRemoveTask()
}