package com.KonstPan.buzzerTrivia

import android.app.Application
import com.KonstPan.buzzerTrivia.classes.BluetoothService
import com.KonstPan.buzzerTrivia.database.QuestionDatabase
import com.KonstPan.buzzerTrivia.database.QuestionRepository

class MyApplication : Application() {
    private val database by lazy { QuestionDatabase.getDatabase(this) }
    val questionRepository by lazy { QuestionRepository(database.questionDao()) }
    val bluetoothService: BluetoothService by lazy { BluetoothService(this) }

    override fun onCreate() {
        super.onCreate()
        bluetoothService.registerBluetoothReceiver()
    }

    override fun onTerminate() {
        super.onTerminate()
        bluetoothService.unregisterBluetoothReceiver()
    }
}