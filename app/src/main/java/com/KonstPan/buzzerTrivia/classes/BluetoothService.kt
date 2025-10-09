package com.KonstPan.buzzerTrivia.classes

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.KonstPan.buzzerTrivia.objects.MyUUIDs
import com.KonstPan.buzzerTrivia.objects.MyBluetoothStatusCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Queue
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach

@SuppressLint("MissingPermission")
class BluetoothService(private val context: Context) {

    // --- Core class objects ---

    private val tag = "TriviaBLEService"

    // Coroutine scope for emitting events
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Events to be observed by the ViewModel
    private val _bluetoothEvents = MutableSharedFlow<BluetoothEvent>()
    val bluetoothEvents = _bluetoothEvents.asSharedFlow()

    // Android Bluetooth objects
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    // --- Helper functions ---

    // Null check (equivalent to "?: run" )
    inline infix fun <T> T?.ifNull(block: () -> Unit): T? {
        this?: block()
        return this
    }

    // --- Bluetooth state events ---

    /**
     * ANY: Broadcast receiver for bluetooth state changes
     */
    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action

            // Read the action and act accordingly
            when (action){
                // Bluetooth state has changed
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )

                    when(state){
                        BluetoothAdapter.STATE_ON -> emitEvent(BluetoothStateChangedToOn)
                        BluetoothAdapter.STATE_TURNING_ON -> emitEvent(BluetoothStateChangedToTurningOn)
                        BluetoothAdapter.STATE_OFF -> {
                            emitEvent(BluetoothStateChangedToOff)
                            bluetoothClosing()
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            emitEvent(BluetoothStateChangedToTurningOff)
                            bluetoothClosing()
                        }
                        else -> emitError("Bluetooth state unknown")
                    }
                }
            }
        }
    }

    /**
     * Returns the current bluetooth state of the adaptor
     */
    val currentBluetoothState: Int get() = bluetoothAdapter.state

    /**
     * ANY: Register receiver for bluetooth state changes
     */
    fun registerBluetoothReceiver(){
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(mReceiver, filter)
        Log.d(tag, "ANY: Bluetooth receiver registered.")
    }

    /**
     * ANY: Unregister receiver for bluetooth state changes
     */
    fun unregisterBluetoothReceiver(){
        context.unregisterReceiver(mReceiver)
        Log.d(tag, "ANY: Bluetooth receiver unregistered.")
    }

    /**
     * ANY: handle unexpected bluetooth closing
     */
    fun bluetoothClosing(){
        Log.d(tag, "ANY: Bluetooth closing, cleaning up...")
        clearQueue() // Clear the gatt queue as nothing can be done with bluetooth off

        // HOST
        for(player in connectedPlayers.values) hostGattCallback.onConnectionStateChange(player, 0, BluetoothProfile.STATE_DISCONNECTED)
        stopScanning()

        // PLAYER
        if (connectedHost != null) playerGattServerCallback.onConnectionStateChange(connectedHost!!, 0, BluetoothProfile.STATE_DISCONNECTED)
        stopAdvertising()
        stopGattServer()
    }

    // --- HOST Ble Scanning ---

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning: Boolean = false

    /**
     * HOST: Starts scanning for players
     */
    fun startScanning() {
        if (!bluetoothAdapter.isEnabled) { emitError("Bluetooth must be enabled to start scanning."); return }
        if (isScanning) { emitError("Already scanning."); return }

        stopScanning() // Ensure no previous scan is running

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner ifNull { emitError("Bluetooth LE Scanner not available."); return}

        isScanning = true

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MyUUIDs.TRIVIA_GAME_SERVICE))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        bluetoothLeScanner!!.startScan(listOf(scanFilter), scanSettings, scanCallback)
        emitEvent(ScanStarted)
        Log.d(tag, "HOST: Started scanning for players.")
    }

    /**
     * HOST: Stops scanning for players
     */
    fun stopScanning() {
        if (bluetoothLeScanner == null) return
        bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothLeScanner = null
        isScanning = false
        emitEvent(ScanStopped)
        Log.d(tag, "HOST: Stopped scanning for players.")
    }

    /**
     * HOST: Scan callback, receives discovered devices
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            emitEvent(DeviceDiscovered(result.device, result.scanRecord?.deviceName))
            Log.d(tag, "HOST: Scan result: ${result.device.address}, Name: ${result.scanRecord?.deviceName ?: "N/A"}")
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach {
                emitEvent(DeviceDiscovered(it.device, it.scanRecord?.deviceName))
                Log.d(tag, "Player: Batch scan result: ${it.device.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(tag, "Player: Scan failed with error code: $errorCode")
            emitError("BLE Scan failed: $errorCode")
            emitEvent(ScanStopped)
        }
    }

    // --- PLAYER Advertising logic ---

    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var autoAdvertise: Boolean = false
    private var isAdvertising: Boolean = false

    /**
     *  PLAYER: Starts ble advertising
     */
    fun startAdvertising(duration: Int = 0, autoAdvertise: Boolean = false) {
        if (!bluetoothAdapter.isEnabled) { emitError("Bluetooth must be enabled to start advertising."); return }
        if (connectedHost != null) { emitError("Currently connected to host, disconnect to start advertising."); return }
        if (isAdvertising) { emitError("Already advertising."); return }

        this.autoAdvertise = autoAdvertise // Save the autoAdvertise setting

        stopAdvertising() // Ensure no previous advertising is running

        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser ifNull { emitError("Bluetooth LE Advertiser not available."); return }

        isAdvertising = true


        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(duration)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MyUUIDs.TRIVIA_GAME_SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true) //get device name though the scan response since it's too long for the normal data
            .build()

        bluetoothLeAdvertiser!!.startAdvertising(advertiseSettings, advertiseData, scanResponse, advertiseCallback)
        emitEvent(AdvertisingStarted)
        Log.d(tag, "Started advertising.")
    }

    /**
     * PLAYER: Stops ble advertising.
     */
    fun stopAdvertising() {
        if (bluetoothLeAdvertiser == null) return
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        bluetoothLeAdvertiser = null
        isAdvertising = false
        emitEvent(AdvertisingStopped)
        Log.d(tag, "Stopped advertising.")
    }

    /**
     * PLAYER: Callback for blr advertiser
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d(tag, "Advertisement started with: $settingsInEffect")
            emitEvent(AdvertisingStarted)
            startGattServer()
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(tag, "Advertisement failed to start: $errorCode")
            emitError("Advertisement failed: $errorCode")
            emitEvent(AdvertisingStopped)
        }
    }

    // --- HOST Connection logic ---

    data class ClockOffset(val rtd: Long, val offset: Long)
    
    private val connectedPlayers = mutableMapOf<String, BluetoothGatt>()
    private val playerClockOffsets = mutableMapOf<String, ClockOffset>()
    private var t0: Long = 0

    val currentSystemTime: Long
        get() = SystemClock.elapsedRealtimeNanos()

    private val playerBeats = mutableMapOf<String, Long>()
    private var heartbeatCleanupJob: Job? = null

    // Value to be sent/received by the player/host to tell the host to gracefully disconnect from the player
    private val playerDcNotifValue: ByteArray = "DC".toByteArray()

    /**
     * HOST: Connect to a discovered player device
     */
    fun connectToPlayer(device: BluetoothDevice) {
        if (connectedPlayers.containsKey(device.address)) { emitError("Already connected to ${device.name}"); return }
        queueGattOperation(Connect(device, context))
    }
    
    /**
     * HOST: Disconnect from a player device
     */
    fun disconnectFromPlayer(device: BluetoothDevice) {
        if (!connectedPlayers.containsKey(device.address)) { emitError("Not connected to player ${device.address}"); return }
        queueGattOperation(Disconnect(device))
    }

    /**
     * HOST: Disconnect from all connected players
     */
    fun disconnectFromPlayers() {
        Log.d(tag, "HOST: Disconnecting from all players")
        connectedPlayers.values.forEach { gatt -> disconnectFromPlayer(gatt.device) }
    }
    
    /**
     * HOST: Send question to a specific player
     */
    fun sendQuestionToPlayer(device: BluetoothDevice, question: Question, startingSystemTime: Long = currentSystemTime) {
        // validate input
        if (!connectedPlayers.containsKey(device.address)) { emitError("Not connected to player ${device.address}"); return }
        if (playerClockOffsets[device.address] == null) { emitError("player ${device.name} is not synced, ignoring"); return }

        // Convert the question text to bytes
        val textData = question.text.toByteArray()

        // Calculate the moment the question should appear on the player's screen based on their clock offset
        // here question.startTime is the delay in ms from "now" until the question appears
        val startTime = question.startTime * 1_000_000 + startingSystemTime + playerClockOffsets[device.address]!!.offset

        // Calculate the moment the question timer ends on the player's screen and they can't answer anymore
        val endTine = question.endTime * 1_000_000 + startTime

        // Put question data into a byte buffer in a convenient order (first 16 bytes are the two timestamps(longs) and the rest is all text)
        val data = ByteBuffer.allocate(16 + textData.size)
                            .putLong(startTime)
                            .putLong(endTine)
                            .put(textData)
                            .array()

        // Don't send questions longer than 512 bytes (note that passing the sync check above implies the mtu is set appropriately)
        if (data.size > 512) { emitError("Question too long"); return }

        // create question operation
        val questionOperation = CharacteristicWrite(
            device = device,
            serviceUuid = MyUUIDs.TRIVIA_GAME_SERVICE,
            characteristicUuid = MyUUIDs.QUESTION_UUID,
            data = data,
            onComplete = { success, statusCode, message ->
                if (!success) emitEvent(SentQuestionError) // send a special error if a player doesn't receive the question
            }
        )

        // queue operation
        queueGattOperation(questionOperation)
        Log.d(tag, "HOST: Queued question of ${data.size} bytes to send to player: ${device.address}")
    }
    
    /**
     * HOST: Send question to all connected players
     */
    fun sendQuestionToAllPlayers(question: Question?) {
        if (question == null) { emitError("Null question given, ignoring"); return }
        Log.d(tag, "HOST: Sending questions to all players")
        val currentTime = currentSystemTime
        connectedPlayers.values.forEach { gatt -> sendQuestionToPlayer(gatt.device, question, currentTime) }
    }

    /**
     * Call a specific amount of SyncRequests to approximate a devices clock offset
     */
    fun synchronizePlayerClocks(device: BluetoothDevice, requestCount: Int = 5){
        if (!connectedPlayers.containsKey(device.address)) { emitError("Not connected to player ${device.address}"); return }
        emitEvent(PlayerStatusChanged(device, "Synchronizing"))

        val syncCount = requestCount
        var completedRequests = 0

        val onComplete = { success: Boolean, statusCode: Int, message: String ->
            Log.d(tag, "Completion message: $message")
            completedRequests++
            if (completedRequests >= syncCount) {
                Log.d(tag, "All synchronization requests completed for ${device.address}")
                emitEvent(PlayerStatusChanged(device, "Ready"))
            }
        }

        repeat(syncCount) {
            val syncRequest = SyncRequest(device, onComplete=onComplete)
            queueGattOperation(syncRequest)
        }

    }

    /**
     * HOST: Start coroutine to periodically disconnect from dead devices(no longer sending heartbeats)
     */
    private fun startHeartbeatCleanupTimer() {
        Log.d(tag, "HOST: Starting heartbeat cleanup timer")
        heartbeatCleanupJob = serviceScope.launch {
            while (true) {
                delay(1000)
                removeDeadDevices()
            }
        }
    }

    /**
     * HOST: Disconnect from dead devices
     */
    private fun removeDeadDevices() {
        // Find devices that haven't sent a heartbeat in the last few seconds
        val deadDevices = playerBeats.filter { (_, timestamp) ->
            System.currentTimeMillis() - timestamp > 5000 // 5 seconds
        }

        // Remove dead devices from the list
        deadDevices.keys.forEach { address -> disconnectFromPlayer(connectedPlayers[address]!!.device) }
    }
    
    /**
     * HOST: GATT callback for connecting to players
     */
    private val hostGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val device = gatt.device

            when (newState) {

                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(tag, "HOST: Connected to player: ${device.address}")
                    emitEvent(PlayerConnected(device))

                    // Complete the Connect operation
                    currentOperation?.let { operation ->
                        if (operation is Connect && operation.device.address == gatt.device.address)
                            completeOperation(operation, status == BluetoothGatt.GATT_SUCCESS, status, "Connected to player: ${device.name}")
                    }

                    connectedPlayers[device.address] = gatt // save the connection

                    // Use a longer delay for service discovery to ensure connection stability
                    Handler(Looper.getMainLooper()).postDelayed({
                        queueGattOperation(DiscoverServices(device))
                    }, 100) // small delay for stability
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(tag, "HOST: Disconnected from player: ${device.address}")
                    gatt.close()
                    connectedPlayers.remove(device.address)
                    playerBeats.remove(device.address)
                    emitEvent(PlayerDisconnected(device))

                    // Complete the Disconnect operation
                    currentOperation?.let { operation ->
                        if (operation is Disconnect && operation.device.address == gatt.device.address)
                            completeOperation(operation, status == BluetoothGatt.GATT_SUCCESS, status, "Disconnected from player: ${device.name}")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            
            // Complete the discover services operation
            currentOperation?.let { operation ->
                if (operation is DiscoverServices && operation.device.address == gatt.device.address) {
                    completeOperation(operation, status == BluetoothGatt.GATT_SUCCESS, status, 
                        if (status == BluetoothGatt.GATT_SUCCESS) "Services discovered" else "Service discovery failed")
                }
            }
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(tag, "HOST: Services discovered for ${gatt.device.address}")
                emitEvent(ServicesDiscovered)

                // Enable notifications for buzzer characteristic
                val notificationOperation = CharacteristicNotification(
                    device = gatt.device,
                    serviceUuid = MyUUIDs.TRIVIA_GAME_SERVICE,
                    characteristicUuid = MyUUIDs.BUZZER,
                    cccdUuid = MyUUIDs.BUZZER_CCCD,
                    enable = true
                )
                queueGattOperation(notificationOperation)

                // Enable notifications for heartbeat characteristic
                val heartbeatNotificationOperation = CharacteristicNotification(
                    device = gatt.device,
                    serviceUuid = MyUUIDs.TRIVIA_GAME_SERVICE,
                    characteristicUuid = MyUUIDs.HEARTBEAT,
                    cccdUuid = MyUUIDs.HEARTBEAT_CCCD,
                    enable = true
                )
                queueGattOperation(heartbeatNotificationOperation)

                // Define a mtu request operation to be called after notifications are enabled for all characteristics
                val mtuRequestOperation = MtuRequest(
                    device = gatt.device,
                    mtu = 512,
                    onComplete = { success, statusCode, message ->
                        if (!success) return@MtuRequest // do nothing if the operation failed
                        synchronizePlayerClocks(gatt.device)
                    }
                )

                // Enable notifications for synchronization characteristic, request a higher MTU, and then synchronize player clocks
                val syncNotificationOperation = CharacteristicNotification(
                    device = gatt.device,
                    serviceUuid = MyUUIDs.TRIVIA_GAME_SERVICE,
                    characteristicUuid = MyUUIDs.SYNCHRONIZATION,
                    cccdUuid = MyUUIDs.SYNCHRONIZATION_CCCD,
                    enable = true,
                    onComplete = { success, statusCode, message ->
                        if (!success) return@CharacteristicNotification // do nothing if the operation failed
                        queueGattOperation(mtuRequestOperation)
                    }
                )
                queueGattOperation(syncNotificationOperation)

                //start heartbeat timer
                startHeartbeatCleanupTimer()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)

            when (characteristic.uuid) {
                MyUUIDs.BUZZER -> {
                    try {
                        // read buzz data
                        val buffer = ByteBuffer.wrap(value)
                        val playerTimestamp = if(playerClockOffsets[gatt.device.address] != null)
                            buffer.long - playerClockOffsets[gatt.device.address]!!.offset // read player time and apply offset
                            else currentSystemTime // fallback to current time if no offset is available
                        val questionHash = buffer.int // read question hash

                        // Send Buzz to UI
                        Log.d(tag, "HOST: Player ${gatt.device.address} buzzed at player time: $playerTimestamp, received at: $currentSystemTime")
                        emitEvent(PlayerBuzzed(gatt.device, playerTimestamp, questionHash))
                    } catch (e: Exception) {
                        Log.e(tag, "HOST: Error parsing buzz data from ${gatt.device.address}: ${e.message}")
                        emitError("Error parsing buzz from ${gatt.device.name}: ${e.message}")
                    }
                }
                MyUUIDs.HEARTBEAT -> {
                    //Log.d(tag, "HOST: received heartbeat from ${gatt.device.address}")
                    playerBeats[gatt.device.address] = System.currentTimeMillis() // Update the player's heartbeat timestamp
                    if (playerDcNotifValue.contentEquals(value)) disconnectFromPlayer(gatt.device) //check if the player wants to disconnect
                }
                MyUUIDs.SYNCHRONIZATION -> {
                    // Complete the sync operation
                    currentOperation?.let { operation ->
                        if (operation is SyncRequest && operation.device.address == gatt.device.address) {
                            var status = BluetoothGatt.GATT_SUCCESS

                            // Calculate clock offset
                            try {
                                val buffer = ByteBuffer.wrap(value)

                                // t0 already exists // client sent time
                                val t1 = buffer.long  // server received time
                                val t2 = buffer.long  // server sent response time
                                val t3 = currentSystemTime // client received response time

                                val offset = ((t1 - t0) + (t2 - t3)) / 2 // SystemClock offset of the 2 phones
                                val rtd = (t3 - t0) - (t2 - t1) // round trip delay

                                val device = gatt.device

                                //update player clock offset if the new rtd is smaller than the saved one
                                if(playerClockOffsets[device.address] == null || rtd < playerClockOffsets[device.address]!!.rtd)
                                    playerClockOffsets[device.address] = ClockOffset(rtd, offset)

                                //Log.d(tag, "HOST: Clock offset for ${device.address}: ${offset}ns")

                            } catch (e: Exception) {
                                Log.e(tag, "HOST: Failed to parse sync response: ${e.message}")
                                status = MyBluetoothStatusCodes.SYNC_RESPONSE_PARSE_ERROR
                            }
                            completeOperation(operation, status == BluetoothGatt.GATT_SUCCESS, status, "Sync response received")
                        }
                        else Log.w(tag, "HOST: Unexpected sync response from ${gatt.device.address}")
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            
            // Complete the write operation
            currentOperation?.let { operation ->
                if (operation is CharacteristicWrite && operation.device.address == gatt.device.address &&
                    operation.characteristicUuid == characteristic.uuid) {
                    completeOperation(operation, status == BluetoothGatt.GATT_SUCCESS, status,
                        if (status == BluetoothGatt.GATT_SUCCESS) "Write successful" else "Write failed")
                }
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                //Log.d(tag, "HOST: Successfully wrote to characteristic ${characteristic.uuid}")
            } else {
                Log.e(tag, "HOST: Failed to write to characteristic ${characteristic.uuid}, status: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            
            // Complete the descriptor write operation
            currentOperation?.let { operation ->
                if (operation is CharacteristicNotification && operation.device.address == gatt.device.address && descriptor.uuid == operation.cccdUuid) {
                    completeOperation(operation, status == BluetoothGatt.GATT_SUCCESS, status,
                        if (status == BluetoothGatt.GATT_SUCCESS) "Descriptor write successful" else "Descriptor write failed")
                }
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(tag, "HOST: Successfully wrote to descriptor ${descriptor.uuid}")
            } else {
                Log.e(tag, "HOST: Failed to write to descriptor ${descriptor.uuid}, status: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            currentOperation?.let { operation ->
                if (operation is MtuRequest && operation.device.address == gatt?.device!!.address) {
                    completeOperation(operation, status == BluetoothGatt.GATT_SUCCESS, status,
                        if (status == BluetoothGatt.GATT_SUCCESS) "MtuChange successful" else "MtuChange failed")
                }
            }
        }
    }

    // --- PLAYER Connection logic ---

    private var gattServer: BluetoothGattServer? = null
    private var buzzerCharacteristic: BluetoothGattCharacteristic? = null
    private var questionCharacteristic: BluetoothGattCharacteristic? = null
    private var heartBeatCharacteristic: BluetoothGattCharacteristic? = null
    private var synchronizationCharacteristic: BluetoothGattCharacteristic? = null
    var connectedHost: BluetoothDevice? = null
        private set
    private var heartbeatJob: Job? = null

    /**
     * PLAYER: Start GATT server for trivia game
     */
    fun startGattServer() {
        // check that bluetooth is enabled
        if (!bluetoothAdapter.isEnabled) { emitError("Bluetooth must be enabled to start GATT server."); return }

        stopGattServer() // Ensure no previous server is running

        // Create GATT server
        gattServer = bluetoothManager.openGattServer(context, playerGattServerCallback) ifNull { emitError("Failed to open GATT server."); return }

        // Create trivia game service
        val triviaService = BluetoothGattService(
            MyUUIDs.TRIVIA_GAME_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Create characteristics
        buzzerCharacteristic = BluetoothGattCharacteristic(
            MyUUIDs.BUZZER,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        heartBeatCharacteristic = BluetoothGattCharacteristic(
            MyUUIDs.HEARTBEAT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        synchronizationCharacteristic = BluetoothGattCharacteristic(
            MyUUIDs.SYNCHRONIZATION,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Add CCCD descriptors for notifications
        val buzzDescriptor = BluetoothGattDescriptor(
            MyUUIDs.BUZZER_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        buzzerCharacteristic?.addDescriptor(buzzDescriptor)

        val heartBeatDescriptor = BluetoothGattDescriptor(
            MyUUIDs.HEARTBEAT_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        heartBeatCharacteristic?.addDescriptor(heartBeatDescriptor)

        val synchronizationDescriptor = BluetoothGattDescriptor(
            MyUUIDs.SYNCHRONIZATION_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        synchronizationCharacteristic?.addDescriptor(synchronizationDescriptor)

        // Create question characteristic (write)
        questionCharacteristic = BluetoothGattCharacteristic(
            MyUUIDs.QUESTION_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Add characteristics to service
        triviaService.addCharacteristic(buzzerCharacteristic)
        triviaService.addCharacteristic(heartBeatCharacteristic)
        triviaService.addCharacteristic(synchronizationCharacteristic)
        triviaService.addCharacteristic(questionCharacteristic)

        // Add service to server
        val success = gattServer?.addService(triviaService) ?: false
        if (success) {
            Log.d(tag, "PLAYER: GATT server started successfully.")
        } else {
            emitError("Failed to add trivia service to GATT server.")
        }
    }

    /**
     * PLAYER: Stop GATT server
     */
    fun stopGattServer() {
        if(connectedHost != null) {
            gattServer?.cancelConnection(connectedHost)
            //call onConnectionStateChange manually as cancelling the connection doesn't trigger it
            playerGattServerCallback.onConnectionStateChange(connectedHost!!, BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTED)
        }
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        buzzerCharacteristic = null
        heartBeatCharacteristic = null
        synchronizationCharacteristic = null
        questionCharacteristic = null
        Log.d(tag, "PLAYER: GATT server stopped.")
    }

    /**
     * Player: Send an indication to tell the host to gracefully disconnect
     */
    fun disconnectFromHost(){
        if (connectedHost == null) { emitError("No host connected to disconnect from."); return }
        if (gattServer == null) { emitError("GATT server not initialized."); return }

        val disconnectNotificationOperation = ServerNotification(
            device = connectedHost!!,
            serviceUuid = MyUUIDs.TRIVIA_GAME_SERVICE,
            characteristicUuid = MyUUIDs.HEARTBEAT,
            data = playerDcNotifValue,
            isIndication = true,
            // onComplete = {success, statusCode, message -> {}} // in the future consider sending a disconnect event early to the UI
        )

        queueGattOperation(disconnectNotificationOperation)
        Log.d(tag, "PLAYER: Queued disconnect indication to host")
    }

    /**
     * PLAYER: Send buzz indication to host with current system time and received question hash
     */
    fun sendBuzz(question: String?) {
        if (connectedHost == null) { emitError("No host connected to send buzz."); return }
        if (gattServer == null) { emitError("GATT server not initialized."); return }
        if (question == null) { emitError("No question to send buzz for."); return }

        // Define buzz data, which is the current system time and the hash of the question
        val currentTime = currentSystemTime
        val buzzData = ByteBuffer.allocate(12).putLong(currentTime).putInt(question.hashCode()).array()

        // Define buzz operation
        val buzzOperation = ServerNotification(
            device = connectedHost!!,
            serviceUuid = MyUUIDs.TRIVIA_GAME_SERVICE,
            characteristicUuid = MyUUIDs.BUZZER,
            data = buzzData,
            isIndication = true
        )

        // Queue buzz operation
        queueGattOperation(buzzOperation)
        Log.d(tag, "PLAYER: Queued buzz indication to host with timestamp: $currentTime and hash: ${question.hashCode()}")
    }

    private fun startHeartbeat() {
        Log.d(tag, "PLAYER: Starting heartbeat timer")
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(500)
                if (connectedHost == null) { Log.e(tag, "PLAYER: No host connected to send heartbeat."); return@launch }
                if (gattServer == null) { Log.e(tag, "PLAYER: GATT server not initialized in heartbeat."); return@launch }
                val heartbeatOperation = ServerNotification(
                    device = connectedHost!!,
                    serviceUuid = MyUUIDs.TRIVIA_GAME_SERVICE,
                    characteristicUuid = MyUUIDs.HEARTBEAT,
                    data = "BEAT".toByteArray()
                )

                queueGattOperation(heartbeatOperation)
                Log.d(tag, "PLAYER: Queued beat notification to host.")
            }
        }
    }


    /**
     * PLAYER: GATT server callback
     */
    private val playerGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            when (newState) {

                BluetoothProfile.STATE_CONNECTED -> {
                    // cancel any new connection if a host already exists
                    if (connectedHost != null) {
                        emitError("Host already connected")
                        gattServer?.cancelConnection(device)
                        return
                    }

                    Log.d(tag, "PLAYER: Host connected: ${device.address}")
                    emitEvent(HostConnected(device))

                    connectedHost = device // save the connected host
                    stopAdvertising() // A player should only ever connect to one host at a time, so stop advertising
                    startHeartbeat() // Start sending heartbeats to the host
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(tag, "PLAYER: Host disconnected: ${device.address}")

                    // Don't clear entire queue - just reset busy state if no other operations
                    if (pendingGattOperations.isEmpty()) isGattBusy = false

                    // if the disconnected device isn't the saved host, it means it's someone trying to connect afterwards
                    // in that case the pseudo-host gets disconnected automatically and the callback doesn't need to do anything else
                    if (connectedHost?.address != device.address) return

                    emitEvent(HostDisconnected)

                    // clear the saved host to allow new connections
                    connectedHost = null

                    // stop sending heartbeats to the non existent host
                    heartbeatJob?.cancel()

                    // If a host doesn't quickly connect restart advertising
                    if (autoAdvertise)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (connectedHost == null) {
                                Log.d(tag, "PLAYER: Restarting advertising after disconnection")
                                startAdvertising()
                            }
                        }, 2000) // 2s delay

                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            super.onServiceAdded(status, service)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(tag, "PLAYER: Service added successfully: ${service.uuid}")
            } else {
                Log.e(tag, "PLAYER: Failed to add service: ${service.uuid}, status: $status")
                emitError("Failed to add GATT service")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            when (characteristic.uuid) {
                MyUUIDs.QUESTION_UUID -> {
                    if (value != null) {
                        try {
                            // read question from value
                            val buffer = ByteBuffer.wrap(value)

                            // first 16 bytes are 2 longs and the rest are the question text
                            val startTime = buffer.long
                            val endTime = buffer.long
                            val text = StandardCharsets.US_ASCII.decode(buffer).toString()

                            val question = Question(text = text, startTime = startTime, endTime = endTime)

                            Log.d(tag, "PLAYER: Question received: \"$text\", to start in ${(startTime - currentSystemTime)/1_000_000_000} sec and last for ${(endTime - startTime)/1_000_000_000} sec")
                            emitEvent(QuestionReceived(question))

                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                        catch (e: Exception) {
                            Log.e(tag, "PLAYER: Failed to parse question: ${e.message}")
                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                    }
                }
                MyUUIDs.SYNCHRONIZATION -> {
                    if (value != null) {
                        try {
                            // Send sync response via notification
                            val t1 = currentSystemTime
                            queueGattOperation(SyncResponse(device, t1))

                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

                        } catch (e: Exception) {
                            Log.e(tag, "PLAYER: Failed to handle sync request: ${e.message}")
                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                    }
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)

            when (descriptor.uuid) {
                MyUUIDs.BUZZER_CCCD -> {
                    val isNotificationEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                    Log.d(tag, "PLAYER: Buzzer notifications ${if (isNotificationEnabled) "enabled" else "disabled"} for ${device.address}")
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                MyUUIDs.HEARTBEAT_CCCD -> {
                    val isNotificationEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                    Log.d(tag, "PLAYER: Heartbeat notifications ${if (isNotificationEnabled) "enabled" else "disabled"} for ${device.address}")
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                MyUUIDs.SYNCHRONIZATION_CCCD -> {
                    val isNotificationEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                    Log.d(tag, "PLAYER: Synchronization notifications ${if (isNotificationEnabled) "enabled" else "disabled"} for ${device.address}")
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                else -> {
                    Log.w(tag, "PLAYER: Unknown descriptor write request: ${descriptor.uuid}")
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            val success = status == BluetoothGatt.GATT_SUCCESS

            // Complete the notification operation
            currentOperation?.let { operation ->
                if (operation is ServerNotification && operation.device.address == device.address) {
                    completeOperation(operation, success, status,
                        if (success) "Notification sent successfully" else "Notification failed")

                    if (success && operation.characteristicUuid == MyUUIDs.BUZZER) emitEvent(BuzzSentSuccessfully)
                    else if (success && operation.characteristicUuid == MyUUIDs.HEARTBEAT){
                        //Log.d(tag, "PLAYER: Heartbeat sent successfully to ${device.address}")
                    }
                    else emitError("notification ${operation.characteristicUuid} failed")
                }
                else if (operation is SyncResponse && operation.device.address == device.address) {
                    completeOperation(operation, success, status,
                        if (success) "Sync response notification sent successfully" else "Sync response notification failed"
                    )
                }
            }

            if (success) {
                Log.d(tag, "PLAYER: Notification sent successfully to ${device.address}")
            } else {
                Log.e(tag, "PLAYER: Failed to send notification to ${device.address}, status: $status")
            }
        }
    }

    // --- GATT Operation Queue ---

    private var isGattBusy = false
    private val pendingGattOperations: Queue<BleOperation> = LinkedList()
    private var currentOperation: BleOperation? = null

    /**
     * Queue a BLE operation for execution
     */
    private fun queueGattOperation(operation: BleOperation) {
        pendingGattOperations.add(operation)
        Log.d(tag, "Queued operation: ${operation.javaClass.simpleName} for device ${operation.device.address}")
        if (!isGattBusy) dequeueGattOperation()
    }

    /**
     * Execute the next operation in the queue
     */
    private fun dequeueGattOperation() {
        // If a previous operation is still ongoing, do nothing
        if (isGattBusy) return

        // Get the next operation from the queue
        val operation = pendingGattOperations.poll()
        if (operation == null) return

        // Set the current operation and mark the queue as busy
        isGattBusy = true
        currentOperation = operation

        Log.d(tag, "Executing operation: ${operation.javaClass.simpleName} for device ${operation.device.address}")

        // Set a timeout to prevent the queue from getting stuck
        Handler(Looper.getMainLooper()).postDelayed({
            if (isGattBusy && currentOperation?.operationId == operation.operationId) {
                Log.w(tag, "GATT operation timed out: ${operation.javaClass.simpleName}")
                emitError("GATT operation timed out: ${operation.javaClass.simpleName}")
                completeOperation(operation, false, -1, "Operation timeout")
            }
        }, operation.timeout)

        // Execute operation on the main thread
        Handler(Looper.getMainLooper()).post { executeGattOperation(operation) }
    }

    /**
     * Execute a specific GATT operation
     */
    private fun executeGattOperation(operation: BleOperation) {
        try {
            // Execute the operation given its class type
            when (operation) {
                is Connect -> {
                    if (connectedPlayers.containsKey(operation.device.address))
                        completeOperation(operation, false, MyBluetoothStatusCodes.DEVICE_ALREADY_CONNECTED, "Device already connected")
                    else operation.device.connectGatt(operation.context, false, hostGattCallback)
                }

                is Disconnect -> {
                    val gatt = connectedPlayers[operation.device.address]
                    gatt?.disconnect() ?: completeOperation(operation, false, -1, "Device not connected")
                }
                
                is DiscoverServices -> {
                    val gatt = connectedPlayers[operation.device.address]
                    val success = gatt?.discoverServices() ?: false
                    if (!success) completeOperation(operation, false, -1, "Failed to start service discovery")
                }
                
                is CharacteristicWrite -> {
                    val gatt = connectedPlayers[operation.device.address] ifNull { completeOperation(operation, false, -1, "Device not connected"); return }
                    val service = gatt!!.getService(operation.serviceUuid) ifNull { completeOperation(operation, false, -1, "Service not found"); return }
                    val characteristic = service!!.getCharacteristic(operation.characteristicUuid) ifNull { completeOperation(operation, false, -1, "Characteristic not found"); return }

                    // Wait a bit for stability
                    Thread.sleep(100)

                    val action = gatt.writeCharacteristic(characteristic!!, operation.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    if (action != BluetoothStatusCodes.SUCCESS) completeOperation(operation, false, -1, "Failed to start write operation")
                }
                
                is CharacteristicRead -> {
                    val gatt = connectedPlayers[operation.device.address] ifNull { completeOperation(operation, false, -1, "Device not connected"); return }
                    val service = gatt!!.getService(operation.serviceUuid) ifNull { completeOperation(operation, false, -1, "Service not found"); return }
                    val characteristic = service!!.getCharacteristic(operation.characteristicUuid) ifNull { completeOperation(operation, false, -1, "Characteristic not found"); return }

                    val success = gatt.readCharacteristic(characteristic!!)
                    if (!success) completeOperation(operation, false, -1, "Failed to start read operation")
                }
                
                is CharacteristicNotification -> {
                    val gatt = connectedPlayers[operation.device.address] ifNull { completeOperation(operation, false, -1, "Device not connected"); return }
                    val service = gatt!!.getService(operation.serviceUuid) ifNull { completeOperation(operation, false, -1, "Service not found"); return }
                    val characteristic = service!!.getCharacteristic(operation.characteristicUuid) ifNull { completeOperation(operation, false, -1, "Characteristic not found"); return }

                    val success = gatt.setCharacteristicNotification(characteristic!!, operation.enable)
                    if (!success) { completeOperation(operation, false, -1, "Failed to set notification"); return }

                    // If enabling notifications, also write to CCCD
                    if (operation.enable) {
                        val descriptor = characteristic.getDescriptor(operation.cccdUuid)
                        if (descriptor != null) {
                            val descriptorSuccess = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            if (descriptorSuccess != BluetoothStatusCodes.SUCCESS) completeOperation(operation, false, -1, "Failed to write CCCD descriptor, status: $descriptorSuccess")
                        }
                        else completeOperation(operation, false, -1, "CCCD descriptor not found")
                    }
                    else // For disabling notifications, we can complete immediately since we don't write to CCCD
                        completeOperation(operation, true, 0, "Notification disabled")
                }
                
                is ServerNotification -> {
                    if (gattServer == null) { completeOperation(operation, false, -1, "GATT server not available"); return }
                    if (connectedHost == null) { completeOperation(operation, false, -1, "Host not connected"); return }
                    
                    val service = gattServer!!.getService(operation.serviceUuid) ifNull { completeOperation(operation, false, -1, "Service not found on server"); return }
                    val characteristic = service!!.getCharacteristic(operation.characteristicUuid) ifNull { completeOperation(operation, false, -1, "Characteristic not found on server"); return }

                    val action = gattServer!!.notifyCharacteristicChanged(operation.device, characteristic!!, operation.isIndication, operation.data)
                    if (action != BluetoothStatusCodes.SUCCESS) completeOperation(operation, false, -1, "Failed to send notification")
                }
                
                is MtuRequest -> {
                    val gatt = connectedPlayers[operation.device.address] ifNull { completeOperation(operation, false, -1, "Device not connected"); return }

                    val success = gatt!!.requestMtu(operation.mtu)
                    if (!success) completeOperation(operation, false, -1, "Failed to request MTU")
                }

                is SyncRequest -> {
                    // SyncRequest is a special type of CharacteristicWrite
                    val gatt = connectedPlayers[operation.device.address] ifNull { completeOperation(operation, false, -1, "Device not connected"); return }
                    val service = gatt!!.getService(MyUUIDs.TRIVIA_GAME_SERVICE) ifNull { completeOperation(operation, false, -1, "Service not found"); return }
                    val characteristic = service!!.getCharacteristic(MyUUIDs.SYNCHRONIZATION) ifNull { completeOperation(operation, false, -1, "Characteristic not found"); return }

                    // Wait a bit for stability
                    Thread.sleep(100)

                    // special data (we don't actually need to send t1, just saving it locally is enough, we do need to send a non-null value though)
                    t0 = currentSystemTime
                    val requestPayload = ByteBuffer.allocate(8).putLong(t0).array()

                    // Send request
                    val action = gatt.writeCharacteristic(characteristic!!, requestPayload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    if (action != BluetoothStatusCodes.SUCCESS) completeOperation(operation, false, -1, "Failed to start sync request write operation")
                }

                is SyncResponse -> {
                    // SyncRequest is a specific type of ServerNotification
                    if (gattServer == null) { completeOperation(operation, false, -1, "GATT server not available"); return }
                    if (connectedHost == null) { completeOperation(operation, false, -1, "Host not connected"); return }

                    val service = gattServer!!.getService(MyUUIDs.TRIVIA_GAME_SERVICE) ifNull { completeOperation(operation, false, -1, "Service not found on server"); return }
                    val characteristic = service!!.getCharacteristic(MyUUIDs.SYNCHRONIZATION) ifNull { completeOperation(operation, false, -1, "Characteristic not found on server"); return }

                    // Special data
                    val t2 = currentSystemTime
                    val responsePayload = ByteBuffer.allocate(16)
                        .putLong(operation.t1)
                        .putLong(t2)
                        .array()

                    // Send notification
                    val action = gattServer!!.notifyCharacteristicChanged(operation.device, characteristic!!, false, responsePayload)
                    if (action != BluetoothStatusCodes.SUCCESS) completeOperation(operation, false, -1, "Failed to send sync response notification")
                }
            }
        } catch (e: Exception) {
            // if an error occurred, complete the operation with a failure
            Log.e(tag, "Error executing GATT operation: ${e.message}")
            completeOperation(operation, false, -1, "Exception: ${e.message}")
        }
    }

    /**
     * Mark an operation as complete and process the next one
     */
    private fun completeOperation(operation: BleOperation, success: Boolean, statusCode: Int, message: String = "") {
        //check that the current operation is the one being completed, should always be true. Useful for debugging
        if (currentOperation?.operationId != operation.operationId) {
            Log.w(tag, "Completing operation that is not current: ${operation.operationId}")
            val message = when (operation) {
                is CharacteristicNotification -> "UUID: ${operation.characteristicUuid}"
                else -> "Operation is not current"
            }
            Log.w(tag, "message: $message")
            return
        }

        // Remove the operation from the queue and mark it as not busy to allow the next one to start
        currentOperation = null
        isGattBusy = false

        // Log the completed operation if it's successful or process further it if it's not
        if (success) Log.d(tag, "Operation completed successfully: ${operation.javaClass.simpleName}")
        else handleFailedOperation(operation, statusCode, message)

        // Execute completion callback if provided
        operation.onComplete?.invoke(success, statusCode, message)

        // Process next operation
        dequeueGattOperation()
    }

    /**
     * Handle a failed operation
     */
    private fun handleFailedOperation(operation: BleOperation, statusCode: Int, message: String){
        // Handle failed operations
        when(operation){
            is Connect -> {
                if (statusCode == MyBluetoothStatusCodes.DEVICE_ALREADY_CONNECTED) return //we tried to connect to an already connected device, so do nothing
                emitError("Failed to connect to: ${operation.device.name}")
                disconnectFromPlayer(operation.device)
            }
            is Disconnect -> {
                Log.w(tag, "Manually disconnecting from device: ${operation.device.name}")
                // this fails if the devices are already disconnected but no callback was used( in which case we just call it manually)
                // or we try to disconnect from a device that isn't connected in which case we don't do anything (may be caused by either role spamming the disconnect button)
                val gatt = connectedPlayers[operation.device.address]
                if (gatt != null) hostGattCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED)
            }
            is DiscoverServices -> {
                emitError("Failed to discover services for \"${operation.device.name}\", disconnecting")
                disconnectFromPlayer(operation.device)
            }
            is CharacteristicNotification -> {
                emitError("Failed to set notification for \"${operation.device.name}\", disconnecting")
                disconnectFromPlayer(operation.device)
            }
            is ServerNotification -> {
                emitError("Failed to send notification for \"${operation.device.name}\", disconnecting")
                if (operation.isIndication) stopGattServer() // kill the gatt server an indication should always be sent and therefore the device is disconnected
            }
            is MtuRequest -> {
                emitError("Failed to complete Mtu request for \"${operation.device.name}\", disconnecting")
                disconnectFromPlayer(operation.device)
            }
            is SyncRequest -> Log.w(tag, "Failed to complete sync request for \"${operation.device.name}\", status: $statusCode")
            is SyncResponse -> Log.w(tag,"Failed to send sync response for \"${operation.device.name}\"")
            else -> emitError("Operation failed: ${operation.javaClass.simpleName} - $message")
        }
    }

    // --- clean up functions ---
    /**
     * Clear the queue and active operations
     */
    fun clearQueue() {
        pendingGattOperations.clear()
        currentOperation = null
        isGattBusy = false
    }

    fun clearHost() {
        stopScanning() // Stop potential scans manually to emmit the appropriate event (otherwise the UI data won't update)
        disconnectFromPlayers() // Ensure host disconnects from everyone if the activity is permanently destroyed
        heartbeatCleanupJob?.cancel() // Cancel the heartbeat hearing job
    }

    fun clearPlayer() {
        stopAdvertising() // Stop advertising manually to emmit the appropriate event (otherwise the UI data won't update)
        stopGattServer() // Ensure server stops and host disconnects from everyone if the activity is permanently destroyed
        heartbeatJob?.cancel() // Cancel the heartbeat job
    }


    // --- Event Emission ---

    private fun emitError(message: String) = emitEvent(BluetoothEventError(message))

    private fun emitEvent(event: BluetoothEvent) {
        // Emit the event to the shared flow
        serviceScope.launch { _bluetoothEvents.emit(event) }

        // Log the event for debugging
        if (event is BluetoothEventError) Log.w(tag, "Event: ${event.message}")
        else Log.d(tag, "Event: ${event.javaClass.simpleName}, Queue size: ${pendingGattOperations.size}")
    }
}