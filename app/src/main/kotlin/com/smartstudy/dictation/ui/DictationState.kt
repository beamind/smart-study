package com.smartstudy.dictation.ui

import com.smartstudy.dictation.english.WordWithTranslation

/**
 * 听写功能的统一状态数据类
 *
 * 将多个独立的 LiveData 状态整合到一个数据类中，便于管理和维护。
 * 使用 sealed class 支持不可变状态，提高线程安全性。
 *
 * @param recognitionWords 识别到的词语列表（中文或英文）
 * @param recognitionError 识别错误信息（null 表示无错误）
 * @param isProcessing 是否正在处理图片识别
 * @param currentUserId 当前用户ID
 * @param currentUserName 当前用户名
 * @param isDictationActive 听写是否激活
 * @param isWaitingForStart 是否在等待开始关键词
 * @param currentWordIndex 当前词语索引
 * @param isSubmitted 是否已提交
 * @param isInForeground Activity是否在前台
 * @param statusText 状态文本
 * @param asrText ASR识别结果文本
 * @param asrStatus ASR识别状态（null 表示无状态）
 * @param isTranslating 是否正在翻译
 * @param isSubmitting 是否正在提交错题
 * @param isLoadingErrorWords 是否正在加载错题
 * @param translationProgress 翻译进度（当前数量/总数量）
 * @param dictatedWordsEnglish 英文听写的听写顺序（包含英文和中文翻译）
 * @param dictatedWordsChinese 中文听写的听写顺序
 */
data class DictationState(
    val recognitionWords: List<String> = emptyList(),
    val recognitionError: String? = null,
    val isProcessing: Boolean = false,
    val currentUserId: String = "",
    val currentUserName: String = "",
    val isDictationActive: Boolean = false,
    val isWaitingForStart: Boolean = false,
    val currentWordIndex: Int = 0,
    val isSubmitted: Boolean = false,
    val isInForeground: Boolean = false,
    val statusText: String = "",
    val asrText: String = "",
    val asrStatus: String? = null,
    val isTranslating: Boolean = false,
    val isSubmitting: Boolean = false,
    val isLoadingErrorWords: Boolean = false,
    val translationProgress: Pair<Int, Int>? = null,
    val dictatedWordsEnglish: List<WordWithTranslation> = emptyList(),
    val dictatedWordsChinese: List<String> = emptyList(),
) {
    /**
     * 创建初始状态
     */
    companion object {
        fun initial() = DictationState()
    }

    /**
     * 重置听写状态
     */
    fun resetDictationState(): DictationState {
        return copy(
            isDictationActive = false,
            isWaitingForStart = false,
            currentWordIndex = 0,
            isSubmitted = false,
            statusText = "",
            asrText = "",
            asrStatus = null,
            recognitionWords = emptyList(),
            recognitionError = null,
            dictatedWordsEnglish = emptyList(),
            dictatedWordsChinese = emptyList(),
        )
    }

    /**
     * 清除识别结果
     */
    fun clearRecognitionResult(): DictationState {
        return copy(
            recognitionWords = emptyList(),
            recognitionError = null,
            dictatedWordsEnglish = emptyList(),
            dictatedWordsChinese = emptyList(),
        )
    }

    /**
     * 清除ASR识别结果
     */
    fun clearAsrResult(): DictationState {
        return copy(
            asrText = "",
            asrStatus = null,
        )
    }
}

/**
 * 听写功能的一次性UI事件
 * 用于处理Toast、Dialog等一次性事件，避免在StateFlow中重复触发
 */
sealed class DictationEvent {
    /**
     * 显示Toast消息
     */
    data class ShowToast(
        val message: String,
        val isLong: Boolean = false,
    ) : DictationEvent()

    /**
     * 翻译完成事件
     */
    data class TranslationComplete(
        val englishWord: String,
        val chineseTranslation: String,
        val wordIndex: Int,
    ) : DictationEvent()

    /**
     * 翻译失败事件
     */
    data class TranslationFailed(
        val englishWord: String,
        val error: String,
    ) : DictationEvent()

    /**
     * 批量翻译进度更新
     */
    data class TranslationProgress(
        val current: Int,
        val total: Int,
    ) : DictationEvent()

    /**
     * 提交错题成功事件
     */
    data class SubmitSuccess(
        val totalCount: Int,
        val correctCount: Int,
        val errorCount: Int,
    ) : DictationEvent()

    /**
     * 提交错题失败事件（权限问题）
     */
    data class SubmitPermissionError(
        val fileName: String,
    ) : DictationEvent()

    /**
     * 提交错题失败事件（IO错误）
     */
    data class SubmitIoError(
        val error: String,
    ) : DictationEvent()

    /**
     * 加载错题成功事件
     */
    data class LoadErrorWordsSuccess(
        val words: List<String>,
    ) : DictationEvent()

    /**
     * 加载错题失败事件
     */
    data class LoadErrorWordsFailed(
        val error: String,
    ) : DictationEvent()

    /**
     * 没有错题事件
     */
    object NoErrorWords : DictationEvent()

    /**
     * 错题数量不足事件
     */
    object NotEnoughErrorWords : DictationEvent()

    /**
     * 图片识别完成事件
     */
    data class ImageRecognitionComplete(
        val words: List<String>,
    ) : DictationEvent()

    /**
     * 手动输词语输完成事件
     */
    data class WordsInputComplete(
        val words: List<String>,
    ) : DictationEvent()

    /**
     * 图片识别失败事件
     */
    data class ImageRecognitionFailed(
        val error: com.smartstudy.core.AppError,
    ) : DictationEvent()
}
