package com.smartstudy.dictation.english

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.BaseDictationActivity
import com.smartstudy.dictation.core.managers.DictationEventCallbacks
import com.smartstudy.dictation.core.managers.DictationEventObserver
import com.smartstudy.dictation.core.managers.DictationUIManager
import com.smartstudy.dictation.core.managers.EnglishDictationFlowManager
import com.smartstudy.utils.ToastHelper

/**
 * 英语听写功能Activity
 */
class EnglishDictationActivity : BaseDictationActivity() {
    companion object {
        private const val TAG = "EnglishDictationActivity"
    }

    // 英文听写特有的数据
    private var answerAdapter: AnswerAdapter? = null

    // 听写流程管理器
    private lateinit var flowManager: EnglishDictationFlowManager

    // UI管理器
    private lateinit var uiManager: DictationUIManager

    // 事件观察器
    private lateinit var eventObserver: DictationEventObserver

    // 状态访问器（委托给 viewModel）
    private val dictatedWords: List<WordWithTranslation>
        get() = viewModel.uiState.value.dictatedWordsEnglish

    override fun getLayoutResId(): Int = R.layout.activity_english_dictation

    override fun getDictationType(): String = AppConfig.TYPE_ENGLISH

    override fun getTtsIntervalMillis(): Long = AppConfig.TTS_INTERVAL_MILLIS_ENGLISH

    override fun initServices() {
        // 所有服务已通过依赖注入初始化
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
    }

    /**
     * 初始化听写流程管理器
     */
    private fun initFlowManager() {
        flowManager =
            EnglishDictationFlowManager(
                activity = this,
                viewModel = viewModel,
                viewManager = viewManager,
                voiceHandler = voiceHandler,
                ttsService = ttsService,
                errorWordStorage = errorWordStorage,
                ttsIntervalMillis = getTtsIntervalMillis(),
                onTranslationComplete = { chineseTranslation, wordIndex ->
                    // 翻译成功后，播放TTS
                    flowManager.speakTranslatedWord(chineseTranslation)
                },
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
                            setupErrorDictationWords(words)
                            viewManager.hidePhotoAndButtons()
                            prepareForErrorDictation()
                        },
                        onTranslationComplete = { wordIndex, englishWord, chineseTranslation ->
                            // 更新对应的中文翻译（通过 ViewModel）
                            viewModel.updateEnglishDictatedWord(
                                wordIndex,
                                WordWithTranslation(englishWord, chineseTranslation),
                            )
                            // 翻译成功后，播放TTS（使用flowManager）
                            flowManager.speakTranslatedWord(chineseTranslation)
                        },
                        onTranslationFailed = { englishWord, error ->
                            // 即使翻译失败，也尝试开始监听，让用户可以继续（中文翻译保持为空）
                            startListening()
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
        // ImageUnderstandingService 已按 ENGLISH_DICTATION 做过过滤与归一化，这里只做保存与打乱顺序
        val shuffledWords = words.shuffled()
        viewModel.setEnglishWords(shuffledWords)
        viewModel.clearDictatedWords()

        Logger.d(TAG, "识别到的英文词语: ${shuffledWords.joinToString(", ")}")

        // 设置来源类型为"new"（新的听写）
        flowManager.setSourceType("new")

        // 等待"开始"关键词后再开始听写
        currentWordIndex = 0
        isWaitingForStart = true
        viewManager.updateStatusText("请说\"开始\"或\"开始听写\"来开始听写")

        // 通过语音告诉小朋友"我已经准备好了，请对我说开始听写！"
        speakReadyPrompt()
    }

    override fun handleManualInput(words: List<String>) {
        // 手动输入现在通过 ViewModel 事件机制处理，与图片识别流程保持一致
        // 调用 onImageUnderstandingSuccess 处理主要逻辑
        onImageUnderstandingSuccess(words)

        // 手动输入特有的UI处理：隐藏照片和按钮
        viewManager.hidePhotoAndButtons()

        Logger.d(TAG, "手动输入的英文词语: ${words.joinToString(", ")}")
    }

    override fun startDictation() {
        // 委托给flowManager处理（英文听写需要处理翻译）
        flowManager.startDictationWithTranslation(
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
        viewModel.startErrorDictation(AppConfig.TYPE_ENGLISH, count)
    }

    /**
     * 设置错题听写的词语列表
     * @param words 单词列表（英文单词，翻译需要后续加载）
     */
    private fun setupErrorDictationWords(words: List<String>) {
        // 打乱顺序
        val shuffled = words.shuffled()
        viewModel.setEnglishWords(shuffled)
        // 预先填充空翻译信息到 dictatedWords（按打乱后的顺序）
        viewModel.setEnglishDictatedWords(shuffled.map { WordWithTranslation(it, "") })

        Logger.d(TAG, "错题听写词语: ${shuffled.joinToString(", ")}")
    }

    /**
     * 准备开始错题听写
     */
    private fun prepareForErrorDictation() {
        // 设置听写索引为0，等待"开始"关键词
        currentWordIndex = 0
        isWaitingForStart = true
        viewManager.updateStatusText("请说\"开始\"或\"开始听写\"来开始听写")

        // 播放准备就绪提示
        speakReadyPrompt()
    }
}

/**
 * 英文单词和中文翻译对
 */
data class WordWithTranslation(
    val englishWord: String,
    val chineseTranslation: String,
)

/**
 * 答案列表适配器（英文听写）
 */
class AnswerAdapter(private val words: List<WordWithTranslation>) : RecyclerView.Adapter<AnswerAdapter.AnswerViewHolder>() {
    private val selectedPositions = mutableSetOf<Int>()
    private var isCheckboxesEnabled = true

    class AnswerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: android.widget.CheckBox = itemView.findViewById(R.id.cbSelect)
        val tvAnswerWord: TextView = itemView.findViewById(R.id.tvAnswerWord)
        val tvAnswerTranslation: TextView = itemView.findViewById(R.id.tvAnswerTranslation)
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
        val wordWithTranslation = words[position]
        holder.tvAnswerWord.text = wordWithTranslation.englishWord

        // 显示中文翻译（如果有）
        if (wordWithTranslation.chineseTranslation.isNotEmpty()) {
            holder.tvAnswerTranslation.text = wordWithTranslation.chineseTranslation
            holder.tvAnswerTranslation.visibility = View.VISIBLE
        } else {
            holder.tvAnswerTranslation.visibility = View.GONE
        }

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
     * 获取选中的词语列表（返回英文单词）
     */
    fun getSelectedWords(): List<String> {
        return selectedPositions.sorted().map { words[it].englishWord }
    }

    /**
     * 设置复选框是否可用
     */
    fun setCheckboxesEnabled(enabled: Boolean) {
        isCheckboxesEnabled = enabled
        notifyDataSetChanged()
    }
}
