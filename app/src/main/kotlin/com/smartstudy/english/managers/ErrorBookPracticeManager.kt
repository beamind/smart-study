package com.smartstudy.english.managers

import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger
import com.smartstudy.english.QuestionAnswer

/**
 * 错题本练习管理Manager
 *
 * 负责错题练习流程管理，将练习相关逻辑从Activity中分离出来。
 *
 * 功能特性：
 * - 统一的练习流程管理
 * - 自动超时管理
 * - 生命周期感知
 *
 * 使用示例：
 * ```
 * val practiceManager = ErrorBookPracticeManager(
 *     activity = this,
 *     onNextQuestion = { goToNext() },
 *     onPracticeComplete = { showAnswers() }
 * )
 * lifecycle.addObserver(practiceManager)
 * practiceManager.startPractice(questions)
 * ```
 *
 * @param activity Activity实例
 * @param onNextQuestion 进入下一题回调
 * @param onPracticeComplete 练习完成回调
 */
class ErrorBookPracticeManager(
    private val activity: AppCompatActivity,
    private val onNextQuestion: () -> Unit,
    private val onPracticeComplete: () -> Unit,
    // 题目更新回调：当前索引、总数、题目内容
    private val onQuestionUpdate: ((Int, Int, String) -> Unit)? = null,
) : DefaultLifecycleObserver {
    companion object {
        private const val TAG = "ErrorBookPracticeManager"
    }

    private val practiceQuestions = mutableListOf<QuestionAnswer>()
    private val practicedQuestions = mutableListOf<QuestionAnswer>()
    private var currentQuestionIndex = 0
    private var isWaitingForStart = false
    private var isPracticeActive = false

    private val questionTimeoutHandler = Handler(Looper.getMainLooper())
    private var questionTimeoutRunnable: Runnable? = null

    /**
     * 开始练习
     */
    fun startPractice(questions: List<QuestionAnswer>) {
        practiceQuestions.clear()
        practicedQuestions.clear()
        practiceQuestions.addAll(questions)
        currentQuestionIndex = 0
        isWaitingForStart = false
        isPracticeActive = true

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startQuestionPractice()
    }

    /**
     * 开始题目练习
     */
    private fun startQuestionPractice() {
        if (currentQuestionIndex >= practiceQuestions.size) {
            // 练习完成
            stopPractice()
            onPracticeComplete()
            return
        }

        val questionAnswer = practiceQuestions[currentQuestionIndex]
        practicedQuestions.add(questionAnswer)

        // 更新UI
        onQuestionUpdate?.invoke(
            currentQuestionIndex + 1,
            practiceQuestions.size,
            questionAnswer.question,
        )
        
        // 启动单题超时计时器
        startQuestionTimeoutTimer()
    }

    /**
     * 进入下一题
     */
    fun goToNextQuestion() {
        if (!isPracticeActive) {
            Logger.w(TAG, "练习未激活，忽略进入下一题的请求")
            return
        }
        Logger.d(TAG, "进入下一题")
        stopQuestionTimeoutTimer() // 停止当前题目的计时器
        currentQuestionIndex++
        startQuestionPractice()
    }

    /**
     * 停止练习
     */
    fun stopPractice() {
        isPracticeActive = false
        isWaitingForStart = false
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopQuestionTimeoutTimer() // 停止单题计时器
    }

    /**
     * 启动单题超时计时器（1分钟）
     */
    private fun startQuestionTimeoutTimer() {
        stopQuestionTimeoutTimer()
        questionTimeoutRunnable = Runnable {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Logger.w(TAG, "题目超时（1分钟），自动进入下一题")
                // 超时自动进入下一题
                activity.runOnUiThread {
                    currentQuestionIndex++
                    startQuestionPractice()
                }
            }
        }
        questionTimeoutRunnable?.let { runnable ->
            questionTimeoutHandler.postDelayed(
                runnable,
                AppConfig.QUESTION_TIMEOUT_SECONDS * AppConfig.MILLIS_PER_SECOND
            )
            Logger.d(TAG, "已启动单题超时计时器（${AppConfig.QUESTION_TIMEOUT_SECONDS}秒）")
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
     * 获取已练习的题目列表
     */
    fun getPracticedQuestions(): List<QuestionAnswer> = practicedQuestions.toList()

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        cleanup()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopPractice()
        questionTimeoutHandler.removeCallbacksAndMessages(null)
        Logger.d(TAG, "练习管理器已清理")
    }
}
