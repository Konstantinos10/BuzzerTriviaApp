package com.placeholder.myFirstApp.activities

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.placeholder.myFirstApp.MyApplication
import com.placeholder.myFirstApp.viewModels.PeripheralViewModel
import com.placeholder.myFirstApp.viewModels.PeripheralViewModelFactory
import com.placeholder.myFirstApp.R
import com.placeholder.myFirstApp.classes.ConnectionState
import com.placeholder.myFirstApp.fragments.PlayerConnectedFragment
import com.placeholder.myFirstApp.fragments.PlayerDisconnectedFragment
import com.placeholder.myFirstApp.objects.MyToast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.getValue


class PlayerGameActivity : BaseBluetoothActivity() {

    // ViewModel
    private val peripheralViewModel: PeripheralViewModel by viewModels {
        PeripheralViewModelFactory((application as MyApplication).bluetoothService)
    }

    // Fragments
    private val disconnectedFragment = PlayerDisconnectedFragment()
    private val connectedFragment = PlayerConnectedFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_player_game)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize with disconnected fragment
        showDisconnectedFragment()

        // Observe ViewModel
        observeViewModel()
    }

    /**
     * Shows the disconnected fragment (scan for host button)
     */
    private fun showDisconnectedFragment() = switchFragment(disconnectedFragment)

    /**
     * Shows the connected fragment (buzz and disconnect buttons)
     */
    private fun showConnectedFragment() = switchFragment(connectedFragment)

    /**
     * Switches between fragments
     */
    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /**
     * Observes the PeripheralViewModel's StateFlow for changes in data
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // connection state events
                launch {
                    peripheralViewModel.connectionStatus.collectLatest {
                        when (it) {
                            ConnectionState.ADVERTISING -> {
                                disconnectedFragment.setText("waiting for host")
                                showDisconnectedFragment()
                            }
                            ConnectionState.CONNECTED_TO_HOST -> {
                                showConnectedFragment()
                            }
                            ConnectionState.DISCONNECTED -> {
                                disconnectedFragment.setText("disconnected\ninitializing")
                                showDisconnectedFragment()
                            }
                            ConnectionState.DISCONNECTED_BT_OFF -> {
                                disconnectedFragment.setText("please enable\nbluetooth")
                                showDisconnectedFragment()
                                bluetoothCheck(true) // try to turn on bluetooth if it's closed
                            }
                            else -> {
                                disconnectedFragment.setText("connection status error")
                                showDisconnectedFragment()
                            }
                        }
                    }
                }

                // error messages
                launch {
                    peripheralViewModel.errorMessage.collectLatest {
                        it?.let {
                            MyToast.showText(this@PlayerGameActivity, it)
                            peripheralViewModel.clearErrorMessage()
                        }
                    }
                }
            }
        }
    }

    /**
     * Kills connection to the host, update to gracefully disconnect when possible
     */
    fun disconnect(view: View) = peripheralViewModel.disconnect()

    /**
     * Send a buzz signal to the host
     */
    fun buzzHost(view: View) = peripheralViewModel.buzzHost()

    override fun onDestroy() {
        super.onDestroy()
        stopBluetoothRequests()
    }
}