package com.placeholder.myFirstApp.viewModels

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.placeholder.myFirstApp.classes.BluetoothEvent
import com.placeholder.myFirstApp.classes.BluetoothService
import com.placeholder.myFirstApp.classes.BluetoothStateChangedToOff
import com.placeholder.myFirstApp.classes.BluetoothStateChangedToOn
import com.placeholder.myFirstApp.classes.BluetoothStateChangedToTurningOff
import com.placeholder.myFirstApp.classes.ConnectionState
import com.placeholder.myFirstApp.classes.DeviceDiscovered
import com.placeholder.myFirstApp.classes.GameEvent
import com.placeholder.myFirstApp.classes.GameService
import com.placeholder.myFirstApp.classes.GameState
import com.placeholder.myFirstApp.classes.SetPlayerPoints
import com.placeholder.myFirstApp.classes.PlayerBuzzed
import com.placeholder.myFirstApp.classes.PlayerBuzzedFirst
import com.placeholder.myFirstApp.classes.PlayerConnected
import com.placeholder.myFirstApp.classes.PlayerDevice
import com.placeholder.myFirstApp.classes.PlayerDisconnected
import com.placeholder.myFirstApp.classes.PlayerStatusChanged
import com.placeholder.myFirstApp.classes.Question
import com.placeholder.myFirstApp.classes.RoundEnded
import com.placeholder.myFirstApp.classes.RoundStarted
import com.placeholder.myFirstApp.classes.RoundUpdated
import com.placeholder.myFirstApp.classes.ScanStarted
import com.placeholder.myFirstApp.classes.ScanStopped
import com.placeholder.myFirstApp.database.QuestionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class CentralViewModel(
    private val bluetoothService: BluetoothService, questionRepository: QuestionRepository? = null
) : BluetoothViewModel(bluetoothService, questionRepository) {

    private val _availablePlayers = MutableStateFlow<List<PlayerDevice>>(emptyList())
    val availablePlayers = _availablePlayers.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _questionTimer = MutableStateFlow<String?>(null)
    val questionTimer = _questionTimer.asStateFlow()

    private val playersMap = mutableMapOf<String, PlayerDevice>()
    private val deviceMap = mutableMapOf<String, BluetoothDevice>()
    private val deviceTimestamps = mutableMapOf<String, Long>()
    private var cleanupJob: kotlinx.coroutines.Job? = null
    private var questionJob: kotlinx.coroutines.Job? = null

    init {
        startDeviceCleanupTimer()

        // Load the appropriate fragment on the activity based on the bluetooth state
        if (bluetoothService.currentBluetoothState != BluetoothAdapter.STATE_ON)
            _connectionStatus.value = ConnectionState.DISCONNECTED_BT_OFF
        else // If bluetooth is on, start scanning
            viewModelScope.launch {
                delay(1000) // Wait 1 second in case bluetoothService is cleaning up
                startScanning()
            }
    }

    /**
     * Handle events from the BluetoothService
     */
    override fun collectBluetoothEvents(event: BluetoothEvent) {
        when (event) {
            // bluetooth state events
            is BluetoothStateChangedToOff -> bluetoothOff()
            is BluetoothStateChangedToTurningOff -> bluetoothOff()
            is BluetoothStateChangedToOn -> startScanning()

            // scan events
            is ScanStarted -> _connectionStatus.value = ConnectionState.SCANNING
            is ScanStopped -> _connectionStatus.value =
                if (playersMap.values.any { it.isConnected }) ConnectionState.CONNECTED
                else ConnectionState.DISCONNECTED
            is DeviceDiscovered -> handleDeviceDiscovered(event.device, event.deviceName)

            // connection events
            is PlayerConnected -> handleDeviceConnection(event.device, true)
            is PlayerDisconnected -> handleDeviceConnection(event.device, false)
            is PlayerStatusChanged -> handleDeviceStatusChange(event.device, event.status)

            // game events (from bluetoothService)
            is PlayerBuzzed -> handleBuzz(event.device, event.timestamp, event.questionHash)
            else -> {}
        }
    }

    /**
     * Handle events from the GameService
     */
    override fun collectGameEvents(event: GameEvent) {
        when (event) {
            is PlayerBuzzedFirst -> {
                if (questionJob == null) return // only handle the first buzz if a question is active
                questionJob?.cancel()
                questionJob = null
                _questionTimer.value = "GO"
                _firstPlayerBuzz.value = event.player?.name
            }
            is RoundStarted -> {
                _question.value = event.round.question
                _questionTimer.value = "GO"
                _gameState.value = GameState.WAITING_FOR_BUZZ
            }
            is RoundUpdated -> _question.value = event.round.question
            is RoundEnded -> {
                _gameState.value = GameState.WAITING_FOR_ROUND
                _firstPlayerBuzz.value = null
            }
            is SetPlayerPoints -> {
                val status = "${event.points} points"
                handleDeviceStatusChange(deviceMap[event.player]!!, status)
            }
            else -> {}
        }
    }


    // --- Front-end actions ---
    /**
     * Starts scanning for players
     */
    fun startScanning() = bluetoothService.startScanning()

    /**
     * Stops scanning for players
     */
    fun stopScanning() = bluetoothService.stopScanning()

    /**
     * Connects to a player device
     */
    fun connectToDevice(device: PlayerDevice) = bluetoothService.connectToPlayer(deviceMap[device.address]!!)

    /**
     * Disconnects the host from a given player device if it's connected, and removes it from the list if it's not
     */
    fun disconnectFromDevice(device: PlayerDevice) {
        if (!deviceMap.containsKey(device.address)) return // failsafe if UI spam calls the function before updating itself
        if (device.isConnected) bluetoothService.disconnectFromPlayer(deviceMap[device.address]!!)
        else {
            setTrackDevice(deviceMap[device.address]!!, false)
            playersMap.remove(device.address)
            deviceMap.remove(device.address)
            _availablePlayers.value = playersMap.values.toList()
        }
    }
    fun disconnectFromPlayers() = bluetoothService.disconnectFromPlayers()

    /**
     * Starts a new round
     */
    fun startRound() = gameService.startRound()

    /**
     * Sends the current round question to all players and starts the "question sequence"
     */
    fun sendQuestion() {
        _question.value?: return
        bluetoothService.sendQuestionToAllPlayers(_question.value)

        fun timeToEvent(start: Long, timestamp: Long): Long = start + timestamp - bluetoothService.currentSystemTime / 1_000_000 //convert to ms

        questionJob?.cancel()
        questionJob = viewModelScope.launch {

            val now = bluetoothService.currentSystemTime / 1_000_000 // current time in ms

            // countdown before showing the question to the players
            var tte = timeToEvent(now, _question.value!!.startTime)
            while (tte > 0){
                delay((tte % 1000) - 10)
                _questionTimer.value = tte.div(1000).toString()
                tte = timeToEvent(now, _question.value!!.startTime)
            }
            _questionTimer.value = "GO"

            // countdown before the question stops receiving buzzes
            tte = timeToEvent(now, _question.value!!.startTime + _question.value!!.endTime)
            while (tte > 0){
                delay((tte % 1000) - 10)
                if (tte.div(1000) <= 5) _questionTimer.value = tte.div(1000).toString()
                tte = timeToEvent(now, _question.value!!.startTime + _question.value!!.endTime)
            }

            delay(1000) // extra second for last moment buzzes

            _questionTimer.value = "TIME\nOUT"

            // if no buzzes have been received, end the round
            if (_firstPlayerBuzz.value == null) {
                delay(4000)
                _questionTimer.value = null
                endRound(0, true)
            }

            questionJob = null
        }

    }

    // --- Device connection handling ---

    private fun saveDevice(device: BluetoothDevice, deviceName: String?) {
        // add device to lists
        val newPlayer = PlayerDevice(address = device.address, name = deviceName?: "")
        playersMap[device.address] = newPlayer
        deviceMap[device.address] = device

        // update UI
        _availablePlayers.value = playersMap.values.toList()
    }

    /**
     * add a new player to the list of available players
     */
    private fun handleDeviceDiscovered(device: BluetoothDevice, deviceName: String?){
        // Ignore connected devices (that have yet to stop advertising)
        if (playersMap[device.address]?.isConnected == true) return

        // Update device timestamp and check if it already exists
        setTrackDevice(device, true)
        if (playersMap.containsKey(device.address)) return

        // Add new player device to the list and update the available players
        saveDevice(device, deviceName)
    }

    /**
     * update player connection status and handle tracking
     */
    private fun handleDeviceConnection(device: BluetoothDevice, isConnected: Boolean) {
        // add device if it doesn't exist (was removed during connection)
        if (!playersMap.containsKey(device.address)) saveDevice(device, device.name)

        // update player object
        playersMap[device.address]!!.isConnected = isConnected
        playersMap[device.address]!!.status = if (isConnected) "Connected" else "Disconnected"

        // disable tracking for connected devices and enable for disconnected ones
        setTrackDevice(device, !isConnected)
        
        // update the UI
        _deviceConnectionUpdate.value = DeviceConnectionUpdate(device.address, isConnected, playersMap[device.address]!!.status)
    }

    /**
     * update player status
     */
    private fun handleDeviceStatusChange(device: BluetoothDevice, status: String){
        // update player object
        playersMap[device.address]!!.status = status

        // update the UI
        _deviceConnectionUpdate.value = DeviceConnectionUpdate(device.address, playersMap[device.address]!!.isConnected, status)
    }
    
    // --- Device tracking ---

    // StateFlow for connection updates
    data class DeviceConnectionUpdate(val address: String, val isConnected: Boolean, val status: String = if (isConnected) "Connected" else "Disconnected")
    private val _deviceConnectionUpdate = MutableStateFlow<DeviceConnectionUpdate?>(null)
    val deviceConnectionUpdate = _deviceConnectionUpdate.asStateFlow()

    /**
     * update a device's timestamp or stop tracking it
     */
    private fun setTrackDevice(device: BluetoothDevice, enabled: Boolean) =
        if(enabled) deviceTimestamps[device.address] = System.currentTimeMillis()
        else deviceTimestamps.remove(device.address)

    /**
     * Start coroutine to periodically remove stale devices from the list
     */
    private fun startDeviceCleanupTimer() {
        cleanupJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                removeStaleDevices()
            }
        }
    }

    /**
     * Remove stale devices from the list
     */
    private fun removeStaleDevices() {
        // Find devices that haven't been seen in the last few seconds
        val staleDevices = deviceTimestamps.filter { (_, timestamp) ->
            System.currentTimeMillis() - timestamp > 5000 // 5 seconds
        }

        // Remove stale devices from the list
        staleDevices.keys.forEach { address ->
            playersMap.remove(address)
            deviceMap.remove(address)
            deviceTimestamps.remove(address)
        }

        // Update the available players list if needed
        if (staleDevices.isNotEmpty()) _availablePlayers.value = playersMap.values.toList()
    }

    // --- Game functions ---

    private val _firstPlayerBuzz = MutableStateFlow<String?>(null)
    val firstPlayerBuzz = _firstPlayerBuzz.asStateFlow()

    private val _question = MutableStateFlow<Question?>(null)
    val question = _question.asStateFlow()

    /**
     * Initialize the game state
     */
    fun createGame(mode: String){
        val gameMode = when (mode) {
            "none" -> GameService.GameModeNONE(playersMap)
            "ffa" -> GameService.GameModeFFA(playersMap)
            else -> return
        }
        gameService.createGame(gameMode)
    }

    /**
     * Handle a received buzz from a player
     */
    private fun handleBuzz(device: BluetoothDevice, timestamp: Long, questionHash: Int){
        if (questionJob == null) return // Ignore buzzes if no question is active
        gameService.receiveBuzz(playersMap[device.address]!!, timestamp, questionHash)
    }

    /**
     * Set whether the answer was correct or not on the game service
     */
    fun setAnswer(value: Boolean) = gameService.evaluateAnswer(value)

    /**
     * End the current round after a small delay
     */
    fun endRound(delayMillis: Long = 1000, roundIsInvalid: Boolean = false) =
        viewModelScope.launch {
            questionJob?.cancel()
            questionJob = null
            delay(delayMillis)
            gameService.endRound(roundIsInvalid)
        }


    // --- Cleanup functions ---

    private fun bluetoothOff(){
        stopScanning() // ScanCallback doesn't have a function for stopping so we call an event manually
        endRound(0, true)
    }

    override fun onCleared() {
        super.onCleared()
        cleanupJob?.cancel() // Cancel the cleanup job when the ViewModel is cleared
        bluetoothService.clearHost()
        gameService.clearGameService()
    }
}

class CentralViewModelFactory(
    private val bluetoothService: BluetoothService, private val questionRepository: QuestionRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(CentralViewModel::class.java)) throw IllegalArgumentException("Unknown ViewModel class")
        @Suppress("UNCHECKED_CAST") return CentralViewModel(bluetoothService, questionRepository) as T
    }
}