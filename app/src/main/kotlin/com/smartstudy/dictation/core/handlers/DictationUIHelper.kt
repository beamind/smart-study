package com.smartstudy.dictation.core.handlers

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.smartstudy.R

/**
 * 听写UI辅助类
 * 负责UI更新和视图管理
 *
 * @param activity Activity实例
 */
class DictationUIHelper(
    private val activity: AppCompatActivity,
) {
    // 视图组件（需要从外部设置）
    var tvStatus: TextView? = null
    var tvCurrentWord: TextView? = null
    var tvWordIndex: TextView? = null
    var progressBar: ProgressBar? = null
    var statusLayout: View? = null
    var cardProgress: CardView? = null
    var cardRecognition: CardView? = null
    var cardTip: CardView? = null
    var tvCurrentUser: TextView? = null

    /**
     * 显示进度
     */
    fun showProgress(show: Boolean) {
        statusLayout?.visibility = if (show) View.VISIBLE else View.GONE
        progressBar?.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * 更新状态文本
     */
    fun updateStatus(text: String) {
        tvStatus?.text = text
        tvStatus?.visibility = View.VISIBLE
    }

    /**
     * 更新当前词语
     */
    fun updateCurrentWord(
        word: String,
        index: Int,
        total: Int,
    ) {
        tvCurrentWord?.text = word
        tvCurrentWord?.visibility = View.VISIBLE
        tvWordIndex?.text = activity.getString(R.string.word_index_format, index + 1, total)
        tvWordIndex?.visibility = View.VISIBLE
    }

    /**
     * 显示听写界面
     */
    fun showDictationUI() {
        cardProgress?.visibility = View.VISIBLE
        cardRecognition?.visibility = View.VISIBLE
        cardTip?.visibility = View.VISIBLE
    }

    /**
     * 隐藏听写界面
     */
    fun hideDictationUI() {
        tvCurrentWord?.visibility = View.GONE
        tvWordIndex?.visibility = View.GONE
        cardProgress?.visibility = View.GONE
        cardRecognition?.visibility = View.GONE
        cardTip?.visibility = View.GONE
    }

    /**
     * 更新当前用户显示
     */
    fun updateCurrentUserDisplay(userName: String?) {
        tvCurrentUser?.text = userName ?: activity.getString(R.string.guest)
    }
}
