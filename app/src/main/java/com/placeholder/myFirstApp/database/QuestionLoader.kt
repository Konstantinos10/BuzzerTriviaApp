package com.placeholder.myFirstApp.database

import android.content.Context
import com.placeholder.myFirstApp.classes.Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object QuestionLoader {

    /**
     * Load questions from assets file
     */
    suspend fun loadQuestionsFromAssets(context: Context, fileName: String = "default_questions.txt"): List<Question> {
        return withContext(Dispatchers.IO) {
            try {
                val questions = mutableListOf<Question>()
                val inputStream = context.assets.open(fileName)
                val reader = BufferedReader(InputStreamReader(inputStream))

                reader.useLines { lines ->
                    for (line in lines) {
                        // ignore blank lines and comments
                        if (line.isBlank() || line.trim().startsWith("#")) continue

                        // parse each line into a question object
                        val parts = line.split("|")
                        if (parts.size >= 4) {
                            val question = Question(
                                text = parts[0].trim(),
                                startTime = parts[1].trim().toLongOrNull() ?: 5000,
                                endTime = parts[2].trim().toLongOrNull() ?: 10000,
                                points = parts[3].trim().toIntOrNull() ?: 10
                            )
                            questions.add(question)
                        }

                    }
                }
                questions
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}