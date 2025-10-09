package com.placeholder.myFirstApp.classes

// Sealed classes
sealed class GameEvent()

sealed class GameStateEvent(): GameEvent()
sealed class HostGameStateEvent(): GameStateEvent()
sealed class PlayerGameStateEvent(): GameStateEvent()

// Host events
data class RoundStarted(val round: GameService.GameRound): HostGameStateEvent()
data class RoundUpdated(val round: GameService.GameRound): HostGameStateEvent()
object RoundEnded: HostGameStateEvent()
data class PlayerBuzzedFirst(val player: PlayerDevice?): HostGameStateEvent()

// Player events

data class SetPlayerPoints(val player: String?, val points: Int): HostGameStateEvent()

// error events
data class GameEventError(val message: String) : GameEvent()