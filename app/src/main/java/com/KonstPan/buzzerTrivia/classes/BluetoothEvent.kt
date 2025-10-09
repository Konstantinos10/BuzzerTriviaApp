package com.KonstPan.buzzerTrivia.classes

import android.bluetooth.BluetoothDevice

// Sealed classes
sealed class BluetoothEvent()
sealed class BluetoothStateEvent(): BluetoothEvent()

// Bluetooth state events
object BluetoothStateChangedToOn : BluetoothStateEvent()
object BluetoothStateChangedToTurningOn : BluetoothStateEvent()
object BluetoothStateChangedToOff : BluetoothStateEvent()
object BluetoothStateChangedToTurningOff : BluetoothStateEvent()
object BluetoothStateError : BluetoothStateEvent()

// Host events
object ScanStarted : BluetoothEvent()
object ScanStopped : BluetoothEvent()
data class DeviceDiscovered(val device: BluetoothDevice, val deviceName: String? = null) : BluetoothEvent()
data class PlayerConnected(val device: BluetoothDevice) : BluetoothEvent()
data class PlayerDisconnected(val device: BluetoothDevice) : BluetoothEvent()
object ServicesDiscovered : BluetoothEvent()
object SentQuestionError : BluetoothEvent()
data class PlayerStatusChanged(val device: BluetoothDevice, val status: String) : BluetoothEvent()
data class PlayerBuzzed(val device: BluetoothDevice, val timestamp: Long, val questionHash: Int) : BluetoothEvent()

// Player events
object AdvertisingStarted : BluetoothEvent()
object AdvertisingStopped : BluetoothEvent()
data class HostConnected(val device: BluetoothDevice) : BluetoothEvent()
object HostDisconnected: BluetoothEvent()
data class QuestionReceived(val question: Question) : BluetoothEvent()
object BuzzSentSuccessfully: BluetoothEvent()

// error events
data class BluetoothEventError(val message: String) : BluetoothEvent()
