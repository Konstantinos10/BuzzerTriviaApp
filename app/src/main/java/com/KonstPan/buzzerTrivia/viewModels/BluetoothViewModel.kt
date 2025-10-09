package com.KonstPan.buzzerTrivia.viewModels

import android.bluetooth.BluetoothAdapter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.KonstPan.buzzerTrivia.classes.BluetoothEvent
import com.KonstPan.buzzerTrivia.classes.BluetoothEventError
import com.KonstPan.buzzerTrivia.classes.BluetoothService
import com.KonstPan.buzzerTrivia.classes.BluetoothStateChangedToOff
import com.KonstPan.buzzerTrivia.classes.BluetoothStateChangedToOn
import com.KonstPan.buzzerTrivia.classes.BluetoothStateChangedToTurningOff
import com.KonstPan.buzzerTrivia.classes.BluetoothStateChangedToTurningOn
import com.KonstPan.buzzerTrivia.classes.BluetoothStateError
import com.KonstPan.buzzerTrivia.classes.BluetoothStateEvent
import com.KonstPan.buzzerTrivia.classes.GameEvent
import com.KonstPan.buzzerTrivia.classes.GameEventError
import com.KonstPan.buzzerTrivia.classes.GameService
import com.KonstPan.buzzerTrivia.classes.GameState
import com.KonstPan.buzzerTrivia.database.QuestionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

abstract class BluetoothViewModel(
    private val bluetoothService: BluetoothService,
    questionRepository: QuestionRepository? = null
) : ViewModel() {

    val gameService: GameService = GameService(questionRepository)

    protected val _gameState = MutableStateFlow(GameState.NONE)
    val gameState = _gameState.asStateFlow()

    private val validBluetoothStates = setOf(
        BluetoothAdapter.STATE_ON,
        BluetoothAdapter.STATE_TURNING_ON,
        BluetoothAdapter.STATE_OFF,
        BluetoothAdapter.STATE_TURNING_OFF
    )

    // Current bluetooth state provided by the BluetoothService
    val bluetoothState = bluetoothService.bluetoothEvents
        .filterIsInstance<BluetoothStateEvent>()
        .map { event ->
            when (event) {
                is BluetoothStateChangedToOff -> BluetoothAdapter.STATE_OFF
                is BluetoothStateChangedToOn -> BluetoothAdapter.STATE_ON
                is BluetoothStateChangedToTurningOff -> BluetoothAdapter.STATE_TURNING_OFF
                is BluetoothStateChangedToTurningOn -> BluetoothAdapter.STATE_TURNING_ON
                is BluetoothStateError -> BluetoothAdapter.ERROR
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue =
                if (bluetoothService.currentBluetoothState in validBluetoothStates) bluetoothService.currentBluetoothState
                else BluetoothAdapter.ERROR
        )

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            bluetoothService.bluetoothEvents.collect { event ->
                if (event is BluetoothEventError) _errorMessage.value = event.message
                collectBluetoothEvents(event)
            }
        }
        viewModelScope.launch {
            gameService.gameEvents.collect { event ->
                if (event is GameEventError) _errorMessage.value = event.message
                collectGameEvents(event)
            }
        }
    }

    abstract fun collectBluetoothEvents(event: BluetoothEvent)
    open fun collectGameEvents(event: GameEvent) {}

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

}