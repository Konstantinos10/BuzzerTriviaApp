package com.placeholder.myFirstApp

import android.app.Application
import com.placeholder.myFirstApp.classes.BluetoothService
import com.placeholder.myFirstApp.database.QuestionDatabase
import com.placeholder.myFirstApp.database.QuestionRepository

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