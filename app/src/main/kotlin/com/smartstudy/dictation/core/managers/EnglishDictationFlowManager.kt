package com.smartstudy.dictation.core.managers

import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.core.handlers.DictationViewManager
import com.smartstudy.dictation.core.handlers.DictationVoiceManager
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.dictation.english.AnswerAdapter
import com.smartstudy.dictation.english.WordWithTranslation
import com.smartstudy.dictation.ui.DictationViewModel

/**
 * 英文听写流程管理器
 */
class EnglishDictationFlowManager(
    activity: AppCompatActivity,
    viewModel: DictationViewModel,
    viewManager: DictationViewManager,
    voiceHandler: DictationVoiceManager,
    ttsService: ITtsService,
    errorWordStorage: DictationRecordStorage,
    ttsIntervalMillis: Long,
    private val onTranslationComplete: (String, Int) -> Unit, // word, index
    onDictationComplete: () -> Unit,
    onError: (String) -> Unit,
) : DictationFlowManager(
        activity = activity,
        viewModel = viewModel,
        viewManager = viewManager,
        voiceHandler = voiceHandler,
        ttsService = ttsService,
        errorWordStorage = errorWordStorage,
        dictationType = "english",
        ttsIntervalMillis = ttsIntervalMillis,
        onDictationComplete = onDictationComplete,
        onError = onError,
    ) {
    override fun getWords(): List<String> {
        return viewModel.uiState.value.recognitionWords
    }

    override fun getDictatedWords(): List<*> {
        return viewModel.uiState.value.dictatedWordsEnglish
    }

    override fun recordCurrentWord(word: String) {
        // 英文听写需要先添加英文单词，翻译稍后更新
        viewModel.addEnglishDictatedWord(WordWithTranslation(word, ""))
    }

    override fun createAnswerAdapter(): RecyclerView.Adapter<*> {
        val words = getDictatedWords() as List<WordWithTranslation>
        return AnswerAdapter(words)
    }

    override fun buildQuestionRecords(selectedWords: Set<String>): List<QuestionRecord> {
        val allWords = getDictatedWords() as List<WordWithTranslation>
        return allWords.map { wordWithTranslation ->
            QuestionRecord(
                word = wordWithTranslation.englishWord,
                isCorrect = !selectedWords.contains(wordWithTranslation.englishWord),
                chineseTranslation = wordWithTranslation.chineseTranslation,
                sourceType = sourceType,
            )
        }
    }

    /**
     * 翻译并播放词语（英文听写特有）
     */
    fun translateAndSpeak(
        englishWord: String,
        wordIndex: Int,
    ) {
        viewManager.tvStatus.text = "正在翻译..."
        viewManager.tvStatus.visibility = android.view.View.VISIBLE
        // 调用ViewModel进行翻译，结果通过Event通知
        viewModel.translateWord(englishWord, wordIndex)
    }

    /**
     * 播放翻译后的中文词语
     */
    fun speakTranslatedWord(chineseText: String) {
        viewManager.tvStatus.setText(com.smartstudy.R.string.speak_keywords)
        viewManager.tvStatus.visibility = android.view.View.VISIBLE

        // 使用VoiceHandler播放词语
        voiceHandler.speakWord(
            chineseText,
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
     * 开始听写（英文听写特有，需要处理翻译）
     */
    fun startDictationWithTranslation(
        setKeepScreenOn: (Boolean) -> Unit,
        startQuestionTimeoutTimer: () -> Unit,
    ) {
        // 设置听写状态为活动，保持屏幕常亮，启动单题超时计时器
        viewModel.setDictationActive(true)
        setKeepScreenOn(true)
        startQuestionTimeoutTimer()

        // 听写开始后显示顶部进度卡片和温馨提示（不显示识别卡片）
        viewManager.cardProgress.visibility = android.view.View.VISIBLE
        viewManager.cardRecognition.visibility = android.view.View.GONE
        viewManager.cardTip.visibility = android.view.View.VISIBLE
        // 设置提示文本为"请对我说下一个"
        viewManager.tvTip.text = activity.getString(com.smartstudy.R.string.dictation_tip_next)

        val words = getWords()
        val currentWordIndex = viewModel.uiState.value.currentWordIndex

        if (currentWordIndex >= words.size) {
            // 听写完成，直接显示答案
            completeDictation(setKeepScreenOn)
            return
        }

        val englishWord = words[currentWordIndex]

        // 更新UI - 只显示进度，不显示听写内容，防止小朋友看到答案
        viewManager.tvWordIndex.text =
            activity.getString(
                com.smartstudy.R.string.word_index,
                currentWordIndex + 1,
                words.size,
            )
        viewManager.tvCurrentWord.visibility = android.view.View.GONE
        viewManager.tvWordIndex.visibility = android.view.View.VISIBLE

        // 先停止之前的监听（不要清空 ASR UI，让 final 结果按计时隐藏，避免"一闪而过"）
        voiceHandler.stopListening(clearAsrUi = false)

        // 检查是否已经有翻译（错题听写时，dictatedWords 已经预先填充了翻译）
        val currentDictatedWords = getDictatedWords() as List<WordWithTranslation>
        if (currentWordIndex < currentDictatedWords.size) {
            val wordWithTranslation = currentDictatedWords[currentWordIndex]
            // 如果翻译为空，需要先翻译（错题听写时，翻译可能还没有加载）
            if (wordWithTranslation.chineseTranslation.isEmpty()) {
                com.smartstudy.core.Logger.d(
                    "EnglishDictationFlowManager",
                    "错题听写：翻译为空，需要翻译: ${wordWithTranslation.englishWord}",
                )
                // 翻译缺失的单词
                translateAndSpeak(englishWord, currentWordIndex)
            } else {
                // 错题听写：直接使用保存的翻译
                com.smartstudy.core.Logger.d(
                    "EnglishDictationFlowManager",
                    "错题听写：使用已保存的翻译: ${wordWithTranslation.englishWord} -> ${wordWithTranslation.chineseTranslation}",
                )
                // 直接播放TTS
                speakTranslatedWord(wordWithTranslation.chineseTranslation)
            }
        } else {
            // 普通听写：需要翻译
            // 记录听写顺序（先添加英文单词，中文翻译稍后更新）
            recordCurrentWord(englishWord)
            // 先翻译，然后播放TTS（翻译成功后会更新对应的中文翻译）
            translateAndSpeak(englishWord, currentDictatedWords.size)
        }
    }

    /**
     * 进入下一个词语（重写以使用英文听写特有的方法）
     */
    override fun goToNextWord(
        setKeepScreenOn: (Boolean) -> Unit,
        startQuestionTimeoutTimer: () -> Unit,
        stopQuestionTimeoutTimer: () -> Unit,
    ) {
        stopQuestionTimeoutTimer() // 停止当前词语的计时器
        viewModel.goToNextWord()
        // 使用英文听写特有的方法，而不是基类的 startDictation
        // 这样可以确保始终使用翻译后的中文进行播报
        startDictationWithTranslation(setKeepScreenOn, startQuestionTimeoutTimer)
    }
}
