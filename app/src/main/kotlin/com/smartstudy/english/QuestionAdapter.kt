package com.smartstudy.english

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.english.EnglishErrorBookStorage.QuestionWithStats
import com.smartstudy.utils.SpannableTextUtils

/**
 * 题目列表适配器
 * 管理题目列表、选中状态和编辑后的文本
 */
class QuestionAdapter(
    // 改为 public，以便外部访问
    val questionAnswers: MutableList<QuestionAnswer>,
    private val questionStats: Map<String, QuestionWithStats>? = null,
    private val callback: EditModeCallback? = null,
    // 是否为查看模式（查看模式下隐藏复选框）
    private val isViewMode: Boolean = false,
) : RecyclerView.Adapter<QuestionAdapter.QuestionViewHolder>() {
    interface EditModeCallback {
        fun onEnterEditMode()

        fun onQuestionDeleted(
            position: Int,
            question: String,
        )

        fun onQuestionUpdated(
            position: Int,
            oldQuestion: String,
            newQuestion: String,
        )

        fun onSelectionChanged(selectedPositions: Set<Int>)
    }

    private val selectedPositions = mutableSetOf<Int>()

    // 存储每个位置编辑后的文本，如果没有编辑则使用原始文本
    private val editedTexts = mutableMapOf<Int, String>()

    // 存储每个位置编辑后的答案，如果没有编辑则使用原始答案
    private val editedAnswers = mutableMapOf<Int, String>()
    private var isEditMode = false // 默认禁用编辑模式，需要长按进入编辑模式

    class QuestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        val tvErrorRate: TextView = itemView.findViewById(R.id.tvErrorRate)
        val etQuestion: EditText = itemView.findViewById(R.id.etQuestion)
        val etAnswer: EditText = itemView.findViewById(R.id.etAnswer)
        val btnDelete: ImageView = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): QuestionViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_question, parent, false)
        return QuestionViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: QuestionViewHolder,
        position: Int,
    ) {
        val questionAnswer = questionAnswers[position]
        val question = questionAnswer.question
        val answer = questionAnswer.answer

        setupQuestionText(holder, position, question)
        setupAnswerText(holder, position, answer)
        setupErrorRateDisplay(holder, position, question)
        setupCheckbox(holder, position)
    }

    /**
     * 设置题目文本和监听器
     * @param holder ViewHolder
     * @param position 位置
     * @param question 原始题目文本
     */
    private fun setupQuestionText(
        holder: QuestionViewHolder,
        position: Int,
        question: String,
    ) {
        // 获取编辑后的文本（如果有），否则使用原始文本
        val displayText = editedTexts[position] ?: question

        // 先移除之前的TextWatcher，避免在设置文本时触发
        holder.etQuestion.removeTextChangedListener(holder.etQuestion.tag as? TextWatcher)

        // 根据编辑模式决定如何显示文本
        if (isEditMode) {
            // 编辑模式：显示原始文本（包含 __ 标记），用户可以编辑
            holder.etQuestion.setText(displayText)
        } else {
            // 非编辑模式：移除 Markdown 下划线标记并应用 UnderlineSpan
            holder.etQuestion.setText(SpannableTextUtils.createUnderlinedText(displayText))
        }

        // 根据编辑模式控制题目是否可编辑
        holder.etQuestion.isFocusable = isEditMode
        holder.etQuestion.isFocusableInTouchMode = isEditMode
        holder.etQuestion.isEnabled = isEditMode
        holder.etQuestion.isClickable = isEditMode

        // 创建新的TextWatcher来监听文本变化
        val textWatcher = createQuestionTextWatcher(position, question)
        holder.etQuestion.tag = textWatcher
        holder.etQuestion.addTextChangedListener(textWatcher)
    }

    /**
     * 创建题目文本监听器
     * @param position 位置
     * @param originalQuestion 原始题目文本
     * @return TextWatcher
     */
    private fun createQuestionTextWatcher(
        position: Int,
        @Suppress("UNUSED_PARAMETER") originalQuestion: String,
    ): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) {}

            override fun afterTextChanged(s: Editable?) {
                // 始终保存编辑后的文本（不受isEditMode影响）
                val newText = s?.toString() ?: ""
                val oldText = questionAnswers[position].question
                // 保存编辑后的文本
                editedTexts[position] = newText
                // 如果文本改变了，通知回调
                if (newText != oldText) {
                    callback?.onQuestionUpdated(position, oldText, newText)
                }
            }
        }
    }

    /**
     * 设置答案文本和监听器
     * @param holder ViewHolder
     * @param position 位置
     * @param answer 原始答案文本
     */
    private fun setupAnswerText(
        holder: QuestionViewHolder,
        position: Int,
        answer: String,
    ) {
        // 获取编辑后的答案（如果有），否则使用原始答案
        val displayAnswer = editedAnswers[position] ?: answer

        // 先移除之前的答案TextWatcher，避免在设置文本时触发
        holder.etAnswer.removeTextChangedListener(holder.etAnswer.tag as? TextWatcher)

        // 设置答案EditText的文本
        holder.etAnswer.setText(displayAnswer)

        // 根据编辑模式控制答案是否可编辑
        holder.etAnswer.isFocusable = isEditMode
        holder.etAnswer.isFocusableInTouchMode = isEditMode
        holder.etAnswer.isEnabled = isEditMode
        holder.etAnswer.isClickable = isEditMode

        // 创建答案的TextWatcher来监听文本变化
        val answerTextWatcher = createAnswerTextWatcher(position)
        holder.etAnswer.tag = answerTextWatcher
        holder.etAnswer.addTextChangedListener(answerTextWatcher)

        // 显示答案（如果有答案或处于查看模式）
        holder.etAnswer.visibility = if (answer.isNotEmpty() || isEditMode) View.VISIBLE else View.GONE
    }

    /**
     * 创建答案文本监听器
     * @param position 位置
     * @return TextWatcher
     */
    private fun createAnswerTextWatcher(position: Int): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) {}

            override fun afterTextChanged(s: Editable?) {
                // 始终保存编辑后的答案（不受isEditMode影响）
                val newAnswer = s?.toString() ?: ""
                // 保存编辑后的答案
                editedAnswers[position] = newAnswer
            }
        }
    }

    /**
     * 设置错误率显示
     * @param holder ViewHolder
     * @param position 位置
     * @param originalQuestion 原始题目文本
     */
    private fun setupErrorRateDisplay(
        holder: QuestionViewHolder,
        position: Int,
        originalQuestion: String,
    ) {
        val displayText = editedTexts[position] ?: originalQuestion
        val stats = findQuestionStats(displayText, originalQuestion)

        if (stats != null) {
            // 显示错误统计：错误次数/总次数
            holder.tvErrorRate.text = stats.getErrorRateText()
            holder.tvErrorRate.visibility = View.VISIBLE
        } else {
            // 如果找不到统计信息，在查看模式下显示题目索引作为后备
            if (isViewMode || isEditMode) {
                val total = questionAnswers.size
                val index = position + 1
                holder.tvErrorRate.text = "$index/$total"
                holder.tvErrorRate.visibility = View.VISIBLE
            } else {
                holder.tvErrorRate.visibility = View.GONE
            }
        }
    }

    /**
     * 查找题目统计信息
     * @param displayText 当前显示的文本
     * @param originalQuestion 原始题目文本
     * @return 统计信息，如果找不到则返回null
     */
    private fun findQuestionStats(
        displayText: String,
        originalQuestion: String,
    ): QuestionWithStats? {
        return if (questionStats != null) {
            // 先尝试用当前显示的题目（可能是编辑后的）查找
            questionStats[displayText]
                // 如果找不到，尝试用原始题目查找
                ?: questionStats[originalQuestion]
        } else {
            null
        }
    }

    /**
     * 设置复选框
     * @param holder ViewHolder
     * @param position 位置
     */
    private fun setupCheckbox(
        holder: QuestionViewHolder,
        position: Int,
    ) {
        holder.btnDelete.visibility = View.GONE

        if (isEditMode) {
            setupCheckboxInEditMode(holder, position)
        } else {
            setupCheckboxInSelectionMode(holder, position)
        }
    }

    /**
     * 在编辑模式下设置复选框
     * @param holder ViewHolder
     * @param position 位置
     */
    private fun setupCheckboxInEditMode(
        holder: QuestionViewHolder,
        position: Int,
    ) {
        holder.cbSelect.visibility = View.VISIBLE

        // 先清除监听器，避免在设置状态时触发
        holder.cbSelect.setOnCheckedChangeListener(null)

        // 根据 selectedPositions 设置复选框状态
        val isSelected = selectedPositions.contains(position)
        holder.cbSelect.isChecked = isSelected

        // 设置复选框点击事件
        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedPositions.add(position)
            } else {
                selectedPositions.remove(position)
            }
            callback?.onSelectionChanged(selectedPositions.toSet())
            notifyItemChanged(position)
        }
    }

    /**
     * 在选择模式下设置复选框
     * @param holder ViewHolder
     * @param position 位置
     */
    private fun setupCheckboxInSelectionMode(
        holder: QuestionViewHolder,
        position: Int,
    ) {
        // 查看模式下隐藏复选框，选择模式下显示复选框
        holder.cbSelect.visibility = if (isViewMode) View.GONE else View.VISIBLE

        // 只有在显示复选框时才设置监听器
        if (!isViewMode) {
            // 先清除监听器，避免在设置状态时触发
            holder.cbSelect.setOnCheckedChangeListener(null)

            // 根据 selectedPositions 设置复选框状态
            val isSelected = selectedPositions.contains(position)
            holder.cbSelect.isChecked = isSelected

            // 设置复选框点击事件
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPositions.add(position)
                } else {
                    selectedPositions.remove(position)
                }
            }
        }
    }

    override fun getItemCount(): Int = questionAnswers.size

    /**
     * 获取选中的题目和答案列表（返回编辑后的文本和答案）
     */
    fun getSelectedQuestionAnswers(): List<QuestionAnswer> {
        return selectedPositions.sorted().map { position ->
            val qa = questionAnswers[position]
            // 返回编辑后的文本和答案，如果没有编辑则返回原始文本和答案
            val editedQuestion = editedTexts[position] ?: qa.question
            val editedAnswer = editedAnswers[position] ?: qa.answer
            // 拍照识别错题时，用户提交的题目状态应该是"错误"
            QuestionAnswer(editedQuestion, editedAnswer, isCorrect = false, isAiGenerated = qa.isAiGenerated)
        }
    }

    /**
     * 获取选中的题目列表
     */
    fun getSelectedQuestions(): List<String> {
        return getSelectedQuestionAnswers().map { it.question }
    }

    /**
     * 设置编辑模式
     */
    fun setEditMode(editMode: Boolean) {
        if (isEditMode != editMode) {
            isEditMode = editMode
            if (!editMode) {
                // 退出编辑模式时，清除选中状态
                selectedPositions.clear()
            }
            // 使用 notifyDataSetChanged 因为编辑模式影响所有项的显示
            notifyDataSetChanged()
        }
    }

    /**
     * 更新题目列表（使用 DiffUtil 优化性能）
     */
    fun updateQuestions(newQuestions: List<QuestionAnswer>) {
        val diffCallback = QuestionDiffCallback(questionAnswers, newQuestions)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        questionAnswers.clear()
        questionAnswers.addAll(newQuestions)

        diffResult.dispatchUpdatesTo(this)
    }

    fun setSelectedPositions(positions: Set<Int>) {
        val oldSelected = selectedPositions.toSet()
        selectedPositions.clear()
        selectedPositions.addAll(positions)

        // 只通知变化的项
        val changedPositions = mutableListOf<Int>()
        questionAnswers.forEachIndexed { index, _ ->
            val wasSelected = oldSelected.contains(index)
            val isSelected = positions.contains(index)
            if (wasSelected != isSelected) {
                changedPositions.add(index)
            }
        }

        if (changedPositions.isNotEmpty()) {
            changedPositions.forEach { position ->
                notifyItemChanged(position)
            }
        }
    }

    fun getSelectedPositions(): Set<Int> {
        return selectedPositions.toSet()
    }

    fun getAllPositions(): List<Int> {
        return questionAnswers.indices.toList()
    }

    /**
     * 清除编辑状态（用于退出编辑模式时）
     */
    fun clearEditState() {
        editedTexts.clear()
        editedAnswers.clear()
    }

    /**
     * 删除题目
     */
    fun deleteQuestion(position: Int) {
        if (position < 0 || position >= questionAnswers.size) {
            return
        }
        val question = questionAnswers[position].question
        questionAnswers.removeAt(position)
        editedTexts.remove(position)
        editedAnswers.remove(position)
        // 更新后续位置的editedTexts映射
        val keysToUpdate = editedTexts.keys.filter { it > position }.sorted()
        keysToUpdate.forEach { oldKey ->
            val value = editedTexts.remove(oldKey)
            if (value != null) {
                editedTexts[oldKey - 1] = value
            }
        }
        // 更新后续位置的editedAnswers映射
        val answerKeysToUpdate = editedAnswers.keys.filter { it > position }.sorted()
        answerKeysToUpdate.forEach { oldKey ->
            val value = editedAnswers.remove(oldKey)
            if (value != null) {
                editedAnswers[oldKey - 1] = value
            }
        }
        // 更新selectedPositions
        val positionsToUpdate = selectedPositions.filter { it > position }.sorted()
        selectedPositions.removeAll(positionsToUpdate)
        positionsToUpdate.forEach { oldPos ->
            selectedPositions.add(oldPos - 1)
        }
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, questionAnswers.size - position)
        callback?.onQuestionDeleted(position, question)
    }

    /**
     * 获取所有题目和答案（包括编辑后的文本和答案）
     */
    fun getAllQuestionAnswers(): List<QuestionAnswer> {
        return questionAnswers.mapIndexed { index, qa ->
            val editedQuestion = editedTexts[index] ?: qa.question
            val editedAnswer = editedAnswers[index] ?: qa.answer
            QuestionAnswer(editedQuestion, editedAnswer)
        }
    }

    /**
     * 获取所有题目
     */
    fun getAllQuestions(): List<String> {
        return getAllQuestionAnswers().map { it.question }
    }

    /**
     * 获取所有原始题目和答案（不包含编辑后的内容，用于比较）
     */
    fun getAllQuestionAnswersOriginal(): List<QuestionAnswer> {
        return questionAnswers.toList()
    }
}

/**
 * DiffUtil.Callback 用于优化列表更新性能
 */
private class QuestionDiffCallback(
    private val oldList: List<QuestionAnswer>,
    private val newList: List<QuestionAnswer>,
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(
        oldItemPosition: Int,
        newItemPosition: Int,
    ): Boolean {
        // 使用题目文本作为唯一标识
        return oldList[oldItemPosition].question == newList[newItemPosition].question
    }

    override fun areContentsTheSame(
        oldItemPosition: Int,
        newItemPosition: Int,
    ): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        return oldItem.question == newItem.question &&
            oldItem.answer == newItem.answer &&
            oldItem.isCorrect == newItem.isCorrect &&
            oldItem.isAiGenerated == newItem.isAiGenerated
    }
}
