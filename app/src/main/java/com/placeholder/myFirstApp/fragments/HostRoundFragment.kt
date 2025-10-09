package com.placeholder.myFirstApp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.placeholder.myFirstApp.R
import com.placeholder.myFirstApp.objects.MyToast
import com.placeholder.myFirstApp.viewModels.CentralViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HostRoundFragment : Fragment() {

    // ViewModel - shared with activity
    private val centralViewModel: CentralViewModel by activityViewModels()

    // Views
    private lateinit var questionText: TextView
    private lateinit var buzzerText: TextView
    private lateinit var buttonQuestion: Button
    private lateinit var buttonCorrect: Button
    private lateinit var buttonWrong: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_host_round, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        questionText = view.findViewById(R.id.questionTextView)
        buzzerText = view.findViewById(R.id.buzzerTextView)
        buttonQuestion = view.findViewById(R.id.buttonQuestion)
        buttonCorrect = view.findViewById(R.id.buttonCorrect)
        buttonWrong = view.findViewById(R.id.buttonWrong)

        // Set default values
        questionText.text = "press GO to send a question"
        buzzerText.text = ""

        // Disable buttons until a buzz comes
        setButtonsEnabled(false)

        // Set up button onClicks
        buttonQuestion.setOnClickListener { sendQuestion(it) }
        buttonCorrect.setOnClickListener { buzzCorrect(it) }
        buttonWrong.setOnClickListener { buzzWrong(it) }

        // Observe ViewModel
        observeViewModel()
    }

    /**
     * Enable or disable the "answer" buttons
     */
    private fun setButtonsEnabled(isEnabled: Boolean){
        buttonCorrect.isEnabled = isEnabled
        buttonWrong.isEnabled = isEnabled
    }

    /**
     * Observes the CentralViewModel's StateFlow for changes in data
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // device connection updates
                launch {
                    centralViewModel.firstPlayerBuzz.collect {
                        // enable buttons after the first buzzer has been decided
                        if (it != null) {
                            buzzerText.text = it
                            setButtonsEnabled(true)
                        }
                    }
                }

                // question
                launch {
                    centralViewModel.question.collectLatest {
                        questionText.text = it?.text?: "loading question"
                    }
                }

                // timer
                launch {
                    centralViewModel.questionTimer.collectLatest {
                        buttonQuestion.text = it?: "GO"
                    }
                }

                // available players
                launch {
                    centralViewModel.availablePlayers.collectLatest { it ->
                        // make sure at least one player is connected at all times, otherwise end and invalidate the round
                        if (it.none { it.isConnected }) {
                            MyToast.showText(requireContext(), "All players disconnected")
                            endRound(0, true)
                        }
                    }
                }
            }
        }
    }

    fun sendQuestion(view: View) {
        buttonQuestion.isEnabled = false
        buzzerText.text = "waiting for buzz"
        centralViewModel.sendQuestion()
    }

    fun buzzCorrect(view: View) {
        questionText.text = "correct!"
        //setButtonsEnabled(false)
        setAnswer(true)
        endRound()
    }

    fun buzzWrong(view: View){
        questionText.text = "wrong!"
        //setButtonsEnabled(false)
        setAnswer(false)
        endRound()
    }

    fun setAnswer(value: Boolean) = centralViewModel.setAnswer(value)
    fun endRound(delayMillis: Long = 1000, isInvalid: Boolean = false) = centralViewModel.endRound(delayMillis, isInvalid)

}
