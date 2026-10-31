package com.smartstudy.english.managers

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.databinding.ActivityEnglishErrorBookBinding
import com.smartstudy.utils.SpannableTextUtils
import com.smartstudy.utils.showToast

/**
 * 英语错题本UI管理器
 *
 * 负责管理英语错题本Activity的UI逻辑，包括：
 * - 对话框创建和显示（练习对话框、举一反三对话框、删除验证对话框）
 * - UI状态切换（输入状态、选择状态、查看状态）
 * - 按钮状态管理
 *
 * 使用示例：
 * ```
 * val uiManager = EnglishErrorBookUIManager(
 *     activity = this,
 *     binding = binding
 * )
 * uiManager.showPracticeDialog { count -> startPractice(count) }
 * ```
 *
 * @param activity Activity实例
 * @param binding View Binding实例
 */
class EnglishErrorBookUIManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityEnglishErrorBookBinding,
) {
    companion object {
        private const val TAG = "EnglishErrorBookUIManager"
    }

    /**
     * 设置RecyclerView的布局管理器
     */
    fun setupRecyclerViews() {
        binding.rvQuestions.layoutManager = LinearLayoutManager(activity)
        binding.rvAnswers.layoutManager = LinearLayoutManager(activity)
    }

    /**
     * 显示初始状态（显示按钮，隐藏列表和提交按钮）
     */
    fun showInputState() {
        binding.cardActions.visibility = View.VISIBLE
        binding.rvQuestions.visibility = View.GONE
        binding.btnContainer.visibility = View.GONE
        binding.btnSubmit.visibility = View.GONE
        binding.btnEdit.visibility = View.GONE
        binding.btnRecognizeUnderline.visibility = View.GONE
        binding.btnPreview.visibility = View.GONE
        binding.btnManualUnderline.visibility = View.GONE
        // 显示用户名按钮（初始状态）
        binding.toolbar.tvCurrentUser.visibility = View.VISIBLE
    }

    /**
     * 显示选择状态（隐藏按钮，显示列表和提交按钮）
     */
    fun showSelectionState() {
        binding.cardActions.visibility = View.GONE
        binding.rvQuestions.visibility = View.VISIBLE
        binding.btnContainer.visibility = View.VISIBLE
        binding.btnSubmit.visibility = View.VISIBLE
        binding.btnSubmit.text = activity.getString(R.string.error_book_submit_button)
        // 显示编辑按钮（识别完成后可以选择编辑）
        binding.btnEdit.visibility = View.VISIBLE
        // 显示识别下划线按钮（识别成功后可以手动触发下划线识别）
        binding.btnRecognizeUnderline.visibility = View.VISIBLE
        binding.btnRecognizeUnderline.isEnabled = true
        binding.btnRecognizeUnderline.text = activity.getString(R.string.error_book_recognize_underline_button)
        // 隐藏预览按钮和手动下划线按钮（非编辑模式）
        binding.btnPreview.visibility = View.GONE
        binding.btnManualUnderline.visibility = View.GONE
        // 隐藏用户名按钮（拍照识别错题提交页面不需要显示用户名）
        binding.toolbar.tvCurrentUser.visibility = View.GONE
    }

    /**
     * 显示查看状态（隐藏按钮，显示列表，默认隐藏保存按钮）
     */
    fun showViewState() {
        binding.cardActions.visibility = View.GONE
        binding.rvQuestions.visibility = View.VISIBLE
        binding.btnContainer.visibility = View.GONE // 查看模式下隐藏按钮容器
        binding.btnSubmit.visibility = View.GONE // 查看模式下默认隐藏保存按钮（不能编辑时不需要保存）
        binding.btnEdit.visibility = View.GONE // 查看模式下隐藏编辑按钮
        binding.btnRecognizeUnderline.visibility = View.GONE // 查看模式下隐藏识别下划线按钮
        binding.btnPreview.visibility = View.GONE // 查看模式下隐藏预览按钮
        binding.btnManualUnderline.visibility = View.GONE // 查看模式下隐藏手动下划线按钮
        binding.btnSubmit.text = activity.getString(R.string.error_book_save_button)
        // 隐藏用户名按钮
        binding.toolbar.tvCurrentUser.visibility = View.GONE
    }

    /**
     * 显示错题练习对话框
     *
     * @param onConfirm 确认回调，参数为题目数量
     */
    fun showPracticeDialog(onConfirm: (Int) -> Unit) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_error_dictation, null)
        val etCount = dialogView.findViewById<EditText>(R.id.etCount)

        updateDialogHint(dialogView, activity.getString(R.string.dialog_practice_hint))
        setupQuickButtons(dialogView, etCount)

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_practice_title)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_start) { _, _ ->
                    handleDialogConfirm(etCount, onConfirm)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

        dialog.show()
    }

    /**
     * 显示举一反三对话框
     *
     * @param onConfirm 确认回调，参数为题目数量
     */
    fun showVariationDialog(onConfirm: (Int) -> Unit) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_error_dictation, null)
        val etCount = dialogView.findViewById<EditText>(R.id.etCount)

        updateDialogHint(dialogView, activity.getString(R.string.dialog_variation_hint))
        setupQuickButtons(dialogView, etCount)

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_variation_title)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_start) { _, _ ->
                    handleDialogConfirm(etCount, onConfirm)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

        dialog.show()
    }

    /**
     * 显示删除验证对话框
     *
     * @param onConfirm 确认删除回调
     */
    fun showDeleteVerificationDialog(onConfirm: () -> Unit) {
        com.smartstudy.utils.DialogHelper.showDeleteVerificationDialog(activity, onConfirm).show()
    }

    /**
     * 设置提交按钮为加载状态
     *
     * @param text 按钮文本
     */
    fun setSubmitButtonLoading(text: String) {
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = text
    }

    /**
     * 重置提交按钮状态
     *
     * @param text 按钮文本
     */
    fun resetSubmitButton(text: String) {
        binding.btnSubmit.isEnabled = true
        binding.btnSubmit.text = text
    }

    /**
     * 返回初始状态（从练习答案页面返回）
     */
    fun returnToInitialState() {
        binding.cardAnswers.visibility = View.GONE
        binding.cardActions.visibility = View.VISIBLE
        binding.btnContainer.visibility = View.GONE
        binding.btnSubmit.visibility = View.GONE
        binding.btnEdit.visibility = View.GONE
    }

    // ==================== 识别相关 UI 操作 ====================

    /**
     * 处理识别成功后的 UI 更新
     */
    fun handleRecognitionSuccess() {
        // 隐藏照片卡片
        binding.cardPhoto.visibility = View.GONE

        // 恢复解析按钮状态
        binding.btnParse.isEnabled = true
        binding.tvParseButton.text = activity.getString(R.string.error_book_parse_button)
    }

    /**
     * 处理识别错误后的 UI 更新
     */
    fun handleRecognitionError() {
        // 隐藏照片卡片，恢复操作按钮
        binding.cardPhoto.visibility = View.GONE
        binding.cardActions.visibility = View.VISIBLE
        // 确保隐藏按钮容器（识别失败时不应该显示编辑和提交按钮）
        binding.btnContainer.visibility = View.GONE
        binding.btnSubmit.visibility = View.GONE
        binding.btnEdit.visibility = View.GONE
        binding.btnRecognizeUnderline.visibility = View.GONE
        binding.btnPreview.visibility = View.GONE
        binding.btnManualUnderline.visibility = View.GONE
    }

    /**
     * 更新错误状态的 UI
     */
    fun updateUIForErrorState() {
        binding.cardActions.visibility = View.VISIBLE
        // 确保隐藏按钮容器（错误状态下不应该显示编辑和提交按钮）
        binding.btnContainer.visibility = View.GONE
        binding.btnSubmit.visibility = View.GONE
        binding.btnEdit.visibility = View.GONE
        binding.btnRecognizeUnderline.visibility = View.GONE
        binding.btnPreview.visibility = View.GONE
        binding.btnManualUnderline.visibility = View.GONE
    }

    // ==================== 练习相关 UI 操作 ====================

    /**
     * 处理练习开始时的 UI 更新
     */
    fun handlePracticeStarted() {
        // 隐藏操作按钮
        binding.cardActions.visibility = View.GONE
        binding.cardAnswers.visibility = View.GONE
        binding.rvQuestions.visibility = View.GONE
        binding.btnContainer.visibility = View.GONE
        binding.btnSubmit.visibility = View.GONE
        binding.btnEdit.visibility = View.GONE
        binding.btnRecognizeUnderline.visibility = View.GONE
        binding.btnPreview.visibility = View.GONE
        binding.btnManualUnderline.visibility = View.GONE
        binding.toolbar.tvCurrentUser.visibility = View.GONE
        // 确保 cardTip 初始隐藏（会在显示题目时显示）
        binding.cardTip.visibility = View.GONE
    }

    /**
     * 更新题目显示
     *
     * @param index 当前题目索引（从0开始）
     * @param total 总题目数
     * @param question 题目文本
     */
    fun updateQuestionDisplay(
        index: Int,
        total: Int,
        question: String,
    ) {
        binding.tvQuestionIndex.text = activity.getString(R.string.error_book_question_index, index + 1, total)
        binding.tvQuestionIndex.visibility = View.VISIBLE

        // 处理题目中的下划线：移除 Markdown 标记并应用 UnderlineSpan
        binding.tvCurrentQuestion.text = SpannableTextUtils.createUnderlinedText(question)
        binding.tvCurrentQuestion.visibility = View.VISIBLE

        // 显示下一题按钮，隐藏提示文本
        binding.cardTip.visibility = View.VISIBLE

        // 隐藏提示文本
        binding.tvTip.visibility = View.GONE

        // 隐藏标题（通过查找父布局中的第一个TextView来隐藏标题）
        val tipLayout = binding.cardTip.getChildAt(0) as? android.view.ViewGroup
        tipLayout?.let { layout ->
            // 遍历子视图，隐藏所有TextView（标题和提示文本），只保留按钮
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is android.widget.TextView) {
                    child.visibility = View.GONE
                }
            }
        }

        // 确保按钮是可见的，并设置正确的背景颜色（蓝色系）
        binding.btnNext.visibility = View.VISIBLE
        binding.btnNext.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.primary_blue),
            )

        // 调试日志
        Logger.d(TAG, "cardTip visibility: ${binding.cardTip.visibility}, btnNext visibility: ${binding.btnNext.visibility}")
    }

    /**
     * 显示练习答案
     */
    fun showPracticeAnswers() {
        // 隐藏进度、状态卡片，显示答案卡片
        binding.cardProgress.visibility = View.GONE
        binding.cardStatus.visibility = View.GONE
        binding.cardTip.visibility = View.GONE // 隐藏下一题按钮
        binding.cardAnswers.visibility = View.VISIBLE
        binding.cardActions.visibility = View.GONE
        binding.rvQuestions.visibility = View.GONE

        // 确保按钮容器和提交按钮可见（必须在显示 cardAnswers 之后设置）
        binding.btnContainer.visibility = View.VISIBLE
        binding.btnSubmit.visibility = View.VISIBLE
        binding.btnEdit.visibility = View.GONE // 练习答案页面不显示编辑按钮
        binding.btnRecognizeUnderline.visibility = View.GONE
        binding.btnPreview.visibility = View.GONE
        binding.btnManualUnderline.visibility = View.GONE
        binding.btnSubmit.text = activity.getString(R.string.error_book_submit_error_button)
        binding.btnSubmit.isEnabled = true
        binding.toolbar.tvCurrentUser.visibility = View.GONE

        // 确保按钮容器在布局中正确显示（强制重新布局和测量）
        binding.btnContainer.requestLayout()
        binding.btnContainer.post {
            // 在下一帧确保按钮容器可见
            binding.btnContainer.visibility = View.VISIBLE
            binding.btnSubmit.visibility = View.VISIBLE
        }
    }

    /**
     * 更新状态文本
     *
     * @param text 状态文本
     */
    fun updateStatusText(text: String) {
        binding.tvStatus.text = text
    }

    /**
     * 更新进度卡片可见性
     *
     * @param visible 是否可见
     */
    fun updateProgressVisibility(visible: Boolean) {
        binding.cardProgress.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 更新状态卡片可见性
     *
     * @param visible 是否可见
     */
    fun updateStatusVisibility(visible: Boolean) {
        binding.cardStatus.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 更新答案卡片可见性
     *
     * @param visible 是否可见
     */
    fun updateAnswersVisibility(visible: Boolean) {
        binding.cardAnswers.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ==================== 按钮状态管理 ====================

    /**
     * 更新提交按钮状态
     *
     * @param text 按钮文本
     * @param enabled 是否启用
     */
    fun updateSubmitButton(
        text: String,
        enabled: Boolean,
    ) {
        binding.btnSubmit.text = text
        binding.btnSubmit.isEnabled = enabled
        // 确保按钮和按钮容器可见
        binding.btnContainer.visibility = View.VISIBLE
        binding.btnSubmit.visibility = View.VISIBLE
    }

    /**
     * 更新提交按钮状态（使用资源ID）
     *
     * @param textResId 按钮文本资源ID
     * @param enabled 是否启用
     */
    fun updateSubmitButton(
        textResId: Int,
        enabled: Boolean,
    ) {
        binding.btnSubmit.text = activity.getString(textResId)
        binding.btnSubmit.isEnabled = enabled
    }

    /**
     * 更新查看按钮状态
     *
     * @param text 按钮文本
     * @param enabled 是否启用
     */
    fun updateViewButton(
        text: String,
        enabled: Boolean,
    ) {
        binding.tvViewButton.text = text
        binding.btnView.isEnabled = enabled
    }

    /**
     * 更新编辑工具栏
     *
     * @param selectedCount 选中的题目数量
     * @param totalCount 总题目数量
     * @param isAllSelected 是否全选
     */
    fun updateEditToolbar(
        @Suppress("UNUSED_PARAMETER") selectedCount: Int,
        @Suppress("UNUSED_PARAMETER") totalCount: Int,
        @Suppress("UNUSED_PARAMETER") isAllSelected: Boolean,
    ) {
        // 编辑工具栏已移至菜单，此方法保留以保持接口兼容性
    }

    /**
     * 显示编辑按钮
     */
    fun showEditButton() {
        binding.btnEdit.visibility = View.VISIBLE
        binding.btnContainer.visibility = View.VISIBLE
    }

    /**
     * 隐藏编辑按钮
     */
    fun hideEditButton() {
        binding.btnEdit.visibility = View.GONE
    }

    /**
     * 显示保存按钮（编辑模式下）
     */
    fun showSaveButton() {
        binding.btnSubmit.visibility = View.VISIBLE
        binding.btnSubmit.text = activity.getString(R.string.error_book_save_button)
        binding.btnContainer.visibility = View.VISIBLE
        // 编辑模式下显示手动下划线和预览按钮，隐藏识别下划线按钮
        binding.btnManualUnderline.visibility = View.VISIBLE
        binding.btnPreview.visibility = View.VISIBLE
        binding.btnRecognizeUnderline.visibility = View.GONE
    }

    /**
     * 隐藏保存按钮
     */
    fun hideSaveButton() {
        binding.btnSubmit.visibility = View.GONE
        // 退出编辑模式时隐藏预览按钮和手动下划线按钮
        binding.btnPreview.visibility = View.GONE
        binding.btnManualUnderline.visibility = View.GONE
    }

    // ==================== 题目保存后的 UI 更新 ====================

    /**
     * 处理题目保存成功后的 UI 更新
     *
     * @param isFromPractice 是否来自练习页面
     * @param isViewMode 是否在查看模式
     * @param isEditMode 是否在编辑模式
     * @param onResetToInputState 重置到输入状态的回调
     * @param onReloadQuestions 重新加载题目的回调
     */
    fun handleQuestionsSaved(
        isFromPractice: Boolean,
        isViewMode: Boolean,
        isEditMode: Boolean,
        onResetToInputState: () -> Unit,
        onReloadQuestions: () -> Unit,
    ) {
        if (isFromPractice) {
            // 从练习页面提交，按钮状态已在 QuestionManager 中更新
            // 这里不需要额外操作
        } else {
            // 从识别页面或查看模式提交
            if (isViewMode) {
                // 从查看模式保存，重新加载错题列表
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = activity.getString(R.string.error_book_save_button)
                onReloadQuestions()
            } else {
                // 从识别页面提交或编辑后保存
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = activity.getString(R.string.error_book_submit_button)
                // 如果是在编辑模式下保存，按钮状态会由状态观察器自动恢复，保持在选择状态
                // 如果是从拍照识别页面直接提交（非编辑模式），重置到初始状态
                if (!isEditMode) {
                    onResetToInputState()
                }
            }
        }
    }

    /**
     * 更新对话框提示文字
     */
    private fun updateDialogHint(
        dialogView: View,
        hintText: String,
    ) {
        val linearLayout = dialogView as? android.widget.LinearLayout
        linearLayout?.let {
            for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                if (child is TextView && (child.text.contains("听写") || child.text.contains("练习"))) {
                    child.text = hintText
                    break
                }
            }
        }
    }

    /**
     * 设置快捷按钮
     */
    private fun setupQuickButtons(
        dialogView: View,
        etCount: EditText,
    ) {
        val quickValues = listOf(5, 10, 15, 20, 25, 30, 35, 40)
        val buttonIds =
            listOf(
                R.id.btnQuick5, R.id.btnQuick10, R.id.btnQuick15, R.id.btnQuick20,
                R.id.btnQuick25, R.id.btnQuick30, R.id.btnQuick35, R.id.btnQuick40,
            )

        quickValues.forEachIndexed { index, value ->
            dialogView.findViewById<Button>(buttonIds[index])?.setOnClickListener {
                etCount.setText(value.toString())
            }
        }
    }

    /**
     * 处理对话框确认
     */
    private fun handleDialogConfirm(
        etCount: EditText,
        onConfirm: (Int) -> Unit,
    ) {
        val countText = etCount.text?.toString()?.trim() ?: ""
        val count = countText.toIntOrNull() ?: 0
        if (count > 0) {
            onConfirm(count)
        } else {
            activity.showToast(R.string.error_book_invalid_count)
        }
    }
}
