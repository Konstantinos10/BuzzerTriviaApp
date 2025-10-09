package com.KonstPan.buzzerTrivia.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.KonstPan.buzzerTrivia.R
import com.KonstPan.buzzerTrivia.activities.PlayerGameActivity
import com.KonstPan.buzzerTrivia.viewModels.PeripheralViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.getValue

class PlayerConnectedFragment : Fragment() {

    // ViewModel - shared with activity
    private val peripheralViewModel: PeripheralViewModel by activityViewModels()

    private lateinit var textView: TextView
    private lateinit var button: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_player_connected, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        textView = view.findViewById(R.id.textView9)

        button = view.findViewById(R.id.button_buzz)
        button.isEnabled = false

        button.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                (activity as PlayerGameActivity).buzzHost(v)
                return@setOnTouchListener true
            }
            return@setOnTouchListener false
        }

        observeViewModel()
    }

    /**
     * Observes the CentralViewModel's StateFlow for changes in data
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // question
                launch {
                    peripheralViewModel.question.collectLatest {
                        if (it != null) {
                            textView.text = it.text
                            button.isEnabled = it.isEnabled
                        }
                        else {
                            button.isEnabled = false
                            textView.text = "waiting for host"
                        }
                    }
                }

            }
        }
    }

}
