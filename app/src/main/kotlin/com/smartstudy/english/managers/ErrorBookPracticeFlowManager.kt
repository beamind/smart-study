package com.smartstudy.english.managers

import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewModelScope
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.english.EnglishErrorBookSampling
import com.smartstudy.english.EnglishErrorBookStorage
import com.smartstudy.english.ErrorBookViewModel
import com.smartstudy.english.QuestionAnswer
import com.smartstudy.english.QuestionGenerationService
import com.smartstudy.english.usecases.ErrorBookUseCase
import com.smartstudy.utils.showLongToast
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 练习流程UI回调接口
 * 用于与Activity通信，保持解耦
 */
interface PracticeFlowUICallback {
    /**
     * 练习开始回调
     */
    fun onPracticeStarted()

    /**
     * 题目变化回调
     * @param index 当前题目索引（从0开始）
     * @param total 总题目数
     * @param question 题目内容
     */
    fun onQuestionChanged(
        index: Int,
        total: Int,
        question: String,
    )

    /**
     * 练习完成回调
     */
    fun onPracticeCompleted()

    /**
     * 练习停止回调
     */
    fun onPracticeStopped()

    /**
     * 更新状态文本
     * @param text 状态文本
     */
    fun updateStatusText(text: String)

    /**
     * 显示答案列表
     * @param questions 练习的题目列表
     */
    fun showAnswers(questions: List<QuestionAnswer>)

    /**
     * 更新进度卡片可见性
     * @param visible 是否可见
     */
    fun updateProgressVisibility(visible: Boolean)

    /**
     * 更新状态卡片可见性
     * @param visible 是否可见
     */
    fun updateStatusVisibility(visible: Boolean)

    /**
     * 更新答案卡片可见性
     * @param visible 是否可见
     */
    fun updateAnswersVisibility(visible: Boolean)
}

/**
 * 错题本练习流程管理Manager
 *
 * 负责错题练习和举一反三的完整流程管理，将练习相关逻辑从Activity中分离出来。
 *
 * 功能特性：
 * - 统一的练习流程管理（错题练习、举一反三）
 * - 自动题目采样（基于错误率）
 * - AI题目生成管理
 * - 练习状态管理
 * - 超时计时器管理
 * - 答案显示管理
 *
 * 使用示例：
 * ```
 * val practiceFlowManager = ErrorBookPracticeFlowManager(
 *     activity = this,
 *     errorBookUseCase = errorBookUseCase,
 *     questionGenerationService = questionGenerationService,
 *     viewModel = viewModel,
 *     uiCallback = this // Activity实现PracticeFlowUICallback接口
 * )
 * practiceFlowManager.startPractice(count)
 * practiceFlowManager.startVariation(count)
 * ```
 *
 * @param activity Activity实例，用于访问生命周期
 * @param errorBookUseCase 错题本业务逻辑用例
 * @param questionGenerationService 题目生成服务
 * @param viewModel 错题本ViewModel，用于管理练习状态
 * @param uiCallback UI回调接口，用于与Activity通信
 */
class ErrorBookPracticeFlowManager(
    private val activity: AppCompatActivity,
    private val errorBookUseCase: ErrorBookUseCase,
    private val questionGenerationService: QuestionGenerationService,
    private val viewModel: ErrorBookViewModel,
    private val uiCallback: PracticeFlowUICallback,
) {
    companion object {
        private const val TAG = "ErrorBookPracticeFlow"
    }

    // 练习状态
    private val practiceQuestions = mutableListOf<QuestionAnswer>()
    private val practicedQuestions = mutableListOf<QuestionAnswer>()

    // 单题超时计时器
    private val questionTimeoutHandler = Handler(Looper.getMainLooper())
    private var questionTimeoutRunnable: Runnable? = null

    /**
     * 开始错题练习
     *
     * @param count 练习题目数量
     */
    fun startPractice(count: Int) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        activity.runOnUiThread {
                            activity.showLongToast(userResult.error.message)
                        }
                        return@launch
                    }
                }

            // 加载所有错题的完整统计信息（带答案）
            val result = errorBookUseCase.loadAllQuestionsWithAnswers(userName)

            when (result) {
                is com.smartstudy.core.AppResult.Success -> {
                    val questionAnswersWithStats = result.data

                    if (questionAnswersWithStats.isEmpty()) {
                        activity.runOnUiThread {
                            activity.showLongToast(activity.getString(com.smartstudy.R.string.no_questions_to_practice))
                        }
                        return@launch
                    }

                    // 采样题目（使用题目和答案对）
                    val selectedQuestionAnswers =
                        EnglishErrorBookSampling.selectQuestionAnswersByErrorRate(
                            questionAnswersWithStats,
                            count,
                        )

                    if (selectedQuestionAnswers.isEmpty()) {
                        activity.runOnUiThread {
                            activity.showLongToast(activity.getString(com.smartstudy.R.string.not_enough_questions))
                        }
                        return@launch
                    }

                    // 打乱顺序
                    Collections.shuffle(selectedQuestionAnswers)

                    Logger.d(TAG, "错题练习题目: ${selectedQuestionAnswers.size} 道")

                    // 直接开始练习
                    startQuestionPractice(selectedQuestionAnswers, isVariation = false)
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    activity.runOnUiThread {
                        activity.showLongToast(result.error.message)
                    }
                }
            }
        }
    }

    /**
     * 开始练习题目（在题目准备就绪后调用）
     *
     * @param questions 练习题目列表
     * @param isVariation 是否为举一反三模式
     */
    fun startQuestionPractice(
        questions: List<QuestionAnswer>,
        isVariation: Boolean,
    ) {
        // 设置举一反三模式标志
        viewModel.setVariationMode(isVariation)
        // 重置提交状态
        viewModel.setPracticeSubmitted(false)

        // 清空并设置练习题目
        practiceQuestions.clear()
        practicedQuestions.clear()
        practiceQuestions.addAll(questions)
        viewModel.setPracticeQuestions(questions)

        Logger.d(TAG, if (isVariation) "举一反三题目: ${questions.size} 道" else "错题练习题目: ${questions.size} 道")

        // 重置题目索引
        viewModel.resetQuestionIndex()

        // 开始第一道题目
        startNextQuestion()
    }

    /**
     * 开始下一道题目
     */
    fun startNextQuestion() {
        val currentIndex = viewModel.getCurrentQuestionIndexSync()
        val questions = viewModel.getPracticeQuestionsSync()

        // 检查是否练习完成
        if (currentIndex >= questions.size) {
            // 练习完成，显示答案
            showAnswers()
            stopPractice()
            uiCallback.onPracticeCompleted()
            return
        }

        // 如果是第一题，启动练习
        if (currentIndex == 0) {
            viewModel.setPracticeActive(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            uiCallback.onPracticeStarted()
        }

        val questionAnswer = questions[currentIndex]

        // 记录练习顺序
        practicedQuestions.add(questionAnswer)

        // 更新UI
        uiCallback.onQuestionChanged(currentIndex, questions.size, questionAnswer.question)
        uiCallback.updateProgressVisibility(true)
        uiCallback.updateStatusVisibility(false)
        uiCallback.updateAnswersVisibility(false)
        
        // 启动单题超时计时器
        startQuestionTimeoutTimer()
    }

    /**
     * 进入下一题（按钮点击）
     */
    fun goToNextQuestion() {
        if (!viewModel.uiState.value.isPracticeActive) {
            Logger.w(TAG, "练习未激活，忽略进入下一题的请求")
            return
        }
        Logger.d(TAG, "进入下一题")
        stopQuestionTimeoutTimer() // 停止当前题目的计时器
        viewModel.incrementQuestionIndex()
        startNextQuestion()
    }

    /**
     * 显示答案
     */
    fun showAnswers() {
        activity.runOnUiThread {
            if (practicedQuestions.isEmpty()) {
                Logger.w(TAG, "没有练习答案可显示")
                return@runOnUiThread
            }

            // 隐藏进度、状态卡片，显示答案卡片
            uiCallback.updateProgressVisibility(false)
            uiCallback.updateStatusVisibility(false)
            uiCallback.updateAnswersVisibility(true)

            // 显示答案列表
            uiCallback.showAnswers(practicedQuestions)

            Logger.d(TAG, "显示练习答案，共 ${practicedQuestions.size} 道题目")
        }
    }

    /**
     * 停止练习
     */
    fun stopPractice() {
        viewModel.setPracticeActive(false)
        viewModel.setWaitingForStart(false)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopQuestionTimeoutTimer() // 停止单题计时器
        uiCallback.onPracticeStopped()
    }

    /**
     * 启动单题超时计时器（英文错题本为2分钟，其他为1分钟）
     */
    private fun startQuestionTimeoutTimer() {
        stopQuestionTimeoutTimer()
        val timeoutSeconds = AppConfig.ENGLISH_ERROR_BOOK_QUESTION_TIMEOUT_SECONDS
        questionTimeoutRunnable = Runnable {
            // 检查Activity是否仍然有效
            if (!activity.isFinishing && !activity.isDestroyed) {
                Logger.w(TAG, "题目超时（${timeoutSeconds}秒），自动进入下一题")
                // 超时自动进入下一题
                activity.runOnUiThread {
                    viewModel.incrementQuestionIndex()
                    startNextQuestion()
                }
            }
        }
        questionTimeoutRunnable?.let { runnable ->
            questionTimeoutHandler.postDelayed(
                runnable,
                timeoutSeconds * AppConfig.MILLIS_PER_SECOND
            )
            Logger.d(TAG, "已启动单题超时计时器（${timeoutSeconds}秒）")
        } ?: Logger.e(TAG, "questionTimeoutRunnable 创建失败，无法启动单题超时计时器")
    }

    /**
     * 停止单题超时计时器
     */
    private fun stopQuestionTimeoutTimer() {
        questionTimeoutRunnable?.let {
            questionTimeoutHandler.removeCallbacks(it)
            questionTimeoutRunnable = null
        }
    }

    /**
     * 获取练习的题目列表（用于提交）
     */
    fun getPracticedQuestions(): List<QuestionAnswer> {
        return practicedQuestions.toList()
    }

    /**
     * 清空练习状态
     */
    fun clearPracticeState() {
        practiceQuestions.clear()
        practicedQuestions.clear()
        viewModel.resetPracticeState()
    }

    /**
     * 清理资源
     * 应在Activity的onDestroy中调用，防止内存泄漏
     */
    fun cleanup() {
        stopPractice()
        // 清理Handler回调，防止内存泄漏
        questionTimeoutHandler.removeCallbacksAndMessages(null)
        questionTimeoutRunnable = null
        Logger.d(TAG, "练习流程管理器已清理")
    }

    /**
     * 开始举一反三
     *
     * @param count 要生成的题目数量
     * @param onProgress 进度回调，参数为当前进度和总数
     * @param onProgressDialogDismiss 进度对话框关闭回调（用于在生成完成后关闭对话框）
     */
    fun startVariation(
        count: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onProgressDialogDismiss: (() -> Unit)? = null,
    ) {
        viewModel.viewModelScope.launch {
            // 确保默认用户存在并获取用户名
            val userName =
                when (val userResult = errorBookUseCase.ensureDefaultUser()) {
                    is com.smartstudy.core.AppResult.Success -> userResult.data.name
                    is com.smartstudy.core.AppResult.Failure -> {
                        activity.runOnUiThread {
                            activity.showLongToast(userResult.error.message)
                        }
                        return@launch
                    }
                }

            val loadResult = loadQuestionsForVariation(userName)
            when (loadResult) {
                is com.smartstudy.core.AppResult.Success -> {
                    handleVariationSuccess(
                        loadResult.data,
                        count,
                        onProgress,
                        onProgressDialogDismiss,
                    )
                }
                is com.smartstudy.core.AppResult.Failure -> {
                    activity.runOnUiThread {
                        activity.showLongToast(loadResult.error.message)
                    }
                }
            }
        }
    }

    /**
     * 加载用于举一反三的错题列表
     */
    private suspend fun loadQuestionsForVariation(userName: String): com.smartstudy.core.AppResult<List<Pair<QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>>> {
        return errorBookUseCase.loadAllQuestionsWithAnswers(userName)
    }

    /**
     * 处理举一反三成功的情况
     */
    private suspend fun handleVariationSuccess(
        questionAnswersWithStats: List<Pair<QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>>,
        count: Int,
        onProgress: (Int, Int) -> Unit,
        onProgressDialogDismiss: (() -> Unit)?,
    ) {
        when {
            questionAnswersWithStats.isEmpty() -> {
                activity.runOnUiThread {
                    activity.showLongToast(activity.getString(com.smartstudy.R.string.no_questions_to_practice))
                }
            }
            else -> {
                val selectedQuestionAnswers = selectQuestionsForVariation(questionAnswersWithStats, count)
                if (selectedQuestionAnswers.isNotEmpty()) {
                    val generatedQuestions = generateVariationQuestions(selectedQuestionAnswers, onProgress)
                    onProgress(selectedQuestionAnswers.size, selectedQuestionAnswers.size)
                    onProgressDialogDismiss?.invoke()

                    if (generatedQuestions.isNotEmpty()) {
                        // 打乱顺序并开始练习
                        Collections.shuffle(generatedQuestions)
                        Logger.d(TAG, "举一反三题目: ${generatedQuestions.size} 道")
                        startQuestionPractice(generatedQuestions, isVariation = true)
                    } else {
                        activity.runOnUiThread {
                            activity.showLongToast(activity.getString(com.smartstudy.R.string.error_book_generation_failed))
                        }
                    }
                }
            }
        }
    }

    /**
     * 选择用于举一反三的题目
     */
    private fun selectQuestionsForVariation(
        questionAnswersWithStats: List<Pair<QuestionAnswer, EnglishErrorBookStorage.QuestionWithStats>>,
        count: Int,
    ): List<QuestionAnswer> {
        val selectedQuestionAnswers =
            EnglishErrorBookSampling.selectQuestionAnswersByErrorRate(
                questionAnswersWithStats,
                count,
            )

        if (selectedQuestionAnswers.isEmpty()) {
            activity.runOnUiThread {
                activity.showLongToast(activity.getString(com.smartstudy.R.string.not_enough_questions))
            }
        }

        return selectedQuestionAnswers
    }

    /**
     * 批量生成举一反三题目
     */
    private suspend fun generateVariationQuestions(
        selectedQuestionAnswers: List<QuestionAnswer>,
        onProgress: (Int, Int) -> Unit,
    ): List<QuestionAnswer> {
        val generatedQuestions = mutableListOf<QuestionAnswer>()
        var successCount = 0
        var failCount = 0

        selectedQuestionAnswers.forEachIndexed { index, originalQA ->
            onProgress(index, selectedQuestionAnswers.size)

            val result =
                questionGenerationService.generateSimilarQuestion(
                    originalQA.question,
                    originalQA.answer,
                )

            when (result) {
                is AppResult.Success -> {
                    val (question, answer) = result.data
                    generatedQuestions.add(
                        QuestionAnswer(
                            question = question,
                            answer = answer,
                            isCorrect = true,
                            isAiGenerated = true,
                        ),
                    )
                    successCount++
                    Logger.d(TAG, "成功生成题目 ${index + 1}: ${question.take(50)}...")
                }

                is AppResult.Failure -> {
                    failCount++
                    Logger.w(TAG, "生成题目 ${index + 1} 失败: ${result.error.message}", result.error.cause)
                }
            }
        }

        // 显示生成结果统计（如果有失败）
        if (failCount > 0) {
            Logger.w(TAG, "成功生成 $successCount 道题目，$failCount 道失败")
        }

        return generatedQuestions
    }
}
