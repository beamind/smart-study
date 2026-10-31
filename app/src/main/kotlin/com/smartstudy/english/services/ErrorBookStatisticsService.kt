package com.smartstudy.english.services

import com.smartstudy.core.Logger
import com.smartstudy.english.EnglishErrorBookStorage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 错题本统计服务
 *
 * 负责计算错题本相关的统计数据，将业务逻辑从存储层分离出来。
 *
 * 功能特性：
 * - 计算题目统计（错误次数、总次数、错误率）
 * - 计算最后练习时间戳
 *
 * 使用示例：
 * ```
 * val service = ErrorBookStatisticsService()
 * val stats = service.calculateStats(json, userName)
 * ```
 */
class ErrorBookStatisticsService {
    companion object {
        private const val TAG = "ErrorBookStatisticsService"
        private const val SCHEMA_VERSION_KEY = "schemaVersion"
    }

    // 日期格式化器
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 临时数据类，用于存储排序所需的信息
     */
    private data class QuestionData(
        val questionAnswer: com.smartstudy.english.QuestionAnswer,
        val stats: EnglishErrorBookStorage.QuestionWithStats,
        val errorRate: Double,
        val lastPracticeTimestamp: Long,
    )

    /**
     * 计算题目统计信息
     * @param json JSON对象，包含所有题目记录
     * @return 题目统计信息列表
     */
    fun calculateQuestionStats(json: JSONObject): List<EnglishErrorBookStorage.QuestionWithStats> {
        val questions = mutableListOf<EnglishErrorBookStorage.QuestionWithStats>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val question = keys.next()
            if (question != SCHEMA_VERSION_KEY) {
                val records = json.optJSONArray(question)
                if (records != null) {
                    val stats = calculateSingleQuestionStats(question, records)
                    questions.add(stats)
                }
            }
        }

        Logger.d(TAG, "计算了 ${questions.size} 道题目的统计信息")
        return questions
    }

    /**
     * 计算单个题目的统计信息
     */
    private fun calculateSingleQuestionStats(
        question: String,
        records: org.json.JSONArray,
    ): EnglishErrorBookStorage.QuestionWithStats {
        val totalCount = records.length()
        val errorCount = countErrors(records, totalCount)

        return EnglishErrorBookStorage.QuestionWithStats(question, errorCount, totalCount)
    }

    /**
     * 统计错误次数
     */
    private fun countErrors(records: org.json.JSONArray, totalCount: Int): Int {
        var errorCount = 0

        for (i in 0 until totalCount) {
            val record = records.getJSONObject(i)
            val status = record.optString("status", "")
            if (status == "错误") {
                errorCount++
            }
        }

        return errorCount
    }

    /**
     * 计算题目完整统计信息（用于采样）
     * @param json JSON对象，包含所有题目记录
     * @return 题目完整统计信息列表
     */
    fun calculateQuestionFullStats(json: JSONObject): List<EnglishErrorBookStorage.QuestionStat> {
        val questions = processAllQuestionsForFullStats(json)
        val sortedQuestions = sortQuestionsByRecentPractice(questions)

        Logger.d(TAG, "计算了 ${sortedQuestions.size} 道题目的完整统计信息")
        return sortedQuestions
    }

    /**
     * 处理所有题目，计算完整统计信息
     */
    private fun processAllQuestionsForFullStats(json: JSONObject): MutableList<EnglishErrorBookStorage.QuestionStat> {
        val questions = mutableListOf<EnglishErrorBookStorage.QuestionStat>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val question = keys.next()
            if (question != SCHEMA_VERSION_KEY) {
                val records = json.optJSONArray(question)
                if (records != null) {
                    val questionStat = calculateSingleQuestionFullStats(question, records)
                    questions.add(questionStat)
                }
            }
        }

        return questions
    }

    /**
     * 计算单个题目的完整统计信息
     */
    private fun calculateSingleQuestionFullStats(
        question: String,
        records: org.json.JSONArray,
    ): EnglishErrorBookStorage.QuestionStat {
        val totalCount = records.length()
        val (errorCount, lastPracticeTimestamp) = calculateErrorCountAndTimestamp(records, totalCount)
        val errorRate = calculateErrorRate(errorCount, totalCount)

        return EnglishErrorBookStorage.QuestionStat(
            question,
            errorCount,
            totalCount,
            errorRate,
            lastPracticeTimestamp,
        )
    }

    /**
     * 按最近练习时间排序题目
     */
    private fun sortQuestionsByRecentPractice(
        questions: MutableList<EnglishErrorBookStorage.QuestionStat>,
    ): List<EnglishErrorBookStorage.QuestionStat> {
        val currentTime = System.currentTimeMillis()
        val oneHourAgo = currentTime - 60 * 60 * 1000L // 1小时

        return questions.sortedWith(
            compareBy<EnglishErrorBookStorage.QuestionStat> { stat ->
                stat.lastPracticeTimestamp >= oneHourAgo
            }.thenByDescending { stat ->
                stat.errorRate
            },
        )
    }

    /**
     * 从JSON中提取题目和答案，并计算统计信息
     * @param json JSON对象，包含所有题目记录
     * @return 题目和答案对列表（带统计信息），按错误率从高到低排序，相同错误率按时间从近到远排序
     */
    fun calculateQuestionAnswersWithStats(
        json: JSONObject,
    ): List<Pair<com.smartstudy.english.QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>> {
        val questionDataList = processAllQuestions(json)

        val sortedQuestionDataList = sortQuestionDataList(questionDataList)

        // 转换为返回格式
        val result = sortedQuestionDataList.map { it.questionAnswer to it.stats }

        Logger.d(TAG, "计算了 ${result.size} 道错题（带答案）的统计信息，已按最近保存优先、错误率从高到低、时间从近到远排序")
        return result
    }

    /**
     * 处理所有题目，提取答案和统计信息
     */
    private fun processAllQuestions(json: JSONObject): MutableList<QuestionData> {
        val questionDataList = mutableListOf<QuestionData>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val question = keys.next()
            if (question != SCHEMA_VERSION_KEY) {
                val records = json.optJSONArray(question)
                if (records != null) {
                    val questionData = processQuestionRecords(question, records)
                    questionDataList.add(questionData)
                }
            }
        }

        return questionDataList
    }

    /**
     * 处理单个题目的记录
     */
    private fun processQuestionRecords(
        question: String,
        records: org.json.JSONArray,
    ): QuestionData {
        val totalCount = records.length()
        val (answer, isAiGenerated) = extractAnswerAndAiFlag(records, totalCount)
        val (errorCount, lastPracticeTimestamp) = calculateErrorCountAndTimestamp(records, totalCount)
        val errorRate = calculateErrorRate(errorCount, totalCount)

        val questionAnswer =
            com.smartstudy.english.QuestionAnswer(
                question,
                answer,
                isCorrect = true,
                isAiGenerated = isAiGenerated,
            )
        val stats = EnglishErrorBookStorage.QuestionWithStats(question, errorCount, totalCount)

        return QuestionData(
            questionAnswer = questionAnswer,
            stats = stats,
            errorRate = errorRate,
            lastPracticeTimestamp = lastPracticeTimestamp,
        )
    }

    /**
     * 从记录中提取答案和AI生成标记
     */
    private fun extractAnswerAndAiFlag(
        records: org.json.JSONArray,
        totalCount: Int,
    ): Pair<String, Boolean> {
        var answer = ""
        var isAiGenerated = false

        if (totalCount > 0) {
            for (i in totalCount - 1 downTo 0) {
                val record = records.getJSONObject(i)
                val answerStr = record.optString("answer", "")
                if (answerStr.isNotEmpty() && answer.isEmpty()) {
                    answer = answerStr
                }
                if (record.has("isAiGenerated")) {
                    isAiGenerated = record.optBoolean("isAiGenerated", false)
                }
                if (answer.isNotEmpty() && record.has("isAiGenerated")) {
                    break
                }
            }
        }

        return answer to isAiGenerated
    }

    /**
     * 计算错误次数和最后练习时间戳
     */
    private fun calculateErrorCountAndTimestamp(
        records: org.json.JSONArray,
        totalCount: Int,
    ): Pair<Int, Long> {
        var errorCount = 0
        var lastPracticeTimestamp = 0L

        for (i in 0 until totalCount) {
            val record = records.getJSONObject(i)
            val status = record.optString("status", "")
            if (status == "错误") {
                errorCount++
            }

            val dateStr = record.optString("date", "")
            if (dateStr.isNotEmpty()) {
                val timestamp = parseDateString(dateStr)
                if (timestamp > lastPracticeTimestamp) {
                    lastPracticeTimestamp = timestamp
                }
            }
        }

        return errorCount to lastPracticeTimestamp
    }

    /**
     * 解析日期字符串为时间戳
     */
    private fun parseDateString(dateStr: String): Long {
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: java.text.ParseException) {
            Logger.w(TAG, "日期格式错误，解析日期失败: $dateStr", e)
            0L
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "参数错误，解析日期失败: $dateStr", e)
            0L
        }
    }

    /**
     * 计算错误率
     */
    private fun calculateErrorRate(errorCount: Int, totalCount: Int): Double {
        return if (totalCount > 0) {
            errorCount.toDouble() / totalCount.toDouble()
        } else {
            0.0
        }
    }

    /**
     * 对题目数据进行排序
     */
    private fun sortQuestionDataList(questionDataList: MutableList<QuestionData>): List<QuestionData> {
        val currentTime = System.currentTimeMillis()
        val recentSaveThreshold = 5 * 60 * 1000L // 5分钟

        return questionDataList.sortedWith(
            compareByDescending<QuestionData> { questionData ->
                val isRecentlySaved = (currentTime - questionData.lastPracticeTimestamp) <= recentSaveThreshold
                isRecentlySaved
            }.thenByDescending { questionData ->
                questionData.errorRate
            }.thenByDescending { questionData ->
                questionData.lastPracticeTimestamp
            },
        )
    }
}
