package com.placeholder.myFirstApp.objects

import java.util.UUID

object MyUUIDs {
    val HEARTBEAT: UUID = UUID.fromString("7063a017-80af-43a5-a16c-dc17f6ead150")
    val HEARTBEAT_CCCD: UUID = UUID.fromString("86c7c325-66c8-4e04-8695-e8d628300bd6")
    val BUZZER: UUID = UUID.fromString("949cfdcb-38d6-428b-a194-1ad4be9b93d3")
    val BUZZER_CCCD: UUID = UUID.fromString("159666df-c889-4dd7-9f85-596a0307a694")
    val SYNCHRONIZATION: UUID = UUID.fromString("936478a6-f7b0-4e66-bfab-d483ba2276f3")
    val SYNCHRONIZATION_CCCD: UUID = UUID.fromString("f88831ea-df2b-433d-8bc7-ba26ceddb444")
    val GAME_STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("1d76d961-8ed1-4d47-b408-f483ce9ff3ae")
    val QUESTION_UUID: UUID = UUID.fromString("677eb45a-935c-4e02-b543-3a3ac6b82852")
    val TRIVIA_GAME_SERVICE: UUID = UUID.fromString("28736e1d-f08a-4c94-b0e1-9e759e353c20")
}

object MyBluetoothStatusCodes {
    const val DEVICE_ALREADY_CONNECTED = -2
    const val SYNC_RESPONSE_PARSE_ERROR = -3
}