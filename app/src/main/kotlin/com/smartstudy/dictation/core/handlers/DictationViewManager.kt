package com.smartstudy.dictation.core.handlers

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.canhub.cropper.CropImageView
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.dictation.ErrorWordListActivity
import com.smartstudy.dictation.ui.DictationState

/**
 * 听写视图管理器
 *
 * 负责初始化所有视图组件，管理视图的显示/隐藏状态。
 *
 * 功能特性：
 * - 初始化所有视图组件
 * - 管理视图的显示/隐藏状态
 * - 提供安全的视图查找方法
 * - 管理视图的可见性切换（如显示/隐藏进度卡片、答案卡片等）
 *
 * 使用示例：
 * ```
 * val viewManager = DictationViewManager(
 *     activity = this,
 *     onNewDictationClick = { /* 处理点击 */ },
 *     onViewErrorsClick = { /* 处理点击 */ },
 *     onErrorDictationClick = { /* 处理点击 */ },
 *     onStartDictationClick = { /* 处理点击 */ },
 *     onUserClick = { /* 处理点击 */ },
 *     onSubmitClick = { /* 处理点击 */ },
 *     onNextClick = { /* 处理点击 */ }
 * )
 * viewManager.initViews()
 * ```
 *
 * @param activity Activity实例
 * @param onNewDictationClick 新的听写按钮点击回调
 * @param onViewErrorsClick 查看错题按钮点击回调
 * @param onErrorDictationClick 错题听写按钮点击回调
 * @param onStartDictationClick 开始听写按钮点击回调
 * @param onUserClick 用户信息点击回调
 * @param onSubmitClick 提交按钮点击回调
 * @param onNextClick 下一个按钮点击回调
 */
class DictationViewManager(
    internal val activity: AppCompatActivity,
    private val onNewDictationClick: () -> Unit,
    private val onViewErrorsClick: () -> Unit,
    private val onErrorDictationClick: () -> Unit,
    private val onStartDictationClick: () -> Unit,
    @Suppress("UNUSED_PRIVATE_PROPERTY") private val onUserClick: () -> Unit,
    private val onSubmitClick: () -> Unit,
    private val onNextClick: () -> Unit,
) {
    companion object {
        private const val TAG = "DictationViewManager"
    }

    // 视图组件
    lateinit var ivPhoto: ImageView
    lateinit var btnTakePhoto: CardView
    lateinit var tvStatus: TextView
    lateinit var tvCurrentWord: TextView
    lateinit var tvWordIndex: TextView
    lateinit var progressBar: ProgressBar
    lateinit var placeholderLayout: View
    lateinit var statusLayout: View
    lateinit var cardProgress: CardView
    lateinit var cardRecognition: CardView
    lateinit var cardTip: CardView
    lateinit var tvRecognitionText: TextView
    lateinit var tvRecognitionStatus: TextView
    lateinit var cardPhoto: CardView
    lateinit var cardAnswers: CardView
    lateinit var cardActions: CardView
    lateinit var rvAnswers: RecyclerView
    lateinit var btnSubmit: Button
    lateinit var cropImageView: CropImageView
    lateinit var cropContainer: View
    lateinit var btnCropDone: Button
    lateinit var btnCropCancel: Button
    lateinit var cropButtonLayout: View
    lateinit var btnViewErrors: CardView
    lateinit var btnErrorDictation: CardView
    lateinit var btnStartDictation: Button
    lateinit var tvCurrentUser: TextView
    lateinit var btnNext: Button
    lateinit var tvTip: TextView
    lateinit var cardStartTip: CardView
    lateinit var tvStartTip: TextView

    /**
     * 安全地查找视图，如果找不到则抛出有意义的异常
     *
     * @param id 视图资源ID
     * @param viewName 视图名称（用于错误信息）
     * @return 找到的视图
     * @throws IllegalStateException 如果视图不存在
     */
    private inline fun <reified T : View> findViewSafely(
        @androidx.annotation.IdRes id: Int,
        viewName: String,
    ): T {
        return activity.findViewById<T>(id) ?: error(
            "找不到视图: $viewName (ID: ${activity.resources.getResourceEntryName(id)})，请检查布局文件是否正确",
        )
    }

    /**
     * 检查所有视图是否已初始化
     * @return true 如果所有视图都已初始化，false 否则
     */
    fun areViewsInitialized(): Boolean {
        return try {
            val viewsToCheck = listOf(
                ::ivPhoto,
                ::btnTakePhoto,
                ::tvStatus,
                ::tvCurrentWord,
                ::tvWordIndex,
                ::progressBar,
                ::placeholderLayout,
                ::statusLayout,
                ::cardProgress,
                ::cardRecognition,
                ::cardTip,
                ::tvRecognitionText,
                ::tvRecognitionStatus,
                ::cardPhoto,
                ::cardAnswers,
                ::cardActions,
                ::rvAnswers,
                ::btnSubmit,
                ::cropImageView,
                ::cropContainer,
                ::btnCropDone,
                ::btnCropCancel,
                ::cropButtonLayout,
                ::btnViewErrors,
                ::btnErrorDictation,
                ::btnStartDictation,
                ::tvCurrentUser,
                ::btnNext,
                ::tvTip,
                ::cardStartTip,
                ::tvStartTip,
            )
            viewsToCheck.all { checkViewInitialized(it) }
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            // 防御性编程：如果视图未初始化，返回 false 而不是崩溃
            // 这是合理的，因为视图初始化可能因为 Activity 生命周期问题而失败
            Logger.w(TAG, "视图未初始化，返回 false: ${e.message}", e)
            false
        } catch (e: IllegalStateException) {
            // 防御性编程：如果 Activity 状态异常，返回 false 而不是崩溃
            Logger.w(TAG, "Activity 状态异常，返回 false: ${e.message}", e)
            false
        }
    }

    /**
     * 检查单个视图属性是否已初始化
     */
    private fun <T> checkViewInitialized(property: kotlin.reflect.KProperty0<T>): Boolean {
        return try {
            property.get()
            true
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            false
        }
    }

    /**
     * 初始化视图
     */
    fun initViews() {
        try {
            ivPhoto = findViewSafely(R.id.ivPhoto, "ivPhoto")
            btnTakePhoto = findViewSafely(R.id.btnTakePhoto, "btnTakePhoto")
            tvStatus = findViewSafely(R.id.tvStatus, "tvStatus")
            tvCurrentWord = findViewSafely(R.id.tvCurrentWord, "tvCurrentWord")
            tvWordIndex = findViewSafely(R.id.tvWordIndex, "tvWordIndex")
            progressBar = findViewSafely(R.id.progressBar, "progressBar")
            placeholderLayout = findViewSafely(R.id.placeholderLayout, "placeholderLayout")
            statusLayout = findViewSafely(R.id.statusLayout, "statusLayout")
            cardProgress = findViewSafely(R.id.cardProgress, "cardProgress")
            cardRecognition = findViewSafely(R.id.cardRecognition, "cardRecognition")
            cardTip = findViewSafely(R.id.cardTip, "cardTip")
            btnNext = findViewSafely(R.id.btnNext, "btnNext")
            tvTip = findViewSafely(R.id.tvTip, "tvTip")
            cardStartTip = findViewSafely(R.id.cardStartTip, "cardStartTip")
            tvStartTip = findViewSafely(R.id.tvStartTip, "tvStartTip")
            tvRecognitionText = findViewSafely(R.id.tvRecognitionText, "tvRecognitionText")
            tvRecognitionStatus = findViewSafely(R.id.tvRecognitionStatus, "tvRecognitionStatus")
            cardPhoto = findViewSafely(R.id.cardPhoto, "cardPhoto")
            cardAnswers = findViewSafely(R.id.cardAnswers, "cardAnswers")
            cardActions = findViewSafely(R.id.cardActions, "cardActions")
            rvAnswers = findViewSafely(R.id.rvAnswers, "rvAnswers")
            btnSubmit = findViewSafely(R.id.btnSubmit, "btnSubmit")

            // 进度卡片初始隐藏
            cardProgress.visibility = View.GONE
            cardRecognition.visibility = View.GONE
            cardTip.visibility = View.GONE

            // 初始化答案列表
            rvAnswers.layoutManager = LinearLayoutManager(activity)

            // 提交按钮
            btnSubmit.setOnClickListener {
                onSubmitClick()
            }

            // 初始化裁剪相关视图
            initCropViews()

            // 下一个按钮
            btnNext.setOnClickListener {
                onNextClick()
            }

            // 新的听写按钮
            btnTakePhoto.setOnClickListener {
                onNewDictationClick()
            }

            // 查看错题按钮
            btnViewErrors = findViewSafely(R.id.btnViewErrors, "btnViewErrors")
            btnViewErrors.setOnClickListener {
                onViewErrorsClick()
            }

            // 错题听写按钮
            btnErrorDictation = findViewSafely(R.id.btnErrorDictation, "btnErrorDictation")
            btnErrorDictation.setOnClickListener {
                onErrorDictationClick()
            }

            // 开始听写按钮
            btnStartDictation = findViewSafely(R.id.btnStartDictation, "btnStartDictation")
            btnStartDictation.setOnClickListener {
                onStartDictationClick()
            }

            // 当前用户显示（仅显示，不可点击）
            tvCurrentUser = findViewSafely(R.id.tvCurrentUser, "tvCurrentUser")
            // 用户名仅显示，不设置点击监听器
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，初始化视图失败", e)
            throw e
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "参数错误，初始化视图失败", e)
            throw e
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            Logger.e(TAG, "属性未初始化，初始化视图失败", e)
            throw e
        }
    }

    /**
     * 初始化裁剪视图
     */
    private fun initCropViews() {
        cropImageView = findViewSafely(R.id.cropImageView, "cropImageView")
        cropContainer = findViewSafely(R.id.cropContainer, "cropContainer")
        btnCropDone = findViewSafely(R.id.btnCropDone, "btnCropDone")
        btnCropCancel = findViewSafely(R.id.btnCropCancel, "btnCropCancel")
        cropButtonLayout = findViewSafely(R.id.cropButtonLayout, "cropButtonLayout")
    }

    /**
     * 显示进度
     */
    fun showProgress(show: Boolean) {
        statusLayout.visibility = if (show) View.VISIBLE else View.GONE
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnTakePhoto.isEnabled = !show
    }

    /**
     * 显示错题列表Activity
     */
    fun showErrorWordList(
        dictationType: String,
        userId: String,
    ) {
        val intent =
            Intent(activity, ErrorWordListActivity::class.java).apply {
                putExtra(ErrorWordListActivity.EXTRA_TYPE, dictationType)
                putExtra(ErrorWordListActivity.EXTRA_USER_ID, userId)
            }
        activity.startActivity(intent)
    }

    /**
     * 隐藏听写相关视图（用于停止听写时）
     */
    fun hideDictationViews() {
        tvCurrentWord.visibility = View.GONE
        tvWordIndex.visibility = View.GONE
        cardProgress.visibility = View.GONE
        cardRecognition.visibility = View.GONE
        cardTip.visibility = View.GONE
        tvRecognitionText.text = ""
        tvRecognitionStatus.visibility = View.GONE
    }

    /**
     * 显示开始听写界面（隐藏图片相关视图，显示开始按钮）
     */
    fun showStartDictationViews() {
        tvCurrentUser.visibility = View.GONE
        cardActions.visibility = View.VISIBLE
        btnStartDictation.visibility = View.VISIBLE
        cardStartTip.visibility = View.VISIBLE
        tvStartTip.text = "请对我说开始听写"
        ivPhoto.visibility = View.GONE
        placeholderLayout.visibility = View.GONE
        cardPhoto.visibility = View.GONE
        cardTip.visibility = View.GONE
        btnTakePhoto.visibility = View.GONE
        btnViewErrors.visibility = View.GONE
        btnErrorDictation.visibility = View.GONE
    }

    /**
     * 隐藏开始听写界面（恢复用户名显示，隐藏开始按钮）
     */
    fun hideStartDictationViews() {
        ivPhoto.visibility = View.GONE
        placeholderLayout.visibility = View.GONE
        btnTakePhoto.visibility = View.GONE
        cardPhoto.visibility = View.GONE
        btnViewErrors.visibility = View.GONE
        btnErrorDictation.visibility = View.GONE
        btnStartDictation.visibility = View.GONE
        cardStartTip.visibility = View.GONE
        tvCurrentUser.visibility = View.VISIBLE
    }

    /**
     * 隐藏照片和按钮（用于识别成功后或手动输入后）
     */
    fun hidePhotoAndButtons() {
        ivPhoto.visibility = View.GONE
        placeholderLayout.visibility = View.GONE
        btnTakePhoto.visibility = View.GONE
        cardPhoto.visibility = View.GONE
        btnViewErrors.visibility = View.GONE
        btnErrorDictation.visibility = View.GONE
        cardActions.visibility = View.GONE
    }

    /**
     * 更新状态文本
     */
    fun updateStatusText(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    /**
     * 隐藏状态文本
     */
    fun hideStatusText() {
        tvStatus.visibility = View.GONE
    }

    /**
     * 更新当前用户显示
     * @param userName 用户名
     */
    fun updateCurrentUser(userName: String) {
        if (userName.isNotBlank()) {
            tvCurrentUser.text = userName
        }
    }

    /**
     * 更新词语索引显示
     * @param currentIndex 当前词语索引（从0开始）
     * @param totalWords 总词语数
     * @param isDictationActive 听写是否激活
     */
    fun updateWordIndex(
        currentIndex: Int,
        totalWords: Int,
        isDictationActive: Boolean,
    ) {
        if (isDictationActive && totalWords > 0 && currentIndex < totalWords) {
            tvWordIndex.text =
                activity.getString(
                    R.string.word_index_format,
                    currentIndex + 1,
                    totalWords,
                )
            tvWordIndex.visibility = View.VISIBLE
        } else {
            tvWordIndex.visibility = View.GONE
        }
    }

    /**
     * 更新ASR识别结果显示
     * @param asrText ASR识别文本
     * @param asrStatus ASR识别状态
     */
    fun updateAsrResult(
        asrText: String,
        asrStatus: String?,
    ) {
        if (asrText.isNotBlank()) {
            tvRecognitionText.text = asrText
            tvRecognitionText.visibility = View.VISIBLE
        } else {
            tvRecognitionText.visibility = View.GONE
        }

        if (asrStatus != null && asrStatus.isNotBlank()) {
            tvRecognitionStatus.text = asrStatus
            tvRecognitionStatus.visibility = View.VISIBLE
        } else {
            tvRecognitionStatus.visibility = View.GONE
        }
    }

    /**
     * 根据状态更新所有UI元素
     * 这是一个便捷方法，用于一次性更新所有基于状态的UI
     * @param state 听写状态
     */
    fun updateUIFromState(state: DictationState) {
        if (!areViewsInitialized()) {
            return
        }

        // 更新当前用户显示
        updateCurrentUser(state.currentUserName)

        // 更新词语索引显示
        updateWordIndex(
            currentIndex = state.currentWordIndex,
            totalWords = state.recognitionWords.size,
            isDictationActive = state.isDictationActive,
        )

        // 更新状态文本
        if (state.statusText.isNotBlank()) {
            updateStatusText(state.statusText)
        } else {
            hideStatusText()
        }

        // 更新ASR识别结果显示
        updateAsrResult(
            asrText = state.asrText,
            asrStatus = state.asrStatus,
        )
    }
}
