package com.KonstPan.buzzerTrivia.classes

import android.bluetooth.BluetoothDevice
import android.content.Context
import java.util.UUID

// Sealed class for BLE operations
sealed class BleOperation {
    abstract val device: BluetoothDevice // Device associated with the operation
    abstract val operationId: String // Unique identifier for the operation
    abstract val timeout: Long // Maximum time to wait for the operation to complete, in milliseconds
    abstract val onComplete: ((success: Boolean, statusCode: Int, message: String) -> Unit)?
}

// Connection operations
data class Connect(
    override val device: BluetoothDevice, 
    val context: Context,
    override val operationId: String = "${device.address}_connect_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()

data class Disconnect(
    override val device: BluetoothDevice,
    override val operationId: String = "${device.address}_disconnect_${System.currentTimeMillis()}",
    override val timeout: Long = 1000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()

// Service discovery
data class DiscoverServices(
    override val device: BluetoothDevice,
    override val operationId: String = "${device.address}_discover_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()

// Characteristic operations
data class CharacteristicWrite(
    override val device: BluetoothDevice, 
    val serviceUuid: UUID,
    val characteristicUuid: UUID, 
    val data: ByteArray,
    override val operationId: String = "${device.address}_write_${characteristicUuid}_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CharacteristicWrite
        return device == other.device && 
               serviceUuid == other.serviceUuid &&
               characteristicUuid == other.characteristicUuid && 
               data.contentEquals(other.data) &&
               operationId == other.operationId
    }

    override fun hashCode(): Int {
        var result = device.hashCode()
        result = 31 * result + serviceUuid.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + operationId.hashCode()
        return result
    }
}

data class CharacteristicRead(
    override val device: BluetoothDevice, 
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    override val operationId: String = "${device.address}_read_${characteristicUuid}_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()

data class CharacteristicNotification(
    override val device: BluetoothDevice,
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val cccdUuid: UUID,
    val enable: Boolean,
    override val operationId: String = "${device.address}_notify_${characteristicUuid}_${enable}_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()

// Server notification operations (for GATT server)
data class ServerNotification(
    override val device: BluetoothDevice,
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val data: ByteArray,
    val isIndication: Boolean = false,
    override val operationId: String = "${device.address}_server_notify_${characteristicUuid}_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ServerNotification
        return device == other.device && 
               serviceUuid == other.serviceUuid &&
               characteristicUuid == other.characteristicUuid && 
               data.contentEquals(other.data) &&
               operationId == other.operationId
    }

    override fun hashCode(): Int {
        var result = device.hashCode()
        result = 31 * result + serviceUuid.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + operationId.hashCode()
        return result
    }
}
// MTU request
data class MtuRequest(
    override val device: BluetoothDevice,
    val mtu: Int = 512,
    override val operationId: String = "${device.address}_mtu_${mtu}_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()

//Clock Sync request
data class SyncRequest(
    override val device: BluetoothDevice,
    override val operationId: String = "${device.address}_sync_req_${System.currentTimeMillis()}",
    override val timeout: Long = 3000,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()

//Clock Sync reply
data class SyncResponse(
    override val device: BluetoothDevice,
    val t1: Long,
    override val operationId: String = "${device.address}_sync_rep_${System.currentTimeMillis()}",
    override val timeout: Long = 500,
    override val onComplete: ((Boolean, Int, String) -> Unit)? = null
) : BleOperation()
