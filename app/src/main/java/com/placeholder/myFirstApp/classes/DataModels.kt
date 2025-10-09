package com.placeholder.myFirstApp.classes

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "questions")
data class Question(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    /**
     * If writing from the host, the amount of time in ms from the moment the question is queued until it should be revealed to the player.
     * If reading from the player, the moment the question is revealed
     */
    val startTime: Long = 5000,

    /**
     * If writing from the host, the amount of time in ms from the moment the question is revealed to the player until it expires.
     * If reading from the player, the moment the question expires
     */
    val endTime: Long = 10000,
    
    val points: Int = 10
)

data class PlayerDevice(
    val address: String,
    val name: String,
    var isConnected: Boolean = false,
    var score: Int = 0,
    var status: String = "Disconnected"
)

enum class ConnectionState
    {SCANNING, CONNECTED, CONNECTING, DISCONNECTING, // host states
    ADVERTISING, CONNECTED_TO_HOST, CONNECTING_TO_HOST, DISCONNECTING_FROM_HOST, // player states
    DISCONNECTED, DISCONNECTED_BT_OFF // common states
    }

enum class GameState{
    // host states
    NONE,
    WAITING_FOR_ROUND,
    WAITING_FOR_BUZZ
}