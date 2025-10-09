package com.placeholder.myFirstApp.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.placeholder.myFirstApp.MyApplication
import com.placeholder.myFirstApp.classes.Question
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [Question::class], version = 1, exportSchema = false)
abstract class QuestionDatabase : RoomDatabase() {
    
    abstract fun questionDao(): QuestionDao

    companion object {
        @Volatile private var INSTANCE: QuestionDatabase? = null

        /**
         * Get the database instance
         */
        fun getDatabase(context: Context): QuestionDatabase =
            INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext as MyApplication
                val instance = Room.databaseBuilder(appContext, QuestionDatabase::class.java, "question_database")
                    .addCallback(QuestionDatabaseCallback(appContext))
                    .build()
                INSTANCE = instance
                instance
            }

        /**
         * Callback to prepopulate the database with default questions
         */
        private class QuestionDatabaseCallback(private val context: Context) : Callback() {

            /**
             * Populate the database with default questions when the database is created
             */
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateDatabase(database.questionDao(), context)
                    }
                }
            }
        }

        private suspend fun populateDatabase(questionDao: QuestionDao, context: Context) {
            // Clear any existing data
            questionDao.deleteAllQuestions()

            // Try to load questions from assets file first
            val questionsFromFile = QuestionLoader.loadQuestionsFromAssets(context)

            // Add questions to the database
            if (questionsFromFile.isNotEmpty()) questionDao.insertQuestions(questionsFromFile)
            else questionDao.insertQuestions(defaultQuestions) // Fallback to default questions if file loading fails
        }

        private val defaultQuestions: List<Question> = listOf(
            Question(
                text = "What is the capital of France?",
                startTime = 5000,
                endTime = 15000,
                points = 10
            ),
            Question(
                text = "What is 2 + 2?",
                startTime = 5000,
                endTime = 8000,
                points = 5
            ),
            Question(
                text = "Which planet is known as the Red Planet?",
                startTime = 5000,
                endTime = 12000,
                points = 10
            ),
            Question(
                text = "Who painted the Mona Lisa?",
                startTime = 5000,
                endTime = 15000,
                points = 15
            ),
            Question(
                text = "What is the largest ocean on Earth?",
                startTime = 5000,
                endTime = 12000,
                points = 10
            ),
            Question(
                text = "In which year did World War II end?",
                startTime = 5000,
                endTime = 15000,
                points = 15
            ),
            Question(
                text = "What is the chemical symbol for gold?",
                startTime = 5000,
                endTime = 10000,
                points = 10
            ),
            Question(
                text = "Which is the smallest country in the world?",
                startTime = 5000,
                endTime = 12000,
                points = 10
            ),
            Question(
                text = "What is the fastest land animal?",
                startTime = 5000,
                endTime = 10000,
                points = 10
            ),
            Question(
                text = "How many continents are there?",
                startTime = 5000,
                endTime = 8000,
                points = 5
            )
        )
    }
}
