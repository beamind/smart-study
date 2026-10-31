package com.smartstudy.dictation.chinese

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.BaseDictationActivity
import com.smartstudy.dictation.core.managers.ChineseDictationFlowManager
import com.smartstudy.dictation.core.managers.DictationEventCallbacks
import com.smartstudy.dictation.core.managers.DictationEventObserver
import com.smartstudy.dictation.core.managers.DictationUIManager
import com.smartstudy.utils.ToastHelper

/**
 * 语文听写功能 Activity
 */
class ChineseDictationActivity : BaseDictationActivity() {
    companion object {
        private const val TAG = "ChineseDictationActivity"
    }

    // 中文听写特有的数据
    private var answerAdapter: AnswerAdapter? = null

    // 听写流程管理器
    private lateinit var flowManager: ChineseDictationFlowManager

    // UI管理器
    private lateinit var uiManager: DictationUIManager

    // 事件观察器
    private lateinit var eventObserver: DictationEventObserver

    private val dictatedWords: List<String>
        get() = viewModel.uiState.value.dictatedWordsChinese

    override fun getLayoutResId(): Int = R.layout.activity_chinese_dictation

    override fun getDictationType(): String = AppConfig.TYPE_CHINESE

    override fun getTtsIntervalMillis(): Long = AppConfig.TTS_INTERVAL_MILLIS_CHINESE

    override fun initServices() {
        // 服务已通过依赖注入初始化，这里只需要显示初始化完成提示
        try {
            Logger.d(TAG, "服务已通过依赖注入初始化")

            // 初始化UI管理器
            uiManager =
                DictationUIManager(
                    activity = this,
                    viewManager = viewManager,
                )

            // 初始化听写流程管理器
            initFlowManager()

            // 初始化事件观察器
            initEventObserver()

            // 显示初始化提示并自动隐藏（委托给UI管理器）
            uiManager.showInitHint("初始化识别")
        } catch (e: Exception) {
            Logger.e(TAG, "初始化服务失败", e)
            if (::uiManager.isInitialized) {
                uiManager.showInitError(e.message ?: getString(R.string.error_io))
            } else {
                // 如果UI管理器未初始化，使用临时处理
                ToastHelper.showLong(this, R.string.init_failed, e.message ?: getString(R.string.error_io))
            }
            // 不退出 Activity，让用户能看到错误信息
        }
    }

    /**
     * 初始化听写流程管理器
     */
    private fun initFlowManager() {
        flowManager =
            ChineseDictationFlowManager(
                activity = this,
                viewModel = viewModel,
                viewManager = viewManager,
                voiceHandler = voiceHandler,
                ttsService = ttsService,
                errorWordStorage = errorWordStorage,
                ttsIntervalMillis = getTtsIntervalMillis(),
                onDictationComplete = {
                    showAnswers()
                },
                onError = { error ->
                    ToastHelper.showShort(this, R.string.unknown_error)
                    ToastHelper.showShort(this, error)
                },
            )
        // 默认来源类型为"new"（新的听写）
        flowManager.setSourceType("new")
    }

    /**
     * 初始化事件观察器
     */
    private fun initEventObserver() {
        eventObserver =
            DictationEventObserver(
                activity = this,
                viewModel = viewModel,
                viewManager = viewManager,
                uiManager = uiManager,
                callbacks =
                    DictationEventCallbacks(
                        onLoadErrorWordsSuccess = { words ->
                            // 设置听写词语列表
                            val shuffledWords = words.shuffled()
                            viewModel.setChineseWords(shuffledWords)
                            viewModel.clearDictatedWords()

                            Logger.d(TAG, "错题听写词语: ${shuffledWords.joinToString(", ")}")

                            // 隐藏照片和按钮
                            viewManager.hidePhotoAndButtons()

                            // 设置听写索引为0，等待"开始"关键词
                            currentWordIndex = 0
                            isWaitingForStart = true
                            viewManager.updateStatusText(getString(R.string.dictation_waiting_start))

                            // 播放准备就绪提示
                            speakReadyPrompt()
                        },
                        onSubmitSuccess = {
                            // 保存成功后，将按钮改为"返回"
                            isSubmitted = true
                        },
                        onShowProgress = { show ->
                            showProgress(show)
                        },
                        getAnswerAdapter = {
                            answerAdapter
                        },
                    ),
            )
        eventObserver.startObserving()
    }

    override fun onImageUnderstandingSuccess(words: List<String>) {
        // ImageUnderstandingService 按照 CHINESE_DICTATION 模式处理，清除之前的词语并添加新的词语
        val shuffledWords = words.shuffled()
        viewModel.setChineseWords(shuffledWords)
        viewModel.clearDictatedWords()

        Logger.d(TAG, "识别到的中文词语: ${shuffledWords.joinToString(", ")}")

        // 设置来源类型为"new"（新的听写）
        flowManager.setSourceType("new")

        // 设置听写索引为0，等待"开始"关键词
        currentWordIndex = 0
        isWaitingForStart = true
        viewManager.updateStatusText("请说\"开始\"或\"开始听写\"来开始听写")

        // 播放准备就绪提示
        speakReadyPrompt()
    }

    override fun handleManualInput(words: List<String>) {
        // 手动输入现在通过 ViewModel 事件机制处理，与图片识别流程保持一致
        // 调用 onImageUnderstandingSuccess 处理主要逻辑
        onImageUnderstandingSuccess(words)

        // 手动输入特有的UI处理：隐藏照片和按钮
        viewManager.hidePhotoAndButtons()

        Logger.d(TAG, "手动输入的中文词语: ${words.joinToString(", ")}")
    }

    override fun startDictation() {
        // 委托给flowManager处理
        flowManager.startDictation(
            setKeepScreenOn = { enable -> setKeepScreenOn(enable) },
            startQuestionTimeoutTimer = {
                startQuestionTimeoutTimer()
            },
        )
    }

    override fun showAnswers() {
        // 委托给flowManager处理
        flowManager.showAnswers()
        // 保存适配器引用以便后续使用
        answerAdapter = rvAnswers.adapter as? AnswerAdapter
    }

    override fun submitErrorWords() {
        val adapter = answerAdapter ?: return
        // 委托给flowManager处理
        flowManager.submitErrorWords(
            getSelectedWords = { adapter.getSelectedWords() },
        )
    }

    override fun createAnswerAdapter(): RecyclerView.Adapter<*> {
        return AnswerAdapter(dictatedWords)
    }

    /**
     * 进入下一个词语（重写以使用flowManager）
     */
    override fun goToNextWord() {
        // 委托给flowManager处理
        flowManager.goToNextWord(
            setKeepScreenOn = { enable -> setKeepScreenOn(enable) },
            startQuestionTimeoutTimer = {
                startQuestionTimeoutTimer()
            },
            stopQuestionTimeoutTimer = {
                stopQuestionTimeoutTimer()
            },
        )
    }

    override fun showErrorDictationDialog() {
        uiManager.showErrorDictationDialog { count ->
            startErrorDictation(count)
        }
    }

    /**
     * 开始错题听写
     */
    private fun startErrorDictation(count: Int) {
        // 设置来源类型为"error"（错题听写）
        flowManager.setSourceType("error")
        showProgress(true)
        viewManager.updateStatusText("正在加载错题...")
        // 调用ViewModel加载错题，结果通过Event通知
        viewModel.startErrorDictation(AppConfig.TYPE_CHINESE, count)
    }
}

/**
 * 答案列表适配器（中文听写）
 */
class AnswerAdapter(private val words: List<String>) : RecyclerView.Adapter<AnswerAdapter.AnswerViewHolder>() {
    private val selectedPositions = mutableSetOf<Int>()
    private var isCheckboxesEnabled = true

    class AnswerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: android.widget.CheckBox = itemView.findViewById(R.id.cbSelect)
        val tvAnswerWord: TextView = itemView.findViewById(R.id.tvAnswerWord)
        val tvAnswerTranslation: TextView? = itemView.findViewById(R.id.tvAnswerTranslation)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): AnswerViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_answer, parent, false)
        return AnswerViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: AnswerViewHolder,
        position: Int,
    ) {
        val word = words[position]
        holder.tvAnswerWord.text = word

        // 中文听写不显示翻译，隐藏翻译 TextView（如果存在）
        holder.tvAnswerTranslation?.visibility = View.GONE

        // 先清除监听器，避免在设置状态时触发
        holder.cbSelect.setOnCheckedChangeListener(null)

        // 根据 selectedPositions 设置复选框状态
        val isSelected = selectedPositions.contains(position)
        holder.cbSelect.isChecked = isSelected

        // 设置复选框是否可用
        holder.cbSelect.isEnabled = isCheckboxesEnabled

        // 设置复选框点击事件（只有在可用时才设置）
        if (isCheckboxesEnabled) {
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPositions.add(position)
                } else {
                    selectedPositions.remove(position)
                }
            }
        } else {
            holder.cbSelect.setOnCheckedChangeListener(null)
        }
    }

    override fun getItemCount(): Int = words.size

    /**
     * 获取选中的词语列表
     */
    fun getSelectedWords(): List<String> {
        return selectedPositions.sorted().map { words[it] }
    }

    /**
     * 设置复选框是否可用
     */
    fun setCheckboxesEnabled(enabled: Boolean) {
        isCheckboxesEnabled = enabled
        notifyDataSetChanged()
    }
}
