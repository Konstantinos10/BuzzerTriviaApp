package com.KonstPan.buzzerTrivia.classes

import android.util.Log
import com.KonstPan.buzzerTrivia.database.QuestionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.collections.get

class GameService(private val questionRepository: QuestionRepository? = null) {

    // --- Core class objects ---

    private val tag = "TriviaGameService"

    // Coroutine scope for emitting events
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Events to be observed by the ViewModel
    private val _gameEvents = MutableSharedFlow<GameEvent>()
    val gameEvents = _gameEvents.asSharedFlow()

    // --- game creation logic ---
    sealed class GameMode(){
        abstract var players: MutableMap<String, PlayerDevice> // List of players and their info
        abstract val numRounds: Int //Number of rounds in the game ( if num <= 0 game goes indefinitely)
    }
    data class GameModeNONE(override var players: MutableMap<String, PlayerDevice>, override val numRounds: Int = 0): GameMode() // No game, only buzzers
    data class GameModeFFA(override var players: MutableMap<String, PlayerDevice>, override val numRounds: Int = 10): GameMode() // Every player collects points for themselves
    data class GameModeTDM(override var players: MutableMap<String, PlayerDevice>, override val numRounds: Int, var teams: Int): GameMode() // Every player collects points for themselves

    data class GameRound(val roundNumber: Int,
                         var question: Question?,
                         val points: Int,
                         var isActive: Boolean = false
    ) // Round information

    private var game: GameMode? = null
    private var currentRound: GameRound? = null
    private var currentAnswer: Boolean? = null
    private val players: MutableMap<String, PlayerDevice> = mutableMapOf()
    private var previousQuestion: String? = null

    /**
     * Initialize a new game
     */
    fun createGame(game: GameMode){
        if (this.game != null) return
        this.game = game
        players.putAll(game.players)
    }

    /**
     * Clear all gameService activity
     */
    fun clearGameService(){
        endRound()
        game = null
        players.clear()
    }

    /**
     * Initialize a new round
     */
    fun startRound(newRound: GameRound = GameRound(0, null, 0, true)){
        if (currentRound != null) emitError("Round already started, overwriting");
        currentRound = newRound
        loadQuestion()
        emitEvent(RoundStarted(currentRound!!))
        Log.d(tag, "Round started")
    }

    /**
     * End the current round
     */
    fun endRound(isInvalid: Boolean = false) {
        if (currentRound == null) return
        setPlayerPoints()
        firstBuzzer = null
        currentAnswer = null
        currentRound = null
        playerBuzzes.clear()
        emitEvent(RoundEnded)
        Log.d(tag, "Round ended")
    }

    /**
     * Load a random question from the database
     */
    fun loadQuestion(){
        // if game mode is none, only send a basic message for a question
        if (game is GameModeNONE){
            currentRound?.question = Question(text = "press the button", startTime = 4000, endTime = 10000000, points = 0)
            return
        }
        // Load a question from the database
        serviceScope.launch {
            try {
                // Get random question from database
                var question: Question? = null
                while (question?.text == previousQuestion) question = questionRepository?.getRandomQuestion() // ensure new question is different from previous

                // Update the current round with the new question
                if (question != null) {
                    currentRound?.question = question
                    Log.d(tag, "Loaded question: ${question.text}")
                }
                else { // Fallback to a default question if the database unavailable or empty
                    currentRound?.question = Question(text = "press the button")
                    Log.w(tag, "No questions available in database, using default question")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to load question from database: ${e.message}")
                currentRound?.question = Question(text = "press the button") // Fallback to default question
            }

            // Send new data to the UI
            emitEvent(RoundUpdated(currentRound!!))
        }
    }

    fun evaluateAnswer(value: Boolean){
        currentAnswer = value
    }

    fun setPlayerPoints() {
        if (firstBuzzer == null || game is GameModeNONE) return
        players[firstBuzzer!!]!!.score += if (currentAnswer == true) 1 else 0
        emitEvent(SetPlayerPoints(players[firstBuzzer]!!.address, players[firstBuzzer!!]!!.score))
    }

    // --- game logic ---

    private val playerBuzzes = mutableMapOf<String, Long>()
    private var buzzJob: Job? = null
    private var firstBuzzer: String? = null

    /**
     * Receive a buzz from a player, buzzes are only accepted for a small window after the first one to account for potential delays
     */
    fun receiveBuzz(player: PlayerDevice, timestamp: Long, receivedHash: Int){
        // Confirm buzz validity
        if (game == null) { emitError("Game not initialized"); return }
        if (currentRound == null || currentRound!!.question == null) { emitError("Received buzz before round start"); return }
        if (!currentRound!!.isActive) { emitError("Received buzz at invalid time"); return }
        if (currentRound!!.question!!.text.hashCode() != receivedHash) { emitError("Received invalid buzz, expected ${currentRound!!.question.hashCode()}, got $receivedHash for question ${currentRound!!.question?.text}"); return }

        // only allow buzzes if none have been received and for a short period after the first one (while job != null)
        if (buzzJob == null && playerBuzzes.isNotEmpty()) return

        // save buzz and the player who did it (if it's their first one)
        if (!playerBuzzes.containsKey(player.address)) {
            players[player.address] = player
            playerBuzzes[player.address] = timestamp
        }

        // accept buzzes for a short period of time after the first one and deactivate the round and buzzJob afterwards
        buzzJob = serviceScope.launch {
            Log.d(tag, "receive buzzes")
            delay(500)
            evaluateBuzzes()
            currentRound!!.isActive = false
            buzzJob = null
        }
    }

    /**
     * Find the player that buzzed first and send them to the UI
     */
    private fun evaluateBuzzes(){
        if (playerBuzzes.isEmpty()) { emitError("No buzzes received"); return }
        firstBuzzer = playerBuzzes.minByOrNull { it.value }!!.key
        emitEvent(PlayerBuzzedFirst(players[firstBuzzer]!!))
        Log.d(tag, "First buzzer: ${players[firstBuzzer]!!}")
    }


    // --- Event Emission ---
    private fun emitError(message: String) = emitEvent(GameEventError(message))

    private fun emitEvent(event: GameEvent) {
        // Emit the event to the shared flow
        serviceScope.launch { _gameEvents.emit(event) }

        // Log the event for debugging
        if (event is GameEventError) Log.w(tag, "Event: ${event.message}")
    }

}