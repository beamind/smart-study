package com.smartstudy.english

import com.smartstudy.english.EnglishErrorBookStorage.QuestionStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EnglishErrorBookSamplingTest {
    @Test
    fun `selectQuestionsByErrorRate returns unique questions and respects count`() {
        val stats =
            listOf(
                QuestionStat("Q1", errorCount = 1, totalCount = 1, errorRate = 1.0, lastPracticeTimestamp = 0L),
                QuestionStat("Q2", errorCount = 0, totalCount = 10, errorRate = 0.0, lastPracticeTimestamp = 0L),
                QuestionStat("Q3", errorCount = 5, totalCount = 10, errorRate = 0.5, lastPracticeTimestamp = 0L),
            )

        val selected =
            EnglishErrorBookSampling.selectQuestionsByErrorRate(
                questionStats = stats,
                count = 2,
                random = Random(123),
                nowMillis = 0L,
            )

        assertEquals(2, selected.size)
        assertEquals(2, selected.toSet().size)
        assertTrue(selected.all { q -> stats.any { it.question == q } })
    }

    @Test
    fun `selectQuestionsByErrorRate always picks errorRate 1_0 when count is 1`() {
        val stats =
            listOf(
                QuestionStat("Always", errorCount = 1, totalCount = 1, errorRate = 1.0, lastPracticeTimestamp = 0L),
                QuestionStat("Never", errorCount = 0, totalCount = 100, errorRate = 0.0, lastPracticeTimestamp = 0L),
            )

        repeat(10) { i ->
            val selected =
                EnglishErrorBookSampling.selectQuestionsByErrorRate(
                    questionStats = stats,
                    count = 1,
                    random = Random(i.toLong()),
                    nowMillis = 0L,
                )
            assertEquals(listOf("Always"), selected)
        }
    }
}
