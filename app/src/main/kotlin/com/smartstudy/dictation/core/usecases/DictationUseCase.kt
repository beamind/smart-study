package com.smartstudy.dictation.core.usecases

import com.smartstudy.core.AppResult
import com.smartstudy.dictation.core.DictationWordStat
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.core.WordSelectionHelper
import com.smartstudy.dictation.core.interfaces.toInterfaceMode
import com.smartstudy.dictation.repository.DictationRepository
import com.smartstudy.user.usecases.UserUseCase

/**
 * 听写业务逻辑用例类
 *
 * 将业务逻辑从Activity中分离出来，提高代码的可测试性和可维护性。
 *
 * 功能特性：
 * - 图片识别业务逻辑
 * - 错题加载和选择业务逻辑（包括过滤、排序等业务规则）
 * - 题目保存业务逻辑
 *
 * 使用示例：
 * ```
 * val useCase = DictationUseCase(
 *     imageUnderstandingService = imageUnderstandingService,
 *     dictationRepository = dictationRepository,
 *     userUseCase = userUseCase
 * )
 * val result = useCase.loadErrorWords(userName, dictationType)
 * ```
 *
 * @param imageUnderstandingService 图片理解服务
 * @param dictationRepository 听写数据仓库
 * @param userUseCase 用户业务逻辑用例
 */
class DictationUseCase(
    private val imageUnderstandingService: ImageUnderstandingService,
    private val dictationRepository: DictationRepository,
    private val userUseCase: UserUseCase,
) {
    companion object {
        private const val TAG = "DictationUseCase"
    }

    /**
     * 识别图片中的词语
     *
     * @param bitmap 图片
     * @param mode 识别模式
     * @return 识别结果
     */
    suspend fun recognizeWords(
        bitmap: android.graphics.Bitmap,
        mode: ImageUnderstandingService.ImageUnderstandingMode,
    ): AppResult<List<String>> {
        return imageUnderstandingService.understandWords(bitmap, mode.toInterfaceMode())
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
    ): AppResult<List<DictationWordStat>> {
        return dictationRepository.loadErrorWordStats(userName, dictationType)
    }

    /**
     * 加载错题列表（只返回有错误的词语，按错误率降序排序）
     *
     * 业务逻辑：
     * - 从统计数据中过滤出错误率大于0的词语
     * - 按错误率降序排序（错误率高的在前）
     * - 返回词语列表（字符串）
     *
     * @param userName 用户名
     * @param dictationType 听写类型
     * @return 错题列表（只包含有错误的词语，按错误率降序排序）
     */
    suspend fun loadErrorWords(
        userName: String,
        dictationType: String,
    ): AppResult<List<String>> {
        val statsResult = dictationRepository.loadErrorWordStats(userName, dictationType)
        return when (statsResult) {
            is AppResult.Success -> {
                val errorWords =
                    statsResult.data
                        .filter { it.errorCount > 0 } // 业务规则：只返回有错误的词语
                        .sortedByDescending { it.errorRate } // 业务规则：按错误率降序排序
                        .map { it.word }
                AppResult.Success(errorWords)
            }
            is AppResult.Failure -> statsResult
        }
    }

    /**
     * 根据错误率选择错题
     *
     * @param errorStats 错题统计列表
     * @param count 需要选择的数量
     * @param selector 选择器函数，用于从统计对象中提取词语
     * @return 选中的词语列表
     */
    fun selectWordsByErrorRate(
        errorStats: List<DictationWordStat>,
        count: Int,
        selector: (DictationWordStat) -> String,
    ): List<String> {
        return WordSelectionHelper.selectByErrorRate(
            errorStats = errorStats,
            count = count,
            selector = selector,
        )
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
    ): AppResult<Unit> {
        return dictationRepository.saveQuestions(userName, dictationType, questions)
    }

    /**
     * 获取当前用户名
     *
     * @return 当前用户名，如果不存在则返回默认值
     */
    suspend fun getCurrentUserName(): String {
        return when (val result = userUseCase.getCurrentUser()) {
            is AppResult.Success -> result.data?.name ?: "游客"
            is AppResult.Failure -> "游客"
        }
    }
}
