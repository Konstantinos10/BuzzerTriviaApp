package com.KonstPan.buzzerTrivia.activities

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.KonstPan.buzzerTrivia.objects.MyToast

@SuppressLint("MissingPermission")
abstract class BaseBluetoothActivity : BaseActivity() {
    // Bluetooth adapter
    protected lateinit var bluetoothAdapter: BluetoothAdapter
    private var callback: (() -> Unit)? = null
    private var shouldKeepTrying = false
    private val handler = Handler(Looper.getMainLooper())

    // Bluetooth launcher
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult())
    { result ->
        if (result.resultCode == RESULT_OK) {
            shouldKeepTrying = false
            callback?.invoke()
            callback = null
        }
        else {
            MyToast.showText(this, "Bluetooth must be on\nto use this feature")
            if (shouldKeepTrying)
                handler.postDelayed({
                    if (shouldKeepTrying && !bluetoothAdapter.isEnabled) launchBtIntent()
                }, 5000)// Wait 5 seconds before asking again
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        if (bluetoothManager.adapter == null) {
            MyToast.showText(this, "Bluetooth is required to use this feature\nbut your device does not support it", 1)
            finish()
            return
        }
        bluetoothAdapter = bluetoothManager.adapter
    }

    private fun launchBtIntent(){
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    /**
     * Tries to activate bluetooth and sets the given callback to be executed after it's turned on
     */
    fun activateBluetooth(callback: (() -> Unit)? = null) =
        if (!bluetoothAdapter.isEnabled) {
            this.callback = callback
            launchBtIntent()
        }
        else MyToast.showText(this, "Bluetooth is already on")

    /**
     * Tries to activate bluetooth repeatedly until successful with 1 second delay between attempts
     */
    fun activateBluetoothPersistent(callback: (() -> Unit)? = null) {
        if (!bluetoothAdapter.isEnabled) {
            shouldKeepTrying = true
            this.callback = callback
            launchBtIntent()
        }
        else MyToast.showText(this, "Bluetooth is already on")
    }

    /**
     * Stops the persistent bluetooth activation requests
     */
    fun stopBluetoothRequests() {
        shouldKeepTrying = false
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Checks if bluetooth is on before executing the given code, if not it activates it persistently
     */
    fun bluetoothCheck(persist: Boolean = false, code: (() -> Unit)? = null) =
        if (bluetoothAdapter.isEnabled) code?.invoke()
        else if (persist) activateBluetoothPersistent(code)
        else activateBluetooth(code)

    // Close the activity
    fun closeActivity(view: View) = finish()

    override fun onDestroy() {
        super.onDestroy()
        stopBluetoothRequests()
    }
}