package com.smartstudy.dictation.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.AppResult
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.FilePermissionException
import com.smartstudy.dictation.core.ImageUnderstandingService
import com.smartstudy.dictation.core.ImageUnderstandingService.ImageUnderstandingMode
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.core.TranslationService
import com.smartstudy.dictation.core.VoiceRecognitionService
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.dictation.english.WordWithTranslation
import com.smartstudy.dictation.repository.DictationRepository
import com.smartstudy.user.usecases.UserUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 听写功能的 ViewModel
 * 管理听写相关的业务逻辑和状态
 *
 * 职责：
 * - 管理图片识别状态和结果
 * - 管理当前用户信息
 * - 提供 UI 状态（StateFlow）
 *
 * 使用示例：
 * ```
 * val viewModel: DictationViewModel by viewModels()
 * // 使用 StateFlow
 * lifecycleScope.launch {
 *     viewModel.uiState.collect { state ->
 *         // 处理状态变化
 *     }
 * }
 * ```
 *
 * @param ttsService TTS 服务
 * @param imageUnderstandingService 图片理解服务
 * @param voiceRecognitionService 语音识别服务
 * @param errorWordStorage 错词存储服务
 * @param userUseCase 用户业务逻辑用例
 * @param dictationRepository 听写数据仓库
 * @param translationService 翻译服务
 */
class DictationViewModel(
    private val context: Context,
    @Suppress("UNUSED_PRIVATE_PROPERTY") private val ttsService: ITtsService,
    private val imageUnderstandingService: ImageUnderstandingService,
    private val voiceRecognitionService: VoiceRecognitionService,
    private val errorWordStorage: DictationRecordStorage,
    private val userUseCase: UserUseCase,
    private val dictationRepository: DictationRepository,
    private val translationService: TranslationService,
) : ViewModel() {
    companion object {
        private const val TAG = "DictationViewModel"
    }

    // 统一的状态管理（使用 StateFlow，更好的协程支持）
    private val _uiState = MutableStateFlow(DictationState.initial())
    val uiState: StateFlow<DictationState> = _uiState.asStateFlow()

    // 一次性UI事件（使用SharedFlow，避免重复触发）
    private val _events = MutableSharedFlow<DictationEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DictationEvent> = _events.asSharedFlow()

    init {
        // 初始化当前用户
        updateCurrentUser()
    }

    /**
     * 优化图片（在后台线程执行）
     * @param bitmap 原始图片
     * @param onSuccess 成功回调，返回优化后的图片
     * @param onError 错误回调
     */
    fun optimizeImage(
        bitmap: Bitmap,
        onSuccess: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val optimizedBitmap = com.smartstudy.dictation.core.ImageProcessingHelper.optimizeBitmapForRecognition(bitmap)
                withContext(Dispatchers.Main) {
                    onSuccess(optimizedBitmap)
                }
            } catch (e: OutOfMemoryError) {
                Logger.e(TAG, "图片优化失败：内存不足", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            } catch (e: IllegalArgumentException) {
                Logger.e(TAG, "图片优化失败：参数错误", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "图片优化失败：未知错误", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    /**
     * 识别图片中的词语
     * 使用Repository统一处理数据访问和错误处理
     * 通过Event通知识别结果，避免Activity持有协程引用
     */
    fun recognizeText(
        bitmap: Bitmap,
        mode: ImageUnderstandingMode,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, recognitionError = null) }

            val result = dictationRepository.recognizeWords(bitmap, mode)

            when (result) {
                is AppResult.Success -> {
                    val words = result.data
                    _uiState.update {
                        it.copy(
                            recognitionWords = words,
                            recognitionError = null,
                            isProcessing = false,
                        )
                    }
                    // 通过Event通知识别成功
                    _events.emit(DictationEvent.ImageRecognitionComplete(words))
                }
                is AppResult.Failure -> {
                    val error = result.error
                    _uiState.update {
                        it.copy(
                            recognitionError = error.message,
                            isProcessing = false,
                        )
                    }
                    // 通过Event通知识别失败
                    _events.emit(DictationEvent.ImageRecognitionFailed(error))
                }
            }
        }
    }

    /**
     * 更新当前用户信息
     */
    fun updateCurrentUser() {
        viewModelScope.launch {
            when (val result = userUseCase.ensureDefaultUser()) {
                is AppResult.Success -> {
                    val currentUser = result.data
                    _uiState.update {
                        it.copy(
                            currentUserId = currentUser.id,
                            currentUserName = currentUser.name,
                        )
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "获取当前用户失败", result.error.cause)
                    _uiState.update {
                        it.copy(
                            currentUserId = "",
                            currentUserName = "游客",
                        )
                    }
                }
            }
        }
    }

    /**
     * 设置听写激活状态
     */
    fun setDictationActive(active: Boolean) {
        _uiState.update { it.copy(isDictationActive = active) }
    }

    /**
     * 设置等待开始状态
     */
    fun setWaitingForStart(waiting: Boolean) {
        _uiState.update { it.copy(isWaitingForStart = waiting) }
    }

    /**
     * 更新当前词语索引
     */
    fun updateWordIndex(index: Int) {
        _uiState.update { state ->
            if (index < 0) {
                Logger.w(TAG, "当前词语索引异常（$index），重置为0")
                state.copy(currentWordIndex = 0)
            } else {
                state.copy(currentWordIndex = index)
            }
        }
    }

    /**
     * 增加词语索引
     */
    fun incrementWordIndex() {
        _uiState.update { state ->
            state.copy(currentWordIndex = state.currentWordIndex + 1)
        }
    }

    /**
     * 进入下一个词语
     * 只有在听写激活时才允许进入下一个词语
     */
    fun goToNextWord() {
        val currentState = _uiState.value
        if (!currentState.isDictationActive) {
            Logger.w(TAG, "听写未激活，忽略进入下一个词语的请求")
            return
        }

        Logger.d(TAG, "进入下一个词语（当前索引：${currentState.currentWordIndex}）")
        _uiState.update { state ->
            val newIndex = state.currentWordIndex + 1
            if (newIndex < 0) {
                Logger.w(TAG, "当前词语索引异常（$newIndex），重置为0")
                state.copy(currentWordIndex = 0)
            } else {
                state.copy(currentWordIndex = newIndex)
            }
        }
    }

    /**
     * 重置词语索引
     */
    fun resetWordIndex() {
        _uiState.update { it.copy(currentWordIndex = 0) }
    }

    /**
     * 设置提交状态
     */
    fun setSubmitted(submitted: Boolean) {
        _uiState.update { it.copy(isSubmitted = submitted) }
    }

    /**
     * 重置听写状态
     */
    fun resetDictationState() {
        _uiState.update { it.resetDictationState() }
        clearRecognitionResult()
    }

    /**
     * 设置前台状态
     */
    fun setInForeground(inForeground: Boolean) {
        _uiState.update { it.copy(isInForeground = inForeground) }
    }

    /**
     * 更新状态文本
     */
    fun updateStatusText(text: String) {
        _uiState.update { it.copy(statusText = text) }
    }

    /**
     * 更新ASR识别结果
     */
    fun updateAsrResult(
        text: String?,
        status: String?,
    ) {
        _uiState.update {
            it.copy(
                asrText = text ?: "",
                asrStatus = status,
            )
        }
    }

    /**
     * 清除ASR识别结果
     */
    fun clearAsrResult() {
        _uiState.update { it.clearAsrResult() }
    }

    /**
     * 清除识别错误
     */
    fun clearError() {
        _uiState.update { it.copy(recognitionError = null) }
    }

    /**
     * 清除识别结果
     */
    fun clearRecognitionResult() {
        _uiState.update { it.clearRecognitionResult() }
    }

    /**
     * 设置英文听写的词语列表
     */
    fun setEnglishWords(words: List<String>) {
        _uiState.update { it.copy(recognitionWords = words) }
    }

    /**
     * 设置中文听写的词语列表
     */
    fun setChineseWords(words: List<String>) {
        _uiState.update { it.copy(recognitionWords = words) }
    }

    /**
     * 设置手动输入的词语列表
     * 与图片识别流程保持一致，更新状态并发送事件
     * @param words 手动输入的词语列表
     */
    fun setManualInputWords(words: List<String>) {
        _uiState.update {
            it.copy(
                recognitionWords = words,
                recognitionError = null,
                isProcessing = false,
            )
        }
        viewModelScope.launch {
            _events.emit(DictationEvent.WordsInputComplete(words))
        }
    }

    /**
     * 添加英文听写的听写顺序记录
     */
    fun addEnglishDictatedWord(word: WordWithTranslation) {
        _uiState.update { state ->
            state.copy(dictatedWordsEnglish = state.dictatedWordsEnglish + word)
        }
    }

    /**
     * 更新英文听写的听写顺序记录（根据索引）
     */
    fun updateEnglishDictatedWord(
        index: Int,
        word: WordWithTranslation,
    ) {
        _uiState.update { state ->
            if (index >= 0 && index < state.dictatedWordsEnglish.size) {
                val updated = state.dictatedWordsEnglish.toMutableList()
                updated[index] = word
                state.copy(dictatedWordsEnglish = updated)
            } else {
                state
            }
        }
    }

    /**
     * 设置英文听写的听写顺序列表
     */
    fun setEnglishDictatedWords(words: List<WordWithTranslation>) {
        _uiState.update { it.copy(dictatedWordsEnglish = words) }
    }

    /**
     * 添加中文听写的听写顺序记录
     */
    fun addChineseDictatedWord(word: String) {
        _uiState.update { state ->
            state.copy(dictatedWordsChinese = state.dictatedWordsChinese + word)
        }
    }

    /**
     * 设置中文听写的听写顺序列表
     */
    fun setChineseDictatedWords(words: List<String>) {
        _uiState.update { it.copy(dictatedWordsChinese = words) }
    }

    /**
     * 清除所有听写顺序记录
     */
    fun clearDictatedWords() {
        _uiState.update {
            it.copy(
                dictatedWordsEnglish = emptyList(),
                dictatedWordsChinese = emptyList(),
            )
        }
    }

    /**
     * 重置所有状态（类似于 DictationStateManager.reset()）
     */
    fun reset() {
        _uiState.update {
            it.copy(
                isDictationActive = false,
                isWaitingForStart = false,
                isSubmitted = false,
                currentWordIndex = 0,
            )
        }
        Logger.d(TAG, "状态已重置")
    }

    /**
     * 翻译单词（英文到中文）
     * @param englishWord 英文单词
     * @param wordIndex 在单词列表中的索引，用于更新翻译结果
     */
    fun translateWord(
        englishWord: String,
        wordIndex: Int,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true, statusText = "正在翻译...") }

            val result = translationService.translate(englishWord)
            when (result) {
                is AppResult.Success -> {
                    val translatedText = result.data
                    Logger.d(TAG, "翻译结果: $englishWord -> $translatedText")
                    _uiState.update { it.copy(isTranslating = false) }
                    _events.emit(
                        DictationEvent.TranslationComplete(
                            englishWord = englishWord,
                            chineseTranslation = translatedText,
                            wordIndex = wordIndex,
                        ),
                    )
                }

                is AppResult.Failure -> {
                    val error = result.error.message
                    Logger.e(TAG, "翻译失败: $error", result.error.cause)
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            statusText = "翻译失败: $error",
                        )
                    }
                    _events.emit(
                        DictationEvent.TranslationFailed(
                            englishWord = englishWord,
                            error = error,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 批量翻译单词
     * @param words 需要翻译的单词列表
     * @return 翻译结果映射（单词 -> 翻译）
     */
    fun translateWordsBatch(words: List<String>) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTranslating = true,
                    statusText = "正在翻译 ${words.size} 个单词...",
                    translationProgress = Pair(0, words.size),
                )
            }

            val results =
                withContext(Dispatchers.IO) {
                    val translationResults = mutableMapOf<String, String>()
                    val total = words.size

                    // 并发执行所有翻译请求
                    val deferredResults =
                        words.mapIndexed { index, word ->
                            async {
                                val result = translationService.translate(word)
                                val translation =
                                    when (result) {
                                        is AppResult.Success -> result.data
                                        is AppResult.Failure -> {
                                            Logger.e(TAG, "翻译失败: $word -> ${result.error.message}", result.error.cause)
                                            null
                                        }
                                    }
                                withContext(Dispatchers.Main) {
                                    _uiState.update {
                                        it.copy(translationProgress = Pair(index + 1, total))
                                    }
                                    _events.emit(
                                        DictationEvent.TranslationProgress(
                                            current = index + 1,
                                            total = total,
                                        ),
                                    )
                                }
                                word to translation
                            }
                        }

                    // 等待所有翻译完成
                    val allResults = deferredResults.awaitAll()

                    // 只保留翻译成功的单词
                    allResults.forEach { (word, translation) ->
                        if (translation != null && translation.isNotEmpty()) {
                            translationResults[word] = translation
                        }
                    }

                    translationResults
                }

            _uiState.update {
                it.copy(
                    isTranslating = false,
                    translationProgress = null,
                )
            }

            Logger.d(TAG, "批量翻译完成，成功 ${results.size}/${words.size} 个")
        }
    }

    /**
     * 提交错题
     * @param dictationType 听写类型（"chinese" 或 "english"）
     * @param questions 题目记录列表
     */
    fun submitErrorWords(
        dictationType: String,
        questions: List<QuestionRecord>,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }

            try {
                val currentUserResult = userUseCase.getCurrentUser()
                val userName =
                    when (currentUserResult) {
                        is AppResult.Success -> currentUserResult.data?.name ?: "游客"
                        is AppResult.Failure -> "游客"
                    }
                withContext(Dispatchers.IO) {
                    errorWordStorage.saveQuestions(userName, dictationType, questions)
                }
                val correctCount = questions.count { it.isCorrect }
                val errorCount = questions.size - correctCount
                _uiState.update { it.copy(isSubmitting = false, isSubmitted = true) }
                _events.emit(
                    DictationEvent.SubmitSuccess(
                        totalCount = questions.size,
                        correctCount = correctCount,
                        errorCount = errorCount,
                    ),
                )
                Logger.d(TAG, "题目保存成功，共 ${questions.size} 个，正确 $correctCount 个，错误 $errorCount 个")
            } catch (e: FilePermissionException) {
                Logger.e(TAG, "保存题目失败：权限问题", e)
                _uiState.update { it.copy(isSubmitting = false) }
                _events.emit(DictationEvent.SubmitPermissionError(e.fileName))
            } catch (e: IOException) {
                Logger.e(TAG, "IO错误，保存题目失败", e)
                _uiState.update { it.copy(isSubmitting = false) }
                _events.emit(DictationEvent.SubmitIoError(e.message ?: context.getString(R.string.error_io)))
            } catch (e: SecurityException) {
                Logger.e(TAG, "权限不足，保存题目失败", e)
                _uiState.update { it.copy(isSubmitting = false) }
                _events.emit(DictationEvent.SubmitIoError(e.message ?: context.getString(R.string.error_permission_denied)))
            }
        }
    }

    /**
     * 加载错题统计信息
     * @param dictationType 听写类型（"chinese" 或 "english"）
     * @return 错题统计列表，如果加载失败或为空则返回null
     */
    fun loadErrorWordStats(dictationType: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingErrorWords = true,
                    statusText = "正在加载错题...",
                )
            }

            val currentUserResult = userUseCase.getCurrentUser()
            val userName =
                when (currentUserResult) {
                    is AppResult.Success -> currentUserResult.data?.name ?: "游客"
                    is AppResult.Failure -> "游客"
                }

            when (val result = dictationRepository.loadErrorWordStats(userName, dictationType)) {
                is AppResult.Success -> {
                    val errorStats = result.data
                    _uiState.update { it.copy(isLoadingErrorWords = false) }

                    if (errorStats.isEmpty()) {
                        _events.emit(DictationEvent.NoErrorWords)
                    } else {
                        // 返回统计信息，由调用者处理
                        // 这里可以通过StateFlow传递，或者通过Event传递
                        // 暂时通过Event传递选中的单词列表（需要调用者进一步处理）
                    }
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "加载错题失败", result.error.cause)
                    _uiState.update { it.copy(isLoadingErrorWords = false) }
                    val errorMessage = result.error.message ?: context.getString(R.string.error_io)
                    _events.emit(DictationEvent.LoadErrorWordsFailed(errorMessage))
                }
            }
        }
    }

    /**
     * 开始错题听写（加载并选择错题）
     * @param dictationType 听写类型（"chinese" 或 "english"）
     * @param count 需要选择的错题数量
     */
    fun startErrorDictation(
        dictationType: String,
        count: Int,
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingErrorWords = true,
                    statusText = "正在加载错题...",
                )
            }

            val currentUserResult = userUseCase.getCurrentUser()
            val userName =
                when (currentUserResult) {
                    is AppResult.Success -> currentUserResult.data?.name ?: "游客"
                    is AppResult.Failure -> "游客"
                }

            when (val result = dictationRepository.loadErrorWordStats(userName, dictationType)) {
                is AppResult.Success -> {
                    val errorStats = result.data

                    if (errorStats.isEmpty()) {
                        _uiState.update { it.copy(isLoadingErrorWords = false) }
                        _events.emit(DictationEvent.NoErrorWords)
                        return@launch
                    }

                    // 根据错误率选择单词
                    val selectedStats =
                        com.smartstudy.dictation.core.WordSelectionHelper.selectByErrorRate(
                            errorStats = errorStats,
                            count = count,
                            selector = { it },
                        )

                    if (selectedStats.isEmpty()) {
                        _uiState.update { it.copy(isLoadingErrorWords = false) }
                        _events.emit(DictationEvent.NotEnoughErrorWords)
                        return@launch
                    }

                    // 对于英文听写，需要翻译缺失翻译的单词
                    if (dictationType == AppConfig.TYPE_ENGLISH) {
                        val wordsWithoutTranslation =
                            selectedStats.filter { it.chineseTranslation.isEmpty() }
                                .map { it.word }
                        if (wordsWithoutTranslation.isNotEmpty()) {
                            // 批量翻译缺失翻译的单词
                            translateWordsBatch(wordsWithoutTranslation)
                            // 等待翻译完成（这里简化处理，实际应该通过状态管理）
                            // 暂时先返回选中的单词，翻译在后台进行
                        }
                    }

                    val selectedWords = selectedStats.map { it.word }
                    _uiState.update { it.copy(isLoadingErrorWords = false) }
                    _events.emit(DictationEvent.LoadErrorWordsSuccess(selectedWords))
                }
                is AppResult.Failure -> {
                    Logger.e(TAG, "加载错题失败", result.error.cause)
                    _uiState.update { it.copy(isLoadingErrorWords = false) }
                    val errorMessage = result.error.message ?: context.getString(R.string.error_io)
                    _events.emit(DictationEvent.LoadErrorWordsFailed(errorMessage))
                }
            }
        }
    }
}
