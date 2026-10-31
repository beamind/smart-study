package com.smartstudy.learningrecord.repository

import android.content.Context
import com.smartstudy.core.AppConfig
import com.smartstudy.core.AppResult
import com.smartstudy.core.repository.BaseRepository
import com.smartstudy.core.repository.executeDataOperation
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.repository.DictationRepository
import com.smartstudy.english.EnglishErrorBookStorage
import com.smartstudy.english.repository.ErrorBookRepository
import com.smartstudy.learningrecord.LearningRecord
import com.smartstudy.learningrecord.LearningRecordGroup
import com.smartstudy.learningrecord.LearningRecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 学习记录数据仓库
 *
 * 统一管理所有学习记录的数据访问，整合听写和错题本数据源。
 *
 * 功能特性：
 * - 统一错误处理：所有方法返回 AppResult
 * - 线程安全：自动在 IO 线程执行数据操作
 * - 数据整合：从多个数据源获取数据并统一格式
 * - 按类型分组：自动按学习类型分组展示
 *
 * @param context Android Context
 * @param dictationRepository 听写数据仓库
 * @param errorBookRepository 错题本数据仓库
 */
class LearningRecordRepository(
    private val context: Context,
    private val dictationRepository: DictationRepository,
    private val errorBookRepository: ErrorBookRepository,
) : BaseRepository {
    override val tag: String = "LearningRecordRepository"

    private val dictationStorage: DictationRecordStorage by lazy {
        DictationRecordStorage(context)
    }

    private val errorBookStorage: EnglishErrorBookStorage by lazy {
        EnglishErrorBookStorage(context)
    }

    // 日期格式化器
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 按日期范围加载所有学习记录（按类型分组）
     *
     * @param userName 用户名
     * @param startDate 开始日期（时间戳，毫秒）
     * @param endDate 结束日期（时间戳，毫秒）
     * @return 按类型分组的学习记录列表
     */
    suspend fun loadLearningRecordsByDateRange(
        userName: String,
        startDate: Long,
        endDate: Long,
    ): AppResult<List<LearningRecordGroup>> =
        executeDataOperation("按日期范围加载学习记录") {
            withContext(Dispatchers.IO) {
                // 并行加载三种类型的数据
                val chineseDictationDeferred =
                    async {
                        loadDictationRecords(userName, AppConfig.TYPE_CHINESE, startDate, endDate)
                    }
                val englishDictationDeferred =
                    async {
                        loadDictationRecords(userName, AppConfig.TYPE_ENGLISH, startDate, endDate)
                    }
                val errorBookDeferred =
                    async {
                        loadErrorBookRecords(userName, startDate, endDate)
                    }

                // 等待所有数据加载完成
                val chineseRecords = chineseDictationDeferred.await()
                val englishRecords = englishDictationDeferred.await()
                val errorBookRecords = errorBookDeferred.await()

                // 按类型分组
                val groups = mutableListOf<LearningRecordGroup>()

                if (chineseRecords.isNotEmpty()) {
                    groups.add(
                        LearningRecordGroup(
                            type = LearningRecordType.CHINESE_DICTATION,
                            records = chineseRecords,
                        ),
                    )
                }

                if (englishRecords.isNotEmpty()) {
                    groups.add(
                        LearningRecordGroup(
                            type = LearningRecordType.ENGLISH_DICTATION,
                            records = englishRecords,
                        ),
                    )
                }

                if (errorBookRecords.isNotEmpty()) {
                    groups.add(
                        LearningRecordGroup(
                            type = LearningRecordType.ERROR_BOOK,
                            records = errorBookRecords,
                        ),
                    )
                }

                groups
            }
        }

    /**
     * 加载听写记录
     */
    private suspend fun loadDictationRecords(
        userName: String,
        dictationType: String,
        startDate: Long,
        endDate: Long,
    ): List<LearningRecord> {
        // 获取日期范围内的记录（用于显示列表）
        val dateRangeRecords = dictationStorage.getRecordsByDateRange(userName, dictationType, startDate, endDate)

        // 获取所有历史记录（用于计算错误次数和总次数）
        val allRecords = dictationStorage.getAllRecords(userName, dictationType)

        // 按词语分组，统计每个词语的信息（基于日期范围内的记录）
        val dateRangeWordMap = mutableMapOf<String, MutableList<QuestionRecord>>()
        dateRangeRecords.forEach { record ->
            dateRangeWordMap.getOrPut(record.word) { mutableListOf() }.add(record)
        }

        // 按词语分组，统计所有历史记录（用于计算错误次数和总次数）
        val allWordMap = mutableMapOf<String, MutableList<QuestionRecord>>()
        allRecords.forEach { record ->
            allWordMap.getOrPut(record.word) { mutableListOf() }.add(record)
        }

        // 转换为LearningRecord
        // 只显示日期范围内出现的词语，但错误次数和总次数使用全部历史数据
        return dateRangeWordMap.map { (word, wordRecords) ->
            // 使用日期范围内的最新记录来确定显示信息
            val latestRecord = wordRecords.maxByOrNull { it.timestamp } ?: wordRecords.first()
            val translation = latestRecord.chineseTranslation

            // 使用全部历史记录计算错误次数和总次数
            val allWordRecords = allWordMap[word] ?: emptyList()
            val errorCount = allWordRecords.count { !it.isCorrect }
            val totalCount = allWordRecords.size

            LearningRecord(
                type =
                    if (dictationType == AppConfig.TYPE_CHINESE) {
                        LearningRecordType.CHINESE_DICTATION
                    } else {
                        LearningRecordType.ENGLISH_DICTATION
                    },
                content = word,
                timestamp = latestRecord.timestamp,
                isCorrect = latestRecord.isCorrect,
                translation = translation,
                errorCount = errorCount,
                totalCount = totalCount,
            )
        }.sortedByDescending { it.timestamp }
    }

    /**
     * 加载错题本记录
     */
    private suspend fun loadErrorBookRecords(
        userName: String,
        startDate: Long,
        endDate: Long,
    ): List<LearningRecord> {
        val json = errorBookStorage.getAllQuestionsJson(userName) ?: return emptyList()

        // 计算日期范围的时间戳
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = startDate
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTimestamp = calendar.timeInMillis

        calendar.timeInMillis = endDate
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endTimestamp = calendar.timeInMillis

        val records = mutableListOf<LearningRecord>()
        val keys = json.keys()

        while (keys.hasNext()) {
            val question = keys.next()
            if (question != "schemaVersion") {
                val questionArray = json.optJSONArray(question)
                if (questionArray != null) {
                    var allErrorCount = 0
                    var allTotalCount = 0
                    var latestTimestamp = 0L
                    var latestIsCorrect = false
                    var latestAnswer = ""
                    var hasDateRangeRecord = false

                    // 遍历该题目的所有记录
                    for (i in 0 until questionArray.length()) {
                        val recordObj = questionArray.getJSONObject(i)
                        val dateStr = recordObj.optString("date", "")
                        if (dateStr.isNotEmpty()) {
                            try {
                                val timestamp = dateFormat.parse(dateStr)?.time ?: 0L
                                val status = recordObj.optString("status", "")

                                // 统计全部历史数据的错误次数和总次数
                                allTotalCount++
                                if (status == "错误") {
                                    allErrorCount++
                                }

                                // 筛选日期范围内的记录（用于确定是否显示和最新记录信息）
                                if (timestamp >= startTimestamp && timestamp <= endTimestamp) {
                                    hasDateRangeRecord = true
                                    if (timestamp > latestTimestamp) {
                                        latestTimestamp = timestamp
                                        latestIsCorrect = status == "正确"
                                        latestAnswer = recordObj.optString("answer", "")
                                    }
                                }
                            } catch (e: Exception) {
                                // 忽略解析错误的记录
                                com.smartstudy.core.Logger.w("LearningRecordRepository", "解析记录失败，跳过: ${e.message}", e)
                            }
                        }
                    }

                    // 如果有日期范围内的记录，添加到结果中（但使用全部历史数据统计错误次数和总次数）
                    if (hasDateRangeRecord) {
                        records.add(
                            LearningRecord(
                                type = LearningRecordType.ERROR_BOOK,
                                content = question,
                                timestamp = latestTimestamp,
                                isCorrect = latestIsCorrect,
                                answer = latestAnswer,
                                errorCount = allErrorCount,
                                totalCount = allTotalCount,
                            ),
                        )
                    }
                }
            }
        }

        return records.sortedByDescending { it.timestamp }
    }

    /**
     * 删除学习记录
     * @param userName 用户名
     * @param records 要删除的记录列表（类型和内容的配对）
     * @return 操作结果
     */
    suspend fun deleteLearningRecords(
        userName: String,
        records: List<Pair<LearningRecordType, String>>,
    ): AppResult<Unit> =
        executeDataOperation("删除学习记录") {
            // 按类型分组
            val chineseDictationWords = mutableListOf<String>()
            val englishDictationWords = mutableListOf<String>()
            val errorBookQuestions = mutableListOf<String>()

            records.forEach { (type, content) ->
                when (type) {
                    LearningRecordType.CHINESE_DICTATION -> chineseDictationWords.add(content)
                    LearningRecordType.ENGLISH_DICTATION -> englishDictationWords.add(content)
                    LearningRecordType.ERROR_BOOK -> errorBookQuestions.add(content)
                }
            }

            // 删除不同类型的数据
            if (chineseDictationWords.isNotEmpty()) {
                val result =
                    dictationRepository.deleteWords(
                        userName,
                        AppConfig.TYPE_CHINESE,
                        chineseDictationWords,
                    )
                if (result is AppResult.Failure) {
                    throw IllegalStateException(result.error.message ?: "删除语文听写记录失败")
                }
            }

            if (englishDictationWords.isNotEmpty()) {
                val result =
                    dictationRepository.deleteWords(
                        userName,
                        AppConfig.TYPE_ENGLISH,
                        englishDictationWords,
                    )
                if (result is AppResult.Failure) {
                    throw IllegalStateException(result.error.message ?: "删除英语听写记录失败")
                }
            }

            if (errorBookQuestions.isNotEmpty()) {
                val result = errorBookRepository.deleteQuestions(userName, errorBookQuestions)
                if (result is AppResult.Failure) {
                    throw IllegalStateException(result.error.message ?: "删除错题本记录失败")
                }
            }
        }

    /**
     * 更新学习记录
     * @param userName 用户名
     * @param oldRecord 原始记录
     * @param newRecord 更新后的记录
     * @return 操作结果
     */
    suspend fun updateLearningRecord(
        userName: String,
        oldRecord: LearningRecord,
        newRecord: LearningRecord,
    ): AppResult<Unit> =
        executeDataOperation("更新学习记录") {
            when (oldRecord.type) {
                LearningRecordType.CHINESE_DICTATION, LearningRecordType.ENGLISH_DICTATION -> {
                    // 听写记录：更新词语和翻译
                    val dictationType =
                        if (oldRecord.type == LearningRecordType.CHINESE_DICTATION) {
                            AppConfig.TYPE_CHINESE
                        } else {
                            AppConfig.TYPE_ENGLISH
                        }

                    // 如果内容改变了，需要更新词语
                    if (oldRecord.content != newRecord.content) {
                        val updateResult =
                            dictationRepository.updateWord(
                                userName,
                                dictationType,
                                oldRecord.content,
                                newRecord.content,
                                newRecord.translation,
                            )
                        if (updateResult is AppResult.Failure) {
                            throw IllegalStateException(updateResult.error.message ?: "更新听写记录失败")
                        }
                    } else if (oldRecord.translation != newRecord.translation && oldRecord.type == LearningRecordType.ENGLISH_DICTATION) {
                        // 如果只是翻译改变了（仅英文听写）
                        val updateResult =
                            dictationRepository.updateWord(
                                userName,
                                dictationType,
                                oldRecord.content,
                                newRecord.content,
                                newRecord.translation,
                            )
                        if (updateResult is AppResult.Failure) {
                            throw IllegalStateException(updateResult.error.message ?: "更新听写记录翻译失败")
                        }
                    }

                    // 如果 isCorrect 改变了，需要更新最新记录的状态
                    if (oldRecord.isCorrect != newRecord.isCorrect) {
                        val updateStatusResult =
                            dictationRepository.updateLatestRecordStatus(
                                userName,
                                dictationType,
                                newRecord.content, // 使用新内容（如果内容改变了）
                                newRecord.isCorrect,
                            )
                        if (updateStatusResult is AppResult.Failure) {
                            throw IllegalStateException(updateStatusResult.error.message ?: "更新听写记录状态失败")
                        }
                    }
                }
                LearningRecordType.ERROR_BOOK -> {
                    // 错题本记录：更新题目和答案
                    // 如果题目内容改变了，需要更新题目
                    if (oldRecord.content != newRecord.content) {
                        val updateQuestionResult =
                            errorBookRepository.updateQuestion(
                                userName,
                                oldRecord.content,
                                newRecord.content,
                            )
                        if (updateQuestionResult is AppResult.Failure) {
                            throw IllegalStateException(updateQuestionResult.error.message ?: "更新错题本题目失败")
                        }
                    }

                    // 如果答案改变了，需要更新答案（使用新题目内容作为key）
                    val questionKey =
                        if (oldRecord.content != newRecord.content) {
                            newRecord.content
                        } else {
                            oldRecord.content
                        }

                    if (oldRecord.answer != newRecord.answer) {
                        val updateAnswerResult =
                            errorBookRepository.updateAnswer(
                                userName,
                                questionKey,
                                newRecord.answer,
                            )
                        if (updateAnswerResult is AppResult.Failure) {
                            throw IllegalStateException(updateAnswerResult.error.message ?: "更新错题本答案失败")
                        }
                    }

                    // 如果 isCorrect 改变了，需要更新最新记录的状态
                    if (oldRecord.isCorrect != newRecord.isCorrect) {
                        val updateStatusResult =
                            errorBookRepository.updateLatestRecordStatus(
                                userName,
                                questionKey, // 使用 questionKey（可能是新题目或旧题目）
                                newRecord.isCorrect,
                            )
                        if (updateStatusResult is AppResult.Failure) {
                            throw IllegalStateException(updateStatusResult.error.message ?: "更新错题本记录状态失败")
                        }
                    }
                }
            }
        }
    }
