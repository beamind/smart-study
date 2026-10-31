package com.smartstudy.dictation.core.managers

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.core.handlers.DictationViewManager
import com.smartstudy.dictation.core.handlers.DictationVoiceManager
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.dictation.ui.DictationViewModel

/**
 * 听写流程管理器
 *
 * 负责管理听写流程的业务逻辑，将业务逻辑从Activity中分离出来。
 *
 * 功能特性：
 * - 听写流程管理（开始、下一个词语、完成）
 * - TTS播放管理（通过VoiceHandler）
 * - 答案显示管理
 * - 错题提交管理
 * - 超时管理
 *
 * 使用示例：
 * ```
 * val flowManager = DictationFlowManager(
 *     activity = this,
 *     viewModel = viewModel,
 *     viewManager = viewManager,
 *     voiceHandler = voiceHandler,
 *     ttsService = ttsService,
 *     errorWordStorage = errorWordStorage,
 *     dictationType = "chinese",
 *     ttsIntervalMillis = 1000L,
 *     onDictationComplete = { showAnswers() },
 *     onError = { error -> showError(error) }
 * )
 * flowManager.startDictation()
 * flowManager.goToNextWord()
 * ```
 *
 * @param activity Activity实例
 * @param viewModel 听写ViewModel
 * @param viewManager 视图管理器
 * @param voiceHandler 语音处理器
 * @param ttsService TTS服务
 * @param errorWordStorage 错词存储服务
 * @param dictationType 听写类型（"chinese" 或 "english"）
 * @param ttsIntervalMillis TTS播放间隔（毫秒）
 * @param onDictationComplete 听写完成回调
 * @param onError 错误回调
 */
abstract class DictationFlowManager(
    protected val activity: AppCompatActivity,
    protected val viewModel: DictationViewModel,
    protected val viewManager: DictationViewManager,
    protected val voiceHandler: DictationVoiceManager,
    protected val ttsService: ITtsService,
    protected val errorWordStorage: DictationRecordStorage,
    protected val dictationType: String,
    protected val ttsIntervalMillis: Long,
    protected val onDictationComplete: () -> Unit,
    protected val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "DictationFlowManager"
    }

    /**
     * 当前听写的来源类型（"new"表示新的听写，"error"表示错题听写）
     * 默认为"new"（新的听写）
     */
    private var _sourceType: String = "new"

    /**
     * 获取当前听写的来源类型
     * @return 来源类型（"new"表示新的听写，"error"表示错题听写）
     */
    protected val sourceType: String
        get() = _sourceType

    /**
     * 获取当前词语列表
     */
    protected abstract fun getWords(): List<String>

    /**
     * 获取已听写的词语列表
     */
    protected abstract fun getDictatedWords(): List<*>

    /**
     * 记录当前词语到已听写列表
     */
    protected abstract fun recordCurrentWord(word: String)

    /**
     * 创建答案适配器
     */
    protected abstract fun createAnswerAdapter(): RecyclerView.Adapter<*>

    /**
     * 构建错题记录列表
     */
    protected abstract fun buildQuestionRecords(selectedWords: Set<String>): List<QuestionRecord>

    /**
     * 设置来源类型
     * @param sourceType 来源类型（"new"表示新的听写，"error"表示错题听写）
     */
    fun setSourceType(sourceType: String) {
        _sourceType = sourceType
    }

    /**
     * 开始听写
     */
    fun startDictation(
        setKeepScreenOn: (Boolean) -> Unit,
        startQuestionTimeoutTimer: () -> Unit,
    ) {
        // 设置听写状态为活动，保持屏幕常亮，启动单题超时计时器
        viewModel.setDictationActive(true)
        setKeepScreenOn(true)
        startQuestionTimeoutTimer()

        // 听写开始后显示顶部进度卡片和温馨提示（不显示识别卡片）
        viewManager.cardProgress.visibility = View.VISIBLE
        viewManager.cardRecognition.visibility = View.GONE
        viewManager.cardTip.visibility = View.VISIBLE
        // 设置提示文本为"请对我说下一个"
        viewManager.tvTip.text = activity.getString(com.smartstudy.R.string.dictation_tip_next)

        val words = getWords()
        val currentWordIndex = viewModel.uiState.value.currentWordIndex

        if (currentWordIndex >= words.size) {
            // 听写完成，直接显示答案
            completeDictation(setKeepScreenOn)
            return
        }

        val word = words[currentWordIndex]

        // 记录听写顺序
        recordCurrentWord(word)

        // 更新UI - 显示当前词语索引和总词语数量
        viewManager.tvWordIndex.text =
            activity.getString(
                com.smartstudy.R.string.word_index,
                currentWordIndex + 1,
                words.size,
            )
        viewManager.tvCurrentWord.visibility = View.GONE
        viewManager.tvWordIndex.visibility = View.VISIBLE

        // 停止语音识别（不要清空 ASR UI，让 final 结果按计时隐藏，避免"一闪而过"）
        voiceHandler.stopListening(clearAsrUi = false)

        // 播放当前词语
        speakWord(word)
    }

    /**
     * 播放当前词语
     */
    protected fun speakWord(word: String) {
        viewManager.tvStatus.setText(com.smartstudy.R.string.speak_keywords)
        viewManager.tvStatus.visibility = View.VISIBLE

        // 使用VoiceHandler播放词语
        voiceHandler.speakWord(
            word,
            ttsIntervalMillis,
            onComplete = {
                // 播放完成，开始监听
                voiceHandler.startListening()
            },
            onError = { error ->
                viewManager.tvStatus.text = "${activity.getString(com.smartstudy.R.string.error_tts)}: $error"
                com.smartstudy.utils.ToastHelper.showShort(activity, com.smartstudy.R.string.error_tts)
                // 即使TTS出错，也尝试开始监听
                voiceHandler.startListening()
            },
        )
    }

    /**
     * 进入下一个词语
     */
    open fun goToNextWord(
        setKeepScreenOn: (Boolean) -> Unit,
        startQuestionTimeoutTimer: () -> Unit,
        stopQuestionTimeoutTimer: () -> Unit,
    ) {
        stopQuestionTimeoutTimer() // 停止当前词语的计时器
        viewModel.goToNextWord()
        startDictation(setKeepScreenOn, startQuestionTimeoutTimer)
    }

    /**
     * 完成听写
     */
    protected fun completeDictation(setKeepScreenOn: (Boolean) -> Unit) {
        viewManager.tvStatus.text = activity.getString(com.smartstudy.R.string.dictation_complete)
        viewManager.tvCurrentWord.visibility = View.GONE
        viewManager.tvWordIndex.visibility = View.GONE
        viewManager.cardProgress.visibility = View.GONE
        voiceHandler.stopListening()
        onDictationComplete()
        stopDictation(setKeepScreenOn)
    }

    /**
     * 停止听写并释放资源
     */
    fun stopDictation(setKeepScreenOn: (Boolean) -> Unit) {
        viewModel.setDictationActive(false)
        voiceHandler.stopListening()
        setKeepScreenOn(false)
        viewManager.hideDictationViews()
    }

    /**
     * 显示答案
     */
    fun showAnswers() {
        val dictatedWords = getDictatedWords()
        if (dictatedWords.isEmpty()) {
            Logger.w(TAG, "没有听写答案可显示")
            return
        }

        // 隐藏照片和进度卡片，显示答案卡片
        viewManager.cardPhoto.visibility = View.GONE
        viewManager.cardProgress.visibility = View.GONE
        viewManager.cardAnswers.visibility = View.VISIBLE

        // 隐藏cardActions中的其他按钮，但保留状态显示（statusLayout）
        viewManager.btnTakePhoto.visibility = View.GONE
        viewManager.btnViewErrors.visibility = View.GONE
        viewManager.btnErrorDictation.visibility = View.GONE

        // 动态调整约束，让答案卡片占据整个屏幕（从顶部到底部）
        val layoutParams = viewManager.cardAnswers.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        layoutParams.topToBottom = -1
        layoutParams.topToTop = 0
        layoutParams.bottomToTop = -1
        layoutParams.bottomToBottom = 0
        layoutParams.topMargin = 0
        layoutParams.bottomMargin = 0
        // 设置负的左右 margin 来抵消根布局的 padding，去掉边框
        val paddingValue = activity.resources.getDimensionPixelSize(com.smartstudy.R.dimen.spacing_xl)
        layoutParams.leftMargin = -paddingValue
        layoutParams.rightMargin = -paddingValue
        viewManager.cardAnswers.layoutParams = layoutParams

        // 确保提交按钮可见
        viewManager.btnSubmit.visibility = View.VISIBLE

        // 设置答案列表适配器
        val adapter = createAnswerAdapter()
        viewManager.rvAnswers.adapter = adapter

        Logger.d(TAG, "显示听写答案，共 ${dictatedWords.size} 个词语")

        // 显示答案后，播放"听写结束"提示
        ttsService.speak(
            "听写结束",
            object : com.smartstudy.dictation.core.interfaces.ITtsService.TtsCallback {
                override fun onStart() {
                    Logger.d(TAG, "开始播放听写结束提示")
                }

                override fun onComplete() {
                    Logger.d(TAG, "听写结束提示播放完成")
                }

                override fun onError(error: String) {
                    Logger.e(TAG, "播放听写结束提示失败: $error")
                }
            },
        )
    }

    /**
     * 提交错题
     */
    fun submitErrorWords(
        getSelectedWords: () -> List<String>,
    ) {
        val allWords = getDictatedWords()
        if (allWords.isEmpty()) {
            com.smartstudy.utils.ToastHelper.showShort(activity, com.smartstudy.R.string.no_questions_to_save)
            return
        }

        // 获取用户勾选的错题
        val selectedWords = getSelectedWords()
        val selectedWordsSet = selectedWords.toSet()

        // 构建所有题目及其状态的列表
        val questions = buildQuestionRecords(selectedWordsSet)

        // 保存所有题目及其状态（通过ViewModel处理）
        viewModel.submitErrorWords(dictationType, questions)
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        // 清理逻辑由 Activity 处理
    }
}
