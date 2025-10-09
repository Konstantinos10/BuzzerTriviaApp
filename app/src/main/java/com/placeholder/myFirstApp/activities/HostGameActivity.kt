package com.placeholder.myFirstApp.activities

import android.bluetooth.BluetoothAdapter
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.placeholder.myFirstApp.MyApplication
import com.placeholder.myFirstApp.R
import com.placeholder.myFirstApp.classes.GameState
import com.placeholder.myFirstApp.fragments.HostBluetoothOffFragment
import com.placeholder.myFirstApp.fragments.HostGameFragment
import com.placeholder.myFirstApp.fragments.HostRoundFragment
import com.placeholder.myFirstApp.objects.MyToast
import com.placeholder.myFirstApp.utils.PreferenceUtils
import com.placeholder.myFirstApp.viewModels.CentralViewModel
import com.placeholder.myFirstApp.viewModels.CentralViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HostGameActivity : BaseBluetoothActivity() {

    // ViewModel - shared with fragments
    private val centralViewModel: CentralViewModel by viewModels {
        val app = application as MyApplication
        CentralViewModelFactory(app.bluetoothService, app.questionRepository)
    }

    // Fragments
    private val hostGameFragment = HostGameFragment()
    private val bluetoothOffFragment = HostBluetoothOffFragment()
    private val hostRoundFragment = HostRoundFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_host_game)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize with the bluetoothOff fragment to let it initialize
        showBluetoothOffFragment()

        // Observe ViewModel
        observeViewModel()

        // set gameMode on the viewModel
        centralViewModel.createGame(PreferenceUtils.getPreferredGameMode(this))
    }

    /**
     * Shows the host game fragment (scan for players and RecyclerView)
     */
    private fun showHostGameFragment() = switchFragment(hostGameFragment)

    private fun showHostRoundFragment() = switchFragment(hostRoundFragment)

    /**
     * Shows the Bluetooth off fragment (message to turn on Bluetooth)
     */
    private fun showBluetoothOffFragment() = switchFragment(bluetoothOffFragment)

    /**
     * Switches between fragments
     */
    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /**
     * Observes the CentralViewModel's StateFlow for changes in data
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // bluetooth state changes
                launch {
                    centralViewModel.bluetoothState.collect {
                        when (it) {
                            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                                bluetoothOffFragment.setText("Please enable\nBluetooth to continue")
                                showBluetoothOffFragment()
                                bluetoothCheck(true) // try to turn on bluetooth if it's closed
                            }
                            BluetoothAdapter.STATE_ON -> {
                                if (centralViewModel.gameState.value == GameState.WAITING_FOR_BUZZ) showHostRoundFragment()
                                else showHostGameFragment()
                            }
                            BluetoothAdapter.STATE_TURNING_ON -> {
                                bluetoothOffFragment.setText("Bluetooth is\nturning on...")
                                showBluetoothOffFragment()
                            }
                            else -> {
                                bluetoothOffFragment.setText("Bluetooth error")
                                showBluetoothOffFragment()
                            }
                        }
                    }
                }

                launch {
                    centralViewModel.gameState.collect {
                        if (centralViewModel.bluetoothState.value == BluetoothAdapter.STATE_ON)
                            when (it) {
                                GameState.WAITING_FOR_ROUND -> showHostGameFragment()
                                GameState.WAITING_FOR_BUZZ -> showHostRoundFragment()
                                else -> {}
                            }
                    }
                }

                // error messages
                launch {
                    centralViewModel.errorMessage.collectLatest {
                        it?.let {
                            MyToast.showText(this@HostGameActivity, it)
                            centralViewModel.clearErrorMessage()
                        }
                    }
                }
            }
        }
    }
}