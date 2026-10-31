package com.smartstudy.dictation.core.coordinators

import android.view.View
import com.smartstudy.dictation.core.handlers.DictationViewManager

/**
 * 听写视图协调器
 *
 * 负责协调视图管理器和Activity之间的交互，进一步分离视图管理逻辑。
 *
 * 功能特性：
 * - 协调视图显示/隐藏状态
 * - 管理视图状态转换
 * - 提供统一的视图操作接口
 *
 * 使用示例：
 * ```
 * val coordinator = DictationViewCoordinator(viewManager)
 * coordinator.showDictationProgress(currentIndex, totalCount)
 * coordinator.hideDictationViews()
 * ```
 *
 * @param viewManager 视图管理器
 */
class DictationViewCoordinator(
    private val viewManager: DictationViewManager,
) {
    companion object {
        private const val TAG = "DictationViewCoordinator"
    }

    /**
     * 显示听写进度
     *
     * @param currentIndex 当前词语索引（从0开始）
     * @param totalCount 总词语数量
     */
    fun showDictationProgress(
        currentIndex: Int,
        totalCount: Int,
    ) {
        viewManager.tvWordIndex.text =
            viewManager.activity.getString(
                com.smartstudy.R.string.word_index_format,
                currentIndex + 1,
                totalCount,
            )
        viewManager.tvWordIndex.visibility = View.VISIBLE
        viewManager.tvCurrentWord.visibility = View.GONE
        viewManager.cardProgress.visibility = View.VISIBLE
        viewManager.cardRecognition.visibility = View.GONE
        viewManager.cardTip.visibility = View.VISIBLE
    }

    /**
     * 隐藏听写相关视图
     */
    fun hideDictationViews() {
        viewManager.hideDictationViews()
    }

    /**
     * 显示开始听写界面
     */
    fun showStartDictationViews() {
        viewManager.showStartDictationViews()
    }

    /**
     * 隐藏开始听写界面
     */
    fun hideStartDictationViews() {
        viewManager.hideStartDictationViews()
    }

    /**
     * 显示答案界面
     */
    fun showAnswerViews() {
        viewManager.cardPhoto.visibility = View.GONE
        viewManager.cardProgress.visibility = View.GONE
        viewManager.cardAnswers.visibility = View.VISIBLE
        viewManager.btnTakePhoto.visibility = View.GONE
        viewManager.btnViewErrors.visibility = View.GONE
        viewManager.btnErrorDictation.visibility = View.GONE
        viewManager.btnSubmit.visibility = View.VISIBLE
    }

    /**
     * 显示图片相关视图
     */
    fun showPhotoViews() {
        viewManager.ivPhoto.visibility = View.VISIBLE
        viewManager.placeholderLayout.visibility = View.VISIBLE
        viewManager.btnTakePhoto.visibility = View.VISIBLE
        viewManager.cardPhoto.visibility = View.VISIBLE
        viewManager.btnViewErrors.visibility = View.VISIBLE
        viewManager.btnErrorDictation.visibility = View.VISIBLE
    }

    /**
     * 隐藏图片相关视图
     */
    fun hidePhotoViews() {
        viewManager.ivPhoto.visibility = View.GONE
        viewManager.placeholderLayout.visibility = View.GONE
        viewManager.btnTakePhoto.visibility = View.GONE
        viewManager.cardPhoto.visibility = View.GONE
        viewManager.btnViewErrors.visibility = View.GONE
        viewManager.btnErrorDictation.visibility = View.GONE
    }

    /**
     * 设置状态文本
     *
     * @param text 状态文本
     * @param visible 是否可见
     */
    fun setStatusText(
        text: String,
        visible: Boolean = true,
    ) {
        viewManager.tvStatus.text = text
        viewManager.tvStatus.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 设置提示文本
     *
     * @param text 提示文本
     */
    fun setTipText(text: String) {
        viewManager.tvTip.text = text
    }
}
