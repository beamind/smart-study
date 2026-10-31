package com.smartstudy.dictation.core.managers

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.dictation.core.handlers.DictationViewManager
import com.smartstudy.dictation.ui.DictationEvent
import com.smartstudy.dictation.ui.DictationViewModel
import com.smartstudy.utils.ToastHelper
import kotlinx.coroutines.launch

/**
 * 听写事件观察器
 *
 * 负责观察和处理 ViewModel 的事件流，将事件处理逻辑从 Activity 中分离出来。
 *
 * 功能特性：
 * - 统一的事件观察逻辑
 * - 可配置的事件处理回调
 * - 生命周期感知的事件收集
 *
 * 使用示例：
 * ```
 * val eventObserver = DictationEventObserver(
 *     activity = this,
 *     viewModel = viewModel,
 *     viewManager = viewManager,
 *     uiManager = uiManager,
 *     callbacks = DictationEventCallbacks(
 *         onLoadErrorWordsSuccess = { words ->
 *             // 处理错题加载成功
 *         },
 *         onTranslationComplete = { wordIndex, englishWord, chineseTranslation ->
 *             // 处理翻译完成（仅英文听写）
 *         }
 *     )
 * )
 * eventObserver.startObserving()
 * ```
 *
 * @param activity Activity实例，用于访问生命周期和上下文
 * @param viewModel 听写ViewModel
 * @param viewManager 视图管理器
 * @param uiManager UI管理器
 * @param callbacks 事件处理回调配置
 */
class DictationEventObserver(
    private val activity: AppCompatActivity,
    private val viewModel: DictationViewModel,
    private val viewManager: DictationViewManager,
    private val uiManager: DictationUIManager,
    private val callbacks: DictationEventCallbacks,
) {
    companion object {
        private const val TAG = "DictationEventObserver"
    }

    /**
     * 开始观察事件
     */
    fun startObserving() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    /**
     * 处理事件
     */
    private fun handleEvent(event: DictationEvent) {
        when (event) {
            is DictationEvent.SubmitSuccess -> {
                handleSubmitSuccess(event)
            }
            is DictationEvent.SubmitPermissionError -> {
                handleSubmitPermissionError(event)
            }
            is DictationEvent.SubmitIoError -> {
                handleSubmitIoError(event)
            }
            is DictationEvent.LoadErrorWordsSuccess -> {
                handleLoadErrorWordsSuccess(event)
            }
            is DictationEvent.LoadErrorWordsFailed -> {
                handleLoadErrorWordsFailed(event)
            }
            is DictationEvent.NoErrorWords -> {
                handleNoErrorWords()
            }
            is DictationEvent.NotEnoughErrorWords -> {
                handleNotEnoughErrorWords()
            }
            is DictationEvent.ShowToast -> {
                handleShowToast(event)
            }
            is DictationEvent.TranslationComplete -> {
                handleTranslationComplete(event)
            }
            is DictationEvent.TranslationFailed -> {
                handleTranslationFailed(event)
            }
            is DictationEvent.TranslationProgress -> {
                handleTranslationProgress(event)
            }
            is DictationEvent.ImageRecognitionComplete -> {
                handleImageRecognitionComplete(event)
            }
            is DictationEvent.WordsInputComplete -> {
                // 词语输入完成（包括手动输入），使用相同的处理逻辑
                handleImageRecognitionComplete(
                    DictationEvent.ImageRecognitionComplete(event.words),
                )
            }
            is DictationEvent.ImageRecognitionFailed -> {
                handleImageRecognitionFailed(event)
            }
        }
    }

    /**
     * 处理提交成功事件
     */
    private fun handleSubmitSuccess(event: DictationEvent.SubmitSuccess) {
        ToastHelper.showShort(
            activity,
            R.string.questions_saved,
            event.totalCount,
            event.correctCount,
            event.errorCount,
        )
        // 保存成功后，将按钮改为"返回"
        callbacks.onSubmitSuccess?.invoke()
        val answerAdapter = callbacks.getAnswerAdapter?.invoke()
        uiManager.updateSubmitButtonAfterSuccess(answerAdapter)
    }

    /**
     * 处理提交权限错误事件
     */
    private fun handleSubmitPermissionError(event: DictationEvent.SubmitPermissionError) {
        uiManager.showStoragePermissionErrorDialog(event.fileName)
    }

    /**
     * 处理提交IO错误事件
     */
    private fun handleSubmitIoError(event: DictationEvent.SubmitIoError) {
        ToastHelper.showLong(activity, R.string.save_failed_with_error, event.error)
    }

    /**
     * 处理加载错题成功事件
     */
    private fun handleLoadErrorWordsSuccess(event: DictationEvent.LoadErrorWordsSuccess) {
        callbacks.onShowProgress?.invoke(false)
        callbacks.onLoadErrorWordsSuccess?.invoke(event.words)
    }

    /**
     * 处理加载错题失败事件
     */
    private fun handleLoadErrorWordsFailed(event: DictationEvent.LoadErrorWordsFailed) {
        callbacks.onShowProgress?.invoke(false)
        ToastHelper.showLong(activity, R.string.dictation_load_errors_failed, event.error)
    }

    /**
     * 处理没有错题事件
     */
    private fun handleNoErrorWords() {
        callbacks.onShowProgress?.invoke(false)
        ToastHelper.showLong(activity, R.string.dictation_no_errors)
    }

    /**
     * 处理错题不足事件
     */
    private fun handleNotEnoughErrorWords() {
        callbacks.onShowProgress?.invoke(false)
        ToastHelper.showLong(activity, R.string.dictation_not_enough_errors)
    }

    /**
     * 处理显示Toast事件
     */
    private fun handleShowToast(event: DictationEvent.ShowToast) {
        if (event.isLong) {
            ToastHelper.showLong(activity, event.message)
        } else {
            ToastHelper.showShort(activity, event.message)
        }
    }

    /**
     * 处理翻译完成事件（仅英文听写）
     */
    private fun handleTranslationComplete(event: DictationEvent.TranslationComplete) {
        callbacks.onTranslationComplete?.invoke(
            event.wordIndex,
            event.englishWord,
            event.chineseTranslation,
        )
    }

    /**
     * 处理翻译失败事件（仅英文听写）
     */
    private fun handleTranslationFailed(event: DictationEvent.TranslationFailed) {
        viewManager.updateStatusText("翻译失败: ${event.error}")
        ToastHelper.showShort(activity, R.string.translation_failed, event.error)
        callbacks.onTranslationFailed?.invoke(event.englishWord, event.error)
    }

    /**
     * 处理翻译进度事件（仅英文听写）
     */
    private fun handleTranslationProgress(event: DictationEvent.TranslationProgress) {
        viewManager.updateStatusText("正在翻译 ${event.current}/${event.total} 个单词...")
    }

    /**
     * 处理图片识别完成事件
     */
    private fun handleImageRecognitionComplete(event: DictationEvent.ImageRecognitionComplete) {
        Logger.d(TAG, "图片识别完成: ${event.words.size} 个词语")
        callbacks.onImageRecognitionComplete?.invoke(event.words)
    }

    /**
     * 处理图片识别失败事件
     */
    private fun handleImageRecognitionFailed(event: DictationEvent.ImageRecognitionFailed) {
        Logger.e(TAG, "图片识别失败: ${event.error.message}", event.error.cause)
        callbacks.onImageRecognitionFailed?.invoke(event.error)
    }
}

/**
 * 听写事件处理回调配置
 *
 * 用于配置不同类型的事件处理回调，支持中文和英文听写的不同处理逻辑。
 *
 * @param onLoadErrorWordsSuccess 错题加载成功回调
 * @param onTranslationComplete 翻译完成回调（仅英文听写）
 * @param onTranslationFailed 翻译失败回调（仅英文听写）
 * @param onSubmitSuccess 提交成功回调
 * @param onShowProgress 显示进度回调
 * @param onImageRecognitionComplete 图片识别完成回调
 * @param onImageRecognitionFailed 图片识别失败回调
 * @param getAnswerAdapter 获取答案适配器的函数（用于更新UI，支持动态获取）
 */
data class DictationEventCallbacks(
    val onLoadErrorWordsSuccess: ((List<String>) -> Unit)? = null,
    val onTranslationComplete: ((Int, String, String) -> Unit)? = null,
    val onTranslationFailed: ((String, String) -> Unit)? = null,
    val onSubmitSuccess: (() -> Unit)? = null,
    val onShowProgress: ((Boolean) -> Unit)? = null,
    val onImageRecognitionComplete: ((List<String>) -> Unit)? = null,
    val onImageRecognitionFailed: ((com.smartstudy.core.AppError) -> Unit)? = null,
    val getAnswerAdapter: (() -> Any?)? = null,
)
