package com.placeholder.myFirstApp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.placeholder.myFirstApp.MyApplication
import com.placeholder.myFirstApp.R
import com.placeholder.myFirstApp.classes.ConnectionState
import com.placeholder.myFirstApp.classes.MyAdapter
import com.placeholder.myFirstApp.classes.PlayerDevice
import com.placeholder.myFirstApp.objects.MyToast
import com.placeholder.myFirstApp.viewModels.CentralViewModel
import com.placeholder.myFirstApp.viewModels.CentralViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HostGameFragment : Fragment() {

    // ViewModel - shared with activity
    private val centralViewModel: CentralViewModel by activityViewModels()

    // Views
    private lateinit var textView: TextView
    private lateinit var button: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MyAdapter
    private val playerDevices: MutableList<PlayerDevice> = mutableListOf()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_host_players, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        textView = view.findViewById(R.id.buzzerTextView)
        recyclerView = view.findViewById(R.id.recyclerView)
        button = view.findViewById(R.id.button12)
        button.setOnClickListener { startRound() }
        
        // Set up the RecyclerView
        adapter = MyAdapter(
            devices = playerDevices,
            onConnectClick = { device -> connectToDevice(device) },
            onRemoveClick = { device -> removeDevice(device) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Observe ViewModel
        observeViewModel()
    }

    /**
     * Observes the CentralViewModel's StateFlow for changes in data
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // connection state events
                launch {
                    centralViewModel.connectionStatus.collectLatest {
                        when (it) {
                            ConnectionState.SCANNING -> textView.text = "scanning for players"
                            ConnectionState.CONNECTED -> textView.text = "connected to player"
                            ConnectionState.DISCONNECTED -> textView.text = "disconnected"
                            else -> textView.text = "connection status error"
                        }
                    }
                }

                // available players
                launch {
                    centralViewModel.availablePlayers.collectLatest {
                        playerDevices.clear()
                        playerDevices.addAll(it)
                        adapter.notifyDataSetChanged()
                    }
                }

                // device connection updates
                launch {
                    centralViewModel.deviceConnectionUpdate.collectLatest { update ->
                        update?.let {
                            adapter.updateDeviceConnectionStatus(it.address, it.isConnected)
                            adapter.updateDeviceStatus(it.address, it.status)
                        }
                    }
                }
            }
        }
    }

    /**
     * Connects to the given device
     */
    private fun connectToDevice(device: PlayerDevice) = centralViewModel.connectToDevice(device)

    /**
     * Disconnects from the given device if it's connected and removes it from the list if it's not
     */
    private fun removeDevice(device: PlayerDevice) = centralViewModel.disconnectFromDevice(device)

    /**
     * Starts a new game round if at least one player is connected
     */
    private fun startRound() =
        if (playerDevices.none { it.isConnected }) MyToast.showText(requireContext(), "No players connected")
        else centralViewModel.startRound()

}
