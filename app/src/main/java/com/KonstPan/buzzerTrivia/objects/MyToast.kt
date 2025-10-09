package com.KonstPan.buzzerTrivia.objects

import android.content.Context
import android.widget.Toast

/**
 * Custom Toast wrapper that overrides previous messages instead of queueing them
 * */
object MyToast {
    private var toast: Toast? = null

    fun showText(context: Context, text: String, duration: Int = Toast.LENGTH_SHORT) {
        toast?.cancel()
        toast = Toast.makeText(context, text, duration)
        toast?.show()
    }
}