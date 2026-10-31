package com.smartstudy.dictation.core.handlers

import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.core.Logger
import com.smartstudy.dictation.ui.DictationEvent
import com.smartstudy.utils.ToastHelper

/**
 * 听写状态观察器
 *
 * 负责处理 ViewModel 的状态变化并更新 UI，将状态处理逻辑从 Activity 中分离出来。
 *
 * 功能特性：
 * - 处理 ViewModel 的 StateFlow 状态变化并更新 UI
 * - 处理 ViewModel 的 Event Flow 事件
 * - 统一的错误处理和边界检查
 *
 * 注意：此 Observer 不再持有协程引用，观察逻辑应在 Activity 中使用 lifecycleScope 启动。
 *
 * 使用示例：
 * ```
 * val stateObserver = DictationStateObserver(
 *     activity = this,
 *     viewModel = viewModel,
 *     viewManager = viewManager,
 *     onImageUnderstandingSuccess = { words -> handleWords(words) },
 *     onImageRecognitionError = { error -> handleError(error) }
 * )
 * // 在 Activity 中启动观察
 * lifecycleScope.launch {
 *     repeatOnLifecycle(Lifecycle.State.STARTED) {
 *         viewModel.uiState.collect { state ->
 *             stateObserver.updateUIFromState(state)
 *         }
 *     }
 * }
 * lifecycleScope.launch {
 *     repeatOnLifecycle(Lifecycle.State.STARTED) {
 *         viewModel.events.collect { event ->
 *             stateObserver.handleEvent(event)
 *         }
 *     }
 * }
 * ```
 *
 * @param activity Activity 实例，用于显示 Toast
 * @param viewManager 视图管理器，用于更新 UI
 * @param onImageUnderstandingSuccess 图片识别成功回调
 * @param onImageRecognitionError 图片识别失败回调
 */
class DictationStateObserver(
    private val activity: AppCompatActivity,
    private val viewManager: DictationViewManager,
    private val onImageUnderstandingSuccess: (List<String>) -> Unit,
    private val onImageRecognitionError: (com.smartstudy.core.AppError) -> Unit,
) {
    companion object {
        private const val TAG = "DictationStateObserver"
    }

    /**
     * 根据状态更新 UI
     *
     * 统一处理所有状态变化，通过 ViewManager 的方法更新UI，减少直接操作视图
     *
     * 注意：此方法应在 Activity 的 lifecycleScope 中调用，通过观察 ViewModel 的 uiState
     */
    fun updateUIFromState(state: com.smartstudy.dictation.ui.DictationState) {
        // 使用 ViewManager 的统一方法更新UI，提高代码可维护性
        viewManager.updateUIFromState(state)

        // 日志记录（用于调试，仅在关键状态变化时记录）
        if (state.isDictationActive || state.isWaitingForStart) {
            Logger.d(
                TAG,
                "状态更新: isDictationActive=${state.isDictationActive}, currentWordIndex=${state.currentWordIndex}, isWaitingForStart=${state.isWaitingForStart}, isSubmitted=${state.isSubmitted}",
            )
        }
    }

    /**
     * 处理事件
     *
     * 注意：此方法应在 Activity 的 lifecycleScope 中调用，通过观察 ViewModel 的 events
     *
     * DictationStateObserver 只处理基础的状态相关事件（如图片识别和词语输入）。
     * 其他业务事件（如翻译、提交等）由子类的 DictationEventObserver 处理。
     * 这种设计确保了职责分离：
     * - BaseDictationActivity: 处理基础状态和词语输入（图片识别/手动输入）
     * - 子类 Activity: 处理特定业务逻辑（中文/英文听写的不同处理）
     */
    fun handleEvent(event: DictationEvent) {
        when (event) {
            is DictationEvent.ImageRecognitionComplete -> {
                handleDictationWordsInputComplete(event.words)
            }
            is DictationEvent.WordsInputComplete -> {
                handleDictationWordsInputComplete(event.words)
            }
            is DictationEvent.ImageRecognitionFailed -> {
                // 图片识别失败，调用回调
                onImageRecognitionError(event.error)
            }
            else -> {
                // 其他事件由子类的 DictationEventObserver 处理
                // 这是设计上的分离，确保 BaseDictationActivity 只处理基础功能
            }
        }
    }

    /**
     * 处理听写词汇识别完成事件
     */
    private fun handleDictationWordsInputComplete(words: List<String>) {
        // 边界检查：空列表
        if (words.isEmpty()) {
            val error =
                com.smartstudy.core.AppError(
                    com.smartstudy.core.AppErrorKind.InvalidData,
                    "未识别到词语，请尝试重新拍照或手动输入",
                )
            onImageRecognitionError(error)
            ToastHelper.showLong(activity, error.message)
            return
        }

        // 边界检查：词语数量限制
        if (words.size > 100) {
            Logger.w(TAG, "识别到的词语数量过多（${words.size}），可能影响性能")
        }

        // 调用成功回调
        onImageUnderstandingSuccess(words)
    }
}
