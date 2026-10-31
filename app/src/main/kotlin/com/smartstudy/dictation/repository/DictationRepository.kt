package com.smartstudy.dictation.repository

import android.content.Context
import android.graphics.Bitmap
import com.smartstudy.R
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.core.repository.BaseRepository
import com.smartstudy.core.repository.executeDataOperation
import com.smartstudy.core.runAppCatching
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.DictationWordStat
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.core.interfaces.toInterfaceMode
import com.smartstudy.dictation.core.services.DictationStatisticsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 听写数据仓库
 *
 * 统一管理听写相关的数据访问，包括：
 * - 图片识别
 * - 错题存储和查询
 * - 错题统计（原始统计数据，不包含业务逻辑过滤和排序）
 *
 * 功能特性：
 * - 统一错误处理：所有方法返回 AppResult
 * - 线程安全：自动在 IO 线程执行数据操作
 * - 易于测试：可以轻松替换数据源进行单元测试
 * - 单一职责：将数据访问逻辑从业务逻辑中分离
 *
 * 注意：
 * - 此 Repository 只负责数据访问，不包含业务逻辑（如过滤、排序等）
 * - 业务逻辑应在 DictationUseCase 中处理
 *
 * 使用示例：
 * ```
 * val repository = DictationRepository(context, imageUnderstandingService)
 * when (val result = repository.recognizeWords(bitmap, mode)) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * @param context Android Context
 * @param imageUnderstandingService 图片理解服务
 */
class DictationRepository(
    private val context: Context,
    private val imageUnderstandingService: ImageUnderstandingService,
) : BaseRepository {
    override val tag: String = "DictationRepository"

    private val storage: DictationRecordStorage by lazy {
        DictationRecordStorage(context)
    }

    private val statisticsService: DictationStatisticsService by lazy {
        DictationStatisticsService()
    }

    /**
     * 识别图片中的词语
     *
     * @param bitmap 图片
     * @param mode 识别模式
     * @return 识别结果
     */
    suspend fun recognizeWords(
        bitmap: Bitmap,
        mode: ImageUnderstandingService.ImageUnderstandingMode,
    ): AppResult<List<String>> =
        runAppCatching(tag, "识别图片中的词语") {
            withContext(Dispatchers.IO) {
                val result =
                    imageUnderstandingService.understandWords(
                        bitmap,
                        mode.toInterfaceMode(),
                    )
                when (result) {
                    is AppResult.Success -> {
                        val words = result.data
                        // 边界检查：空列表
                        require(words.isNotEmpty()) { context.getString(R.string.no_words) }
                        // 边界检查：词语数量限制
                        if (words.size > 100) {
                            Logger.w(
                                tag,
                                "识别到的词语数量过多（${words.size}），可能影响性能",
                            )
                        }
                        words
                    }
                    is AppResult.Failure -> {
                        // 如果有原始异常，抛出它；否则创建一个新的异常
                        val exception =
                            result.error.cause
                                ?: RuntimeException(result.error.message)
                        throw exception
                    }
                }
            }
        }

    /**
     * 保存题目记录
     *
     * @param userName 用户名
     * @param dictationType 听写类型
     * @param questions 题目列表
     * @return 保存结果
     */
    suspend fun saveQuestions(
        userName: String,
        dictationType: String,
        questions: List<QuestionRecord>,
    ): AppResult<Unit> =
        executeDataOperation("保存题目记录") {
            storage.saveQuestions(userName, dictationType, questions)
        }

    /**
     * 加载错题统计
     *
     * @param userName 用户名
     * @param dictationType 听写类型
     * @return 错题统计列表
     */
    suspend fun loadErrorWordStats(
        userName: String,
        dictationType: String,
    ): AppResult<List<DictationWordStat>> =
        executeDataOperation("加载错题统计") {
            // 从存储层获取原始记录
            val records = storage.getAllRecords(userName, dictationType)
            // 使用统计服务计算统计数据
            statisticsService.calculateStats(records)
        }

    /**
     * 删除指定词语的所有记录
     *
     * @param userName 用户名
     * @param dictationType 听写类型
     * @param words 要删除的词语列表
     * @return 操作结果
     */
    suspend fun deleteWords(
        userName: String,
        dictationType: String,
        words: List<String>,
    ): AppResult<Unit> =
        executeDataOperation("删除词语") {
            storage.deleteWords(userName, dictationType, words)
        }

    /**
     * 更新单词名称和翻译
     *
     * @param userName 用户名
     * @param dictationType 听写类型
     * @param oldWord 旧单词名称
     * @param newWord 新单词名称
     * @param newTranslation 新翻译（可选，仅英文类型）
     * @return 操作结果
     */
    suspend fun updateWord(
        userName: String,
        dictationType: String,
        oldWord: String,
        newWord: String,
        newTranslation: String = "",
    ): AppResult<Unit> =
        executeDataOperation("更新单词") {
            storage.updateWord(userName, dictationType, oldWord, newWord, newTranslation)
        }

    /**
     * 更新词语最新记录的状态
     *
     * @param userName 用户名
     * @param dictationType 听写类型
     * @param word 词语
     * @param isCorrect 是否正确
     * @return 操作结果
     */
    suspend fun updateLatestRecordStatus(
        userName: String,
        dictationType: String,
        word: String,
        isCorrect: Boolean,
    ): AppResult<Unit> =
        executeDataOperation("更新听写记录状态") {
            storage.updateLatestRecordStatus(userName, dictationType, word, isCorrect)
        }

    /**
     * 按日期范围加载听写记录统计
     *
     * @param userName 用户名
     * @param dictationType 听写类型
     * @param startDate 开始日期（时间戳，毫秒）
     * @param endDate 结束日期（时间戳，毫秒）
     * @return 听写记录统计列表
     */
    suspend fun loadDictationRecordsByDateRange(
        userName: String,
        dictationType: String,
        startDate: Long,
        endDate: Long,
    ): AppResult<List<DictationWordStat>> =
        executeDataOperation("按日期范围加载听写记录") {
            // 从存储层获取日期范围内的记录
            val records = storage.getRecordsByDateRange(userName, dictationType, startDate, endDate)
            // 使用统计服务计算统计数据
            statisticsService.calculateStats(records)
        }
}
