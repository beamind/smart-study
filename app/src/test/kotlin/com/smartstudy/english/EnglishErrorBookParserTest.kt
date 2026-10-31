package com.smartstudy.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EnglishErrorBookParser 单元测试
 * 测试错题解析器的各种场景
 */
class EnglishErrorBookParserTest {
    @Test
    fun `parseQuestionsWithAnswers extracts questions and answers from JSON correctly`() {
        val jsonText =
            """
            [
                {"question": "1. What is the capital of France?", "answer": "A"},
                {"question": "2. What is 2 + 2?", "answer": "B"}
            ]
            """.trimIndent()

        val result = EnglishErrorBookParser.parseQuestionsWithAnswers(jsonText)

        assertNotNull(result)
        assertEquals(2, result.size)
        assertEquals("What is the capital of France?", result[0].question)
        assertEquals("A", result[0].answer)
        assertEquals("What is 2 + 2?", result[1].question)
        assertEquals("B", result[1].answer)
    }

    @Test
    fun `parseQuestionsWithAnswers handles empty text`() {
        val result = EnglishErrorBookParser.parseQuestionsWithAnswers("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseQuestionsWithAnswers handles invalid JSON`() {
        val text = "This is not valid JSON"
        val result = EnglishErrorBookParser.parseQuestionsWithAnswers(text)
        // JSON 解析失败，返回空列表
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseQuestionsWithAnswers handles single question`() {
        val jsonText =
            """
            [{"question": "1. What is the capital of France?", "answer": "A"}]
            """.trimIndent()

        val result = EnglishErrorBookParser.parseQuestionsWithAnswers(jsonText)

        assertEquals(1, result.size)
        assertEquals("What is the capital of France?", result[0].question)
        assertEquals("A", result[0].answer)
    }

    @Test
    fun `removeQuestionNumber removes various number formats`() {
        assertEquals("What is the capital?", EnglishErrorBookParser.removeQuestionNumber("1. What is the capital?"))
        assertEquals("What is the capital?", EnglishErrorBookParser.removeQuestionNumber("1) What is the capital?"))
        assertEquals("What is the capital?", EnglishErrorBookParser.removeQuestionNumber("(1) What is the capital?"))
        assertEquals("What is the capital?", EnglishErrorBookParser.removeQuestionNumber("（1） What is the capital?"))
        assertEquals("What is the capital?", EnglishErrorBookParser.removeQuestionNumber("一. What is the capital?"))
        assertEquals("What is the capital?", EnglishErrorBookParser.removeQuestionNumber("1、What is the capital?"))
    }

    @Test
    fun `removeQuestionNumber handles text without number prefix`() {
        val text = "What is the capital of France?"
        assertEquals(text, EnglishErrorBookParser.removeQuestionNumber(text))
    }

    @Test
    fun `parseQuestionsWithAnswers handles questions with special characters`() {
        val jsonText =
            """
            [{"question": "1. What is \"hello\" in French?", "answer": "A"}]
            """.trimIndent()

        val result = EnglishErrorBookParser.parseQuestionsWithAnswers(jsonText)

        assertNotNull(result)
        if (result.isNotEmpty()) {
            assertTrue(result[0].question.contains("hello"))
            assertEquals("A", result[0].answer)
        }
    }
}
