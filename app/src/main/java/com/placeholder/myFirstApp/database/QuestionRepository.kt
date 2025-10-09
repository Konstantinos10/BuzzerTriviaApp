package com.placeholder.myFirstApp.database

import com.placeholder.myFirstApp.classes.Question

class QuestionRepository(private val questionDao: QuestionDao) {

    /**
     * Get a random question from the database
     */
    suspend fun getRandomQuestion(): Question? = questionDao.getRandomQuestion()

    /**
     * Insert a list of questions into the database
     */
    suspend fun insertQuestions(questions: List<Question>) = questionDao.insertQuestions(questions)

    /**
     * Delete all questions from the database
     */
    suspend fun deleteAllQuestions() = questionDao.deleteAllQuestions()

}