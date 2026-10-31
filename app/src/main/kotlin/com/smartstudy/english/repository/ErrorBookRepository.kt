package com.smartstudy.english.repository

import android.content.Context
import com.smartstudy.core.AppResult
import com.smartstudy.core.repository.BaseRepository
import com.smartstudy.core.repository.executeDataOperation
import com.smartstudy.english.EnglishErrorBookStorage
import com.smartstudy.english.QuestionAnswer
import com.smartstudy.english.services.ErrorBookStatisticsService

/**
 * 英语错题本数据仓库
 *
 * 统一管理英语错题本相关的数据访问，包括：
 * - 错题的保存、查询、更新、删除
 * - 错题统计和智能采样
 *
 * 功能特性：
 * - 统一错误处理：所有方法返回 AppResult
 * - 线程安全：自动在 IO 线程执行数据操作
 * - 易于测试：可以轻松替换数据源进行单元测试
 * - 单一职责：将数据访问逻辑从业务逻辑中分离
 *
 * 使用示例：
 * ```
 * val repository = ErrorBookRepository(context)
 * when (val result = repository.loadAllQuestions(userName)) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * @param context Android Context
 */
class ErrorBookRepository(
    private val context: Context,
) : BaseRepository {
    override val tag: String = "ErrorBookRepository"

    /**
     * 获取Context，用于访问字符串资源
     */
    fun getContext(): Context = context

    private val storage: EnglishErrorBookStorage by lazy {
        EnglishErrorBookStorage(context)
    }

    private val statisticsService: ErrorBookStatisticsService by lazy {
        ErrorBookStatisticsService()
    }

    /**
     * 加载所有错题题目列表（带统计信息）
     *
     * @param userName 用户名
     * @return 题目统计信息列表
     */
    suspend fun loadAllQuestionsWithStats(
        userName: String,
    ): AppResult<List<EnglishErrorBookStorage.QuestionWithStats>> =
        executeDataOperation("加载错题统计信息") {
            val json = storage.getAllQuestionsJson(userName)
            if (json == null || json.length() == 0) {
                return@executeDataOperation emptyList()
            }
            statisticsService.calculateQuestionStats(json)
        }

    /**
     * 加载所有错题题目列表（带完整统计信息，用于采样）
     *
     * @param userName 用户名
     * @return 题目完整统计信息列表
     */
    suspend fun loadAllQuestionsWithFullStats(
        userName: String,
    ): AppResult<List<EnglishErrorBookStorage.QuestionStat>> =
        executeDataOperation("加载错题完整统计信息") {
            val json = storage.getAllQuestionsJson(userName)
            if (json == null || json.length() == 0) {
                return@executeDataOperation emptyList()
            }
            statisticsService.calculateQuestionFullStats(json)
        }

    /**
     * 加载所有错题题目和答案列表（带统计信息）
     *
     * @param userName 用户名
     * @return 题目和答案对列表（带统计信息）
     */
    suspend fun loadAllQuestionsWithAnswers(
        userName: String,
    ): AppResult<List<Pair<QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>>> =
        executeDataOperation("加载错题和答案") {
            val json = storage.getAllQuestionsJson(userName)
            if (json == null || json.length() == 0) {
                return@executeDataOperation emptyList()
            }
            statisticsService.calculateQuestionAnswersWithStats(json)
        }

    /**
     * 加载所有错题题目列表
     *
     * @param userName 用户名
     * @return 题目列表
     */
    suspend fun loadAllQuestions(userName: String): AppResult<List<String>> =
        executeDataOperation("加载错题列表") {
            storage.loadAllQuestions(userName)
        }

    /**
     * 更新错题（当题目内容改变时，需要更新key）
     *
     * @param userName 用户名
     * @param oldQuestion 旧的题目内容
     * @param newQuestion 新的题目内容
     * @return 操作结果
     */
    suspend fun updateQuestion(
        userName: String,
        oldQuestion: String,
        newQuestion: String,
    ): AppResult<Unit> =
        executeDataOperation("更新错题") {
            storage.updateQuestion(userName, oldQuestion, newQuestion)
        }

    /**
     * 更新错题的答案
     *
     * @param userName 用户名
     * @param question 题目内容
     * @param newAnswer 新的答案
     * @return 操作结果
     */
    suspend fun updateAnswer(
        userName: String,
        question: String,
        newAnswer: String,
    ): AppResult<Unit> =
        executeDataOperation("更新错题答案") {
            storage.updateAnswer(userName, question, newAnswer)
        }

    /**
     * 更新题目最新记录的状态
     *
     * @param userName 用户名
     * @param question 题目内容
     * @param isCorrect 是否正确
     * @return 操作结果
     */
    suspend fun updateLatestRecordStatus(
        userName: String,
        question: String,
        isCorrect: Boolean,
    ): AppResult<Unit> =
        executeDataOperation("更新错题本记录状态") {
            storage.updateLatestRecordStatus(userName, question, isCorrect)
        }

    /**
     * 删除错题
     *
     * @param userName 用户名
     * @param questionsToDelete 要删除的题目列表
     * @return 操作结果
     */
    suspend fun deleteQuestions(
        userName: String,
        questionsToDelete: List<String>,
    ): AppResult<Unit> =
        executeDataOperation("删除错题") {
            storage.deleteQuestions(userName, questionsToDelete)
        }

    /**
     * 按照题目顺序重新排列JSON对象
     *
     * @param userName 用户名
     * @param questionOrder 题目顺序列表（按显示顺序）
     * @return 操作结果
     */
    suspend fun reorderJsonByQuestionOrder(
        userName: String,
        questionOrder: List<String>,
    ): AppResult<Unit> =
        executeDataOperation("重新排列错题顺序") {
            storage.reorderJsonByQuestionOrder(userName, questionOrder)
        }

    /**
     * 保存错题记录（带答案）
     *
     * @param userName 用户名
     * @param questionAnswers 题目和答案对列表（题目内容作为key）
     * @return 操作结果
     */
    suspend fun saveQuestionsWithAnswers(
        userName: String,
        questionAnswers: List<QuestionAnswer>,
    ): AppResult<Unit> =
        executeDataOperation("保存错题和答案") {
            storage.saveQuestionsWithAnswers(userName, questionAnswers)
        }

    /**
     * 保存错题记录
     *
     * @param userName 用户名
     * @param questions 题目列表（题目内容作为key）
     * @return 操作结果
     */
    suspend fun saveQuestions(
        userName: String,
        questions: List<String>,
    ): AppResult<Unit> =
        executeDataOperation("保存错题") {
            storage.saveQuestions(userName, questions)
        }

    /**
     * 保存练习题目到文件
     *
     * @param userName 用户名
     * @param questions 题目列表
     * @return 操作结果
     */
    suspend fun savePracticeQuestions(
        userName: String,
        questions: List<String>,
    ): AppResult<Unit> =
        executeDataOperation("保存练习题目") {
            storage.savePracticeQuestions(userName, questions)
        }
}
