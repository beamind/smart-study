package com.smartstudy.english

import com.smartstudy.core.AppConfig
import java.util.Calendar
import java.util.Random
import kotlin.random.asKotlinRandom

/**
 * 英语错题本采样工具对象
 *
 * 提供基于错误率和时间间隔的智能采样功能。
 */
object EnglishErrorBookSampling {
    /**
     * 根据错误率与"距离上次练习天数"采样题目。
     *
     * 采样概率 = min(max(错误率, daysDiff * 0.01), 1.0)
     */
    fun selectQuestionsByErrorRate(
        questionStats: List<EnglishErrorBookStorage.QuestionStat>,
        count: Int,
        random: Random = Random(),
        nowMillis: Long = System.currentTimeMillis(),
    ): List<String> {
        if (questionStats.isEmpty() || count <= 0) return emptyList()

        val selectedQuestions = mutableSetOf<String>()
        val todayMillis = getTodayMillis(nowMillis)

        val maxAttempts = count * 20
        selectQuestionsBySampling(questionStats, selectedQuestions, count, random, todayMillis, maxAttempts)

        // 如果选择不足，从剩余题目中随机补齐
        fillRemainingQuestions(questionStats, selectedQuestions, count, random)

        return selectedQuestions.toList()
    }

    /**
     * 获取今天的开始时间（毫秒）
     */
    private fun getTodayMillis(nowMillis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 通过采样选择题目
     */
    private fun selectQuestionsBySampling(
        questionStats: List<EnglishErrorBookStorage.QuestionStat>,
        selectedQuestions: MutableSet<String>,
        count: Int,
        random: Random,
        todayMillis: Long,
        maxAttempts: Int,
    ) {
        var attempts = 0
        while (selectedQuestions.size < count && attempts < maxAttempts) {
            attempts++
            for (stat in questionStats) {
                if (selectedQuestions.size >= count) break

                // 只处理未选中的题目
                if (!selectedQuestions.contains(stat.question)) {
                    val samplingProbability = calculateSamplingProbability(stat, todayMillis)
                    if (random.nextDouble() < samplingProbability) {
                        selectedQuestions.add(stat.question)
                    }
                }
            }
        }
    }

    /**
     * 计算采样概率
     */
    private fun calculateSamplingProbability(
        stat: EnglishErrorBookStorage.QuestionStat,
        todayMillis: Long,
    ): Double {
        val lastPracticeDate = getLastPracticeDate(stat, todayMillis)
        val daysDiff = ((todayMillis - lastPracticeDate.timeInMillis) / AppConfig.MILLIS_PER_DAY).toInt()
        val dateProbability = daysDiff * 0.01
        return maxOf(stat.errorRate, dateProbability).coerceIn(0.0, 1.0)
    }

    /**
     * 获取上次练习日期
     */
    private fun getLastPracticeDate(
        stat: EnglishErrorBookStorage.QuestionStat,
        todayMillis: Long,
    ): Calendar {
        val timestamp = if (stat.lastPracticeTimestamp > 0) {
            stat.lastPracticeTimestamp
        } else {
            todayMillis
        }
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    /**
     * 从剩余题目中随机补齐
     */
    private fun fillRemainingQuestions(
        questionStats: List<EnglishErrorBookStorage.QuestionStat>,
        selectedQuestions: MutableSet<String>,
        count: Int,
        random: Random,
    ) {
        if (selectedQuestions.size < count) {
            val remaining = questionStats.filter { !selectedQuestions.contains(it.question) }
            if (remaining.isNotEmpty()) {
                val needMore = count - selectedQuestions.size
                selectedQuestions.addAll(remaining.shuffled(random.asKotlinRandom()).take(needMore).map { it.question })
            }
        }
    }

    /**
     * 基于错题统计（含答案）采样题目。
     */
    fun selectQuestionAnswersByErrorRate(
        questionAnswersWithStats: List<Pair<QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>>,
        count: Int,
        random: Random = Random(),
        nowMillis: Long = System.currentTimeMillis(),
    ): List<QuestionAnswer> {
        if (questionAnswersWithStats.isEmpty() || count <= 0) return emptyList()

        val questionStats =
            questionAnswersWithStats.map { (qa, stats) ->
                val errorRate =
                    if (stats.totalCount > 0) {
                        stats.errorCount.toDouble() / stats.totalCount.toDouble()
                    } else {
                        0.0
                    }
                EnglishErrorBookStorage.QuestionStat(
                    qa.question,
                    stats.errorCount,
                    stats.totalCount,
                    errorRate,
                    System.currentTimeMillis(),
                )
            }

        val selectedQuestions = selectQuestionsByErrorRate(questionStats, count, random = random, nowMillis = nowMillis)
        val selectedSet = selectedQuestions.toSet()

        return questionAnswersWithStats
            .filter { (qa, _) -> selectedSet.contains(qa.question) }
            .map { it.first }
    }
}
