package com.placeholder.myFirstApp.viewModels

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.placeholder.myFirstApp.classes.AdvertisingStarted
import com.placeholder.myFirstApp.classes.AdvertisingStopped
import com.placeholder.myFirstApp.classes.BluetoothEvent
import com.placeholder.myFirstApp.classes.BluetoothService
import com.placeholder.myFirstApp.classes.BluetoothStateChangedToOff
import com.placeholder.myFirstApp.classes.BluetoothStateChangedToOn
import com.placeholder.myFirstApp.classes.BluetoothStateChangedToTurningOff
import com.placeholder.myFirstApp.classes.BuzzSentSuccessfully
import com.placeholder.myFirstApp.classes.ConnectionState
import com.placeholder.myFirstApp.classes.HostConnected
import com.placeholder.myFirstApp.classes.HostDisconnected
import com.placeholder.myFirstApp.classes.Question
import com.placeholder.myFirstApp.classes.QuestionReceived
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class PeripheralViewModel(private val bluetoothService: BluetoothService) : BluetoothViewModel(bluetoothService) {

    private val _connectionStatus = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    private var host: BluetoothDevice? = null

    private var questionJob: kotlinx.coroutines.Job? = null

    data class QuestionData(val text: String, val isEnabled: Boolean)

    private val _question = MutableStateFlow<QuestionData?>(null)
    val question = _question.asStateFlow()

    init {
        if (bluetoothService.currentBluetoothState != BluetoothAdapter.STATE_ON) _connectionStatus.value = ConnectionState.DISCONNECTED_BT_OFF
        else // Try to start advertising after the activity starts
            viewModelScope.launch {
                delay(1000) //wait 1 second in case bluetoothService is cleaning up
                startAdvertising()
            }
    }

    override fun collectBluetoothEvents(event: BluetoothEvent) {
        when (event) {
            // advertising events
            is AdvertisingStarted -> if (host == null) _connectionStatus.value = ConnectionState.ADVERTISING
            is AdvertisingStopped -> if (host == null) _connectionStatus.value =
                ConnectionState.DISCONNECTED else _connectionStatus.value = ConnectionState.CONNECTED_TO_HOST
            is BluetoothStateChangedToOff -> handleBluetoothOff()
            is BluetoothStateChangedToTurningOff -> handleBluetoothOff()
            is BluetoothStateChangedToOn -> {
                _connectionStatus.value = ConnectionState.DISCONNECTED
                startAdvertising()
            }

            // connection events
            is HostConnected -> handleHostConnection(event.device)
            is HostDisconnected -> handleHostDisconnection()
            
            // game events
            is BuzzSentSuccessfully -> {}
            is QuestionReceived -> handleQuestionReceived(event.question)
            else -> {}
        }
    }


    // --- Front-end actions ---
    /**
     * Starts advertising for hosts
     */
    fun startAdvertising() = bluetoothService.startAdvertising()

    /**
     * Stops advertising for hosts
     */
    fun stopAdvertising() = bluetoothService.stopAdvertising()

    /**
     * Sends a buzz to the host
     */

    fun buzzHost() = bluetoothService.sendBuzz(_question.value?.text)

    /**
     * Send a disconnect request to the host
     */
    fun disconnect() = bluetoothService.disconnectFromHost()


    // --- Device connection handling ---

    private fun handleHostConnection(device: BluetoothDevice) {
        host = device
        _connectionStatus.value = ConnectionState.CONNECTED_TO_HOST
    }

    private fun handleHostDisconnection() {
        host = null
        _connectionStatus.value = ConnectionState.DISCONNECTED
        questionJob?.cancel()
        _question.value = null

        // if disconnection happened due to Bt turning off don't retry advertising
        if(bluetoothService.currentBluetoothState != BluetoothAdapter.STATE_ON){
            handleBluetoothOff()
            return
        }

        //start advertising again after a small delay
        viewModelScope.launch {
            delay(1000)
            startAdvertising()
        }
    }

    private fun handleQuestionReceived(question: Question) {
        // begin sequence to show question
        fun timeToStart(): Long = (question.startTime - bluetoothService.currentSystemTime) / 1_000_000 //convert to ms
        questionJob?.cancel()
        questionJob = viewModelScope.launch {
            var tts = timeToStart()

            _question.value = QuestionData("get ready", false)
            // countdown the last 3 seconds
            while (tts > 0){
                delay((tts % 1000) - 10)
                if (tts.div(1000) <= 3) _question.value = QuestionData(tts.div(1000).toString(), false)
                tts = timeToStart()
            }
            // show the question
            _question.value = QuestionData(question.text, true)

            // wait for the question activity period to end
            delay((question.endTime - bluetoothService.currentSystemTime) / 1_000_000)
            _question.value = QuestionData(question.text, false)

            delay(10000) // wait a bit and then nullify the question
            _question.value = null
            questionJob = null
        }
    }


    // --- Cleanup functions ---

    private fun handleBluetoothOff() {
        _connectionStatus.value = ConnectionState.DISCONNECTED_BT_OFF
        stopAdvertising() // AdvertiseCallback doesn't have a function for stopping so we call an event manually
        questionJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothService.clearPlayer()
        questionJob?.cancel()
    }
}

class PeripheralViewModelFactory(private val bluetoothService: BluetoothService) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(PeripheralViewModel::class.java)) throw IllegalArgumentException("Unknown ViewModel class")
        @Suppress("UNCHECKED_CAST") return PeripheralViewModel(bluetoothService) as T
    }
}