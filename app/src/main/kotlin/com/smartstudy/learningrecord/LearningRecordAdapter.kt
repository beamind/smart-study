package com.smartstudy.learningrecord

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.utils.SpannableTextUtils
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * 学习记录适配器
 * 支持按类型分组展示和编辑模式
 */
class LearningRecordAdapter(
    private var recordGroups: List<LearningRecordGroup>,
    private var isEditMode: Boolean = false,
    private var editedRecords: Map<String, LearningRecord> = emptyMap(),
    @Suppress("UNUSED_PRIVATE_PROPERTY") private val onRecordUpdated: (String, LearningRecord) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    // 扁平化的数据列表（包含分组标题和记录项）
    private val items = mutableListOf<Any>()

    // 本地存储编辑后的内容，不触发 ViewModel 更新
    private val localEditedRecords = mutableMapOf<String, LearningRecord>()

    // RecyclerView 的弱引用，用于获取可见范围
    private var recyclerViewRef: WeakReference<RecyclerView>? = null

    init {
        // 将分组数据扁平化
        recordGroups.forEach { group ->
            // 添加分组标题
            items.add(GroupHeader(group.type))
            // 添加该分组下的所有记录
            items.addAll(group.records)
        }
    }

    /**
     * 获取所有编辑后的记录
     * 在保存时调用，从本地 Map 中获取所有编辑后的内容
     */
    fun getEditedRecords(): Map<String, LearningRecord> {
        return localEditedRecords.toMap()
    }

    /**
     * 根据位置获取记录
     * @param position 在扁平化列表中的位置
     * @return LearningRecord 或 null（如果是分组标题）
     */
    fun getRecordAtPosition(position: Int): LearningRecord? {
        if (position < 0 || position >= items.size) {
            return null
        }
        return items[position] as? LearningRecord
    }

    /**
     * 设置 RecyclerView 引用，用于优化更新性能
     */
    fun attachRecyclerView(rv: RecyclerView) {
        recyclerViewRef = WeakReference(rv)
    }

    /**
     * 更新数据而不重新创建 Adapter
     */
    fun updateData(
        newRecordGroups: List<LearningRecordGroup>,
        newIsEditMode: Boolean,
        newEditedRecords: Map<String, LearningRecord>,
    ) {
        val oldItemCount = items.size
        val editModeChanged = isEditMode != newIsEditMode // 检测编辑模式是否改变

        // 更新数据
        recordGroups = newRecordGroups
        isEditMode = newIsEditMode
        editedRecords = newEditedRecords

        // 重新扁平化数据
        items.clear()
        recordGroups.forEach { group ->
            items.add(GroupHeader(group.type))
            items.addAll(group.records)
        }

        val newItemCount = items.size

        // 如果数量不同，使用 notifyDataSetChanged
        if (oldItemCount != newItemCount) {
            notifyDataSetChanged()
        } else if (editModeChanged) {
            // 编辑模式改变时，只更新可见的 ViewHolder
            updateVisibleItemsOnly()
        } else {
            // 其他情况（如选中状态改变），正常通知
            notifyItemRangeChanged(0, items.size, null)
        }
    }

    /**
     * 只更新可见的 ViewHolder，其他项在滚动时自动更新
     * 这样可以大幅减少进入编辑模式时的卡顿
     */
    private fun updateVisibleItemsOnly() {
        val rv = recyclerViewRef?.get() ?: run {
            // 如果无法获取 RecyclerView，回退到全部更新
            notifyItemRangeChanged(0, items.size, "EDIT_MODE_CHANGED")
            return
        }

        // 获取可见范围
        val layoutManager = rv.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val lastVisible = layoutManager.findLastVisibleItemPosition()

            if (firstVisible >= 0 && lastVisible >= firstVisible) {
                // 使用 payload 标记这是编辑模式变化
                val payload = "EDIT_MODE_CHANGED"
                // 只更新可见范围
                notifyItemRangeChanged(firstVisible, lastVisible - firstVisible + 1, payload)

                // 其他不可见的项会在滚动到可见时自动更新（RecyclerView 的机制）
                // 不需要手动更新它们，这样可以避免卡顿
            } else {
                // 如果无法获取可见范围，回退到全部更新
                notifyItemRangeChanged(0, items.size, "EDIT_MODE_CHANGED")
            }
        } else {
            // 如果不是 LinearLayoutManager，回退到全部更新
            notifyItemRangeChanged(0, items.size, "EDIT_MODE_CHANGED")
        }
    }

    /**
     * 分组标题数据类
     */
    private data class GroupHeader(val type: LearningRecordType)

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GroupHeader -> TYPE_HEADER
            else -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view =
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_learning_record_group_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view =
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_learning_record, parent, false)
                RecordViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = items[position]) {
            is GroupHeader -> {
                (holder as HeaderViewHolder).bind(item.type)
            }
            is LearningRecord -> {
                val editedRecord = editedRecords[item.getUniqueId()] ?: item
                (holder as RecordViewHolder).bind(editedRecord)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // 如果有 payload，使用部分更新
        if (payloads.isNotEmpty() && payloads[0] == "EDIT_MODE_CHANGED") {
            when (val item = items[position]) {
                is GroupHeader -> {
                    // 分组标题不需要更新
                }
                is LearningRecord -> {
                    val editedRecord = editedRecords[item.getUniqueId()] ?: item
                    (holder as RecordViewHolder).bindEditModeOnly(editedRecord)
                }
            }
            return
        }

        // 正常绑定（没有 payload 或 payload 不匹配）
        onBindViewHolder(holder, position)
    }

    override fun getItemCount(): Int = items.size

    /**
     * 分组标题ViewHolder
     */
    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvGroupTitle)

        fun bind(type: LearningRecordType) {
            tvTitle.text =
                when (type) {
                    LearningRecordType.CHINESE_DICTATION -> "语文听写"
                    LearningRecordType.ENGLISH_DICTATION -> "英语听写"
                    LearningRecordType.ERROR_BOOK -> "错题本"
                }
        }
    }

    /**
     * 学习记录ViewHolder
     */
    inner class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val etContent: EditText = itemView.findViewById(R.id.etContent)
        private val tvErrorCount: TextView = itemView.findViewById(R.id.tvErrorCount)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val etTranslationOrAnswer: EditText = itemView.findViewById(R.id.etTranslationOrAnswer)

        private var contentTextWatcher: TextWatcher? = null
        private var translationOrAnswerTextWatcher: TextWatcher? = null
        private var currentRecord: LearningRecord? = null

        fun bind(
            record: LearningRecord,
        ) {
            currentRecord = record

            // 设置内容（统一使用 EditText，通过属性控制可编辑性）
            if (isEditMode) {
                // 编辑模式：可编辑
                etContent.isFocusable = true
                etContent.isFocusableInTouchMode = true
                etContent.isEnabled = true
                etContent.isClickable = true
                etContent.background = itemView.context.resources.getDrawable(android.R.drawable.edit_text, null)
                
                // 如果 EditText 正在编辑（有焦点）且文本相同，跳过设置，避免光标位置被重置
                val isEditing = etContent.hasFocus()
                val currentText = etContent.text?.toString() ?: ""
                if (isEditing && currentText == record.content) {
                    // 更新 currentRecord，然后只更新 TextWatcher，不重新设置文本
                    currentRecord = record
                    // 移除旧的 TextWatcher
                    contentTextWatcher?.let { etContent.removeTextChangedListener(it) }
                    // 创建新的 TextWatcher，使用最新的 record，只更新本地 Map
                    contentTextWatcher =
                        object : TextWatcher {
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
                                val newContent = s?.toString() ?: ""
                                val latestRecord = currentRecord ?: record
                                // 只更新本地 Map，不触发 ViewModel 更新
                                val updatedRecord = latestRecord.copy(content = newContent)
                                localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                            }
                        }
                    etContent.tag = contentTextWatcher
                    etContent.addTextChangedListener(contentTextWatcher)
                } else {
                    setupContentEditText(record)
                }
            } else {
                // 非编辑模式：只读显示
                etContent.isFocusable = false
                etContent.isFocusableInTouchMode = false
                etContent.isEnabled = false
                etContent.isClickable = false
                etContent.background = null  // 透明背景，看起来像 TextView
                
                // 处理下划线：移除 Markdown 标记并应用 UnderlineSpan
                etContent.setText(SpannableTextUtils.createUnderlinedText(record.content))
                
                // 移除 TextWatcher（非编辑模式不需要）
                contentTextWatcher?.let { etContent.removeTextChangedListener(it) }
                contentTextWatcher = null
            }

            // 设置错误情况和状态标签
            updateStatusDisplay(record)

            // 编辑模式下，设置状态标签可点击切换
            if (isEditMode) {
                tvStatus.isClickable = true
                tvStatus.setOnClickListener {
                    val latestRecord = currentRecord ?: record
                    val newIsCorrect = !latestRecord.isCorrect
                    val newErrorCount = if (newIsCorrect) {
                        // 从"错误"变为"正确"：errorCount - 1（最小为 0）
                        max(0, latestRecord.errorCount - 1)
                    } else {
                        // 从"正确"变为"错误"：errorCount + 1（如果 totalCount > 0）
                        if (latestRecord.totalCount > 0) {
                            latestRecord.errorCount + 1
                        } else {
                            latestRecord.errorCount
                        }
                    }
                    
                    val updatedRecord = latestRecord.copy(
                        isCorrect = newIsCorrect,
                        errorCount = newErrorCount
                    )
                    // 更新 currentRecord
                    currentRecord = updatedRecord
                    // 保存到本地 Map
                    localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                    
                    // 更新 UI 显示
                    updateStatusDisplay(updatedRecord)
                }
            } else {
                tvStatus.isClickable = false
                tvStatus.setOnClickListener(null)
            }

            // 设置翻译或答案（统一使用 EditText，通过属性控制可编辑性）
            when (record.type) {
                LearningRecordType.ENGLISH_DICTATION -> {
                    if (isEditMode) {
                        etTranslationOrAnswer.visibility = View.VISIBLE
                        etTranslationOrAnswer.hint = "翻译"
                        // 编辑模式：可编辑
                        etTranslationOrAnswer.isFocusable = true
                        etTranslationOrAnswer.isFocusableInTouchMode = true
                        etTranslationOrAnswer.isEnabled = true
                        etTranslationOrAnswer.isClickable = true
                        etTranslationOrAnswer.background = itemView.context.resources.getDrawable(android.R.drawable.edit_text, null)
                        
                        // 如果 EditText 正在编辑（有焦点）且文本相同，跳过设置，避免光标位置被重置
                        val isEditing = etTranslationOrAnswer.hasFocus()
                        val currentText = etTranslationOrAnswer.text?.toString() ?: ""
                        val targetText = record.translation
                        if (isEditing && currentText == targetText) {
                            // 更新 currentRecord，然后只更新 TextWatcher，不重新设置文本
                            currentRecord = record
                            // 移除旧的 TextWatcher
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            // 创建新的 TextWatcher，使用最新的 record，只更新本地 Map
                            translationOrAnswerTextWatcher =
                                object : TextWatcher {
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
                                        val newText = s?.toString() ?: ""
                                        val latestRecord = currentRecord ?: record
                                        // 只更新本地 Map，不触发 ViewModel 更新
                                        val updatedRecord = latestRecord.copy(translation = newText)
                                        localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                                    }
                                }
                            etTranslationOrAnswer.tag = translationOrAnswerTextWatcher
                            etTranslationOrAnswer.addTextChangedListener(translationOrAnswerTextWatcher)
                        } else {
                            setupTranslationOrAnswerEditText(record, "翻译")
                        }
                    } else {
                        // 非编辑模式：只读显示
                        if (record.translation.isNotEmpty()) {
                            etTranslationOrAnswer.visibility = View.VISIBLE
                            etTranslationOrAnswer.isFocusable = false
                            etTranslationOrAnswer.isFocusableInTouchMode = false
                            etTranslationOrAnswer.isEnabled = false
                            etTranslationOrAnswer.isClickable = false
                            etTranslationOrAnswer.background = null
                            etTranslationOrAnswer.setText("翻译：${record.translation}")
                            
                            // 移除 TextWatcher
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            translationOrAnswerTextWatcher = null
                        } else {
                            etTranslationOrAnswer.visibility = View.GONE
                        }
                    }
                }
                LearningRecordType.ERROR_BOOK -> {
                    if (isEditMode) {
                        etTranslationOrAnswer.visibility = View.VISIBLE
                        etTranslationOrAnswer.hint = "答案"
                        // 编辑模式：可编辑
                        etTranslationOrAnswer.isFocusable = true
                        etTranslationOrAnswer.isFocusableInTouchMode = true
                        etTranslationOrAnswer.isEnabled = true
                        etTranslationOrAnswer.isClickable = true
                        etTranslationOrAnswer.background = itemView.context.resources.getDrawable(android.R.drawable.edit_text, null)
                        
                        // 如果 EditText 正在编辑（有焦点）且文本相同，跳过设置，避免光标位置被重置
                        val isEditing = etTranslationOrAnswer.hasFocus()
                        val currentText = etTranslationOrAnswer.text?.toString() ?: ""
                        val targetText = record.answer
                        if (isEditing && currentText == targetText) {
                            // 更新 currentRecord，然后只更新 TextWatcher，不重新设置文本
                            currentRecord = record
                            // 移除旧的 TextWatcher
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            // 创建新的 TextWatcher，使用最新的 record，只更新本地 Map
                            translationOrAnswerTextWatcher =
                                object : TextWatcher {
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
                                        val newText = s?.toString() ?: ""
                                        val latestRecord = currentRecord ?: record
                                        // 只更新本地 Map，不触发 ViewModel 更新
                                        val updatedRecord = latestRecord.copy(answer = newText)
                                        localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                                    }
                                }
                            etTranslationOrAnswer.tag = translationOrAnswerTextWatcher
                            etTranslationOrAnswer.addTextChangedListener(translationOrAnswerTextWatcher)
                        } else {
                            setupTranslationOrAnswerEditText(record, "答案")
                        }
                    } else {
                        // 非编辑模式：只读显示
                        if (record.answer.isNotEmpty()) {
                            etTranslationOrAnswer.visibility = View.VISIBLE
                            etTranslationOrAnswer.isFocusable = false
                            etTranslationOrAnswer.isFocusableInTouchMode = false
                            etTranslationOrAnswer.isEnabled = false
                            etTranslationOrAnswer.isClickable = false
                            etTranslationOrAnswer.background = null
                            etTranslationOrAnswer.setText("答案：${record.answer}")
                            
                            // 移除 TextWatcher
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            translationOrAnswerTextWatcher = null
                        } else {
                            etTranslationOrAnswer.visibility = View.GONE
                        }
                    }
                }
                else -> {
                    etTranslationOrAnswer.visibility = View.GONE
                }
            }
        }

        /**
         * 只更新编辑模式相关的UI，不重新设置文本（如果文本已存在）
         * 这个方法比 bind() 快得多，因为跳过了很多不必要的操作
         */
        fun bindEditModeOnly(
            record: LearningRecord,
        ) {
            currentRecord = record

            // 3. 设置内容 EditText 的可编辑性
            if (isEditMode) {
                // 编辑模式：可编辑
                etContent.isFocusable = true
                etContent.isFocusableInTouchMode = true
                etContent.isEnabled = true
                etContent.isClickable = true
                etContent.background = itemView.context.resources.getDrawable(android.R.drawable.edit_text, null)

                // 检查文本是否需要更新
                val currentText = etContent.text?.toString() ?: ""
                if (currentText != record.content) {
                    // 移除旧的 TextWatcher，避免在 setText 时触发
                    contentTextWatcher?.let { etContent.removeTextChangedListener(it) }
                    etContent.setText(record.content)
                    // 重新设置 TextWatcher
                    setupContentEditText(record)
                } else if (contentTextWatcher == null) {
                    // 如果文本相同但 TextWatcher 不存在，创建它
                    setupContentEditText(record)
                }
            } else {
                // 非编辑模式：只读显示
                etContent.isFocusable = false
                etContent.isFocusableInTouchMode = false
                etContent.isEnabled = false
                etContent.isClickable = false
                etContent.background = null
                
                // 更新文本（带下划线样式）
                etContent.setText(SpannableTextUtils.createUnderlinedText(record.content))
                
                // 移除 TextWatcher
                contentTextWatcher?.let { etContent.removeTextChangedListener(it) }
                contentTextWatcher = null
            }

            // 4. 处理翻译/答案字段
            when (record.type) {
                LearningRecordType.ENGLISH_DICTATION -> {
                    if (isEditMode) {
                        etTranslationOrAnswer.visibility = View.VISIBLE
                        etTranslationOrAnswer.hint = "翻译"
                        // 编辑模式：可编辑
                        etTranslationOrAnswer.isFocusable = true
                        etTranslationOrAnswer.isFocusableInTouchMode = true
                        etTranslationOrAnswer.isEnabled = true
                        etTranslationOrAnswer.isClickable = true
                        etTranslationOrAnswer.background = itemView.context.resources.getDrawable(android.R.drawable.edit_text, null)
                        
                        val currentText = etTranslationOrAnswer.text?.toString() ?: ""
                        if (currentText != record.translation) {
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            etTranslationOrAnswer.setText(record.translation)
                            setupTranslationOrAnswerEditText(record, "翻译")
                        } else if (translationOrAnswerTextWatcher == null) {
                            setupTranslationOrAnswerEditText(record, "翻译")
                        }
                    } else {
                        // 非编辑模式：只读显示
                        if (record.translation.isNotEmpty()) {
                            etTranslationOrAnswer.visibility = View.VISIBLE
                            etTranslationOrAnswer.isFocusable = false
                            etTranslationOrAnswer.isFocusableInTouchMode = false
                            etTranslationOrAnswer.isEnabled = false
                            etTranslationOrAnswer.isClickable = false
                            etTranslationOrAnswer.background = null
                            etTranslationOrAnswer.setText("翻译：${record.translation}")
                            
                            // 移除 TextWatcher
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            translationOrAnswerTextWatcher = null
                        } else {
                            etTranslationOrAnswer.visibility = View.GONE
                        }
                    }
                }
                LearningRecordType.ERROR_BOOK -> {
                    if (isEditMode) {
                        etTranslationOrAnswer.visibility = View.VISIBLE
                        etTranslationOrAnswer.hint = "答案"
                        // 编辑模式：可编辑
                        etTranslationOrAnswer.isFocusable = true
                        etTranslationOrAnswer.isFocusableInTouchMode = true
                        etTranslationOrAnswer.isEnabled = true
                        etTranslationOrAnswer.isClickable = true
                        etTranslationOrAnswer.background = itemView.context.resources.getDrawable(android.R.drawable.edit_text, null)
                        
                        val currentText = etTranslationOrAnswer.text?.toString() ?: ""
                        if (currentText != record.answer) {
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            etTranslationOrAnswer.setText(record.answer)
                            setupTranslationOrAnswerEditText(record, "答案")
                        } else if (translationOrAnswerTextWatcher == null) {
                            setupTranslationOrAnswerEditText(record, "答案")
                        }
                    } else {
                        // 非编辑模式：只读显示
                        if (record.answer.isNotEmpty()) {
                            etTranslationOrAnswer.visibility = View.VISIBLE
                            etTranslationOrAnswer.isFocusable = false
                            etTranslationOrAnswer.isFocusableInTouchMode = false
                            etTranslationOrAnswer.isEnabled = false
                            etTranslationOrAnswer.isClickable = false
                            etTranslationOrAnswer.background = null
                            etTranslationOrAnswer.setText("答案：${record.answer}")
                            
                            // 移除 TextWatcher
                            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }
                            translationOrAnswerTextWatcher = null
                        } else {
                            etTranslationOrAnswer.visibility = View.GONE
                        }
                    }
                }
                else -> {
                    etTranslationOrAnswer.visibility = View.GONE
                }
            }

            // 5. 更新错误计数和状态（这些在编辑模式下也需要显示）
            updateStatusDisplay(record)

            // 编辑模式下，设置状态标签可点击切换
            tvStatus.isClickable = true
            tvStatus.setOnClickListener {
                val latestRecord = currentRecord ?: record
                val newIsCorrect = !latestRecord.isCorrect
                val newErrorCount = if (newIsCorrect) {
                    // 从"错误"变为"正确"：errorCount - 1（最小为 0）
                    max(0, latestRecord.errorCount - 1)
                } else {
                    // 从"正确"变为"错误"：errorCount + 1（如果 totalCount > 0）
                    if (latestRecord.totalCount > 0) {
                        latestRecord.errorCount + 1
                    } else {
                        latestRecord.errorCount
                    }
                }
                
                val updatedRecord = latestRecord.copy(
                    isCorrect = newIsCorrect,
                    errorCount = newErrorCount
                )
                // 更新 currentRecord
                currentRecord = updatedRecord
                // 保存到本地 Map
                localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                
                // 更新 UI 显示
                updateStatusDisplay(updatedRecord)
            }
        }

        private fun setupContentEditText(record: LearningRecord) {
            // 移除之前的TextWatcher
            contentTextWatcher?.let { etContent.removeTextChangedListener(it) }

            // 保存当前焦点和光标位置
            val hadFocus = etContent.hasFocus()
            val selectionStart = etContent.selectionStart
            val selectionEnd = etContent.selectionEnd
            val currentText = etContent.text?.toString() ?: ""

            // 如果 EditText 有焦点且文本相同，完全跳过设置文本，避免光标位置被重置
            if (hadFocus && currentText == record.content) {
                // 更新 currentRecord
                currentRecord = record
                // 只重新添加 TextWatcher，不设置文本
                contentTextWatcher =
                    object : TextWatcher {
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
                            val newContent = s?.toString() ?: ""
                            val latestRecord = currentRecord ?: record
                            // 只更新本地 Map，不触发 ViewModel 更新
                            val updatedRecord = latestRecord.copy(content = newContent)
                            localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                        }
                    }
                etContent.tag = contentTextWatcher
                etContent.addTextChangedListener(contentTextWatcher)
                return
            }

            // 只有在文本真的改变时才设置文本
            if (currentText != record.content) {
                etContent.setText(record.content)

                // 使用 post 延迟恢复光标位置，确保 setText 完成后再设置
                if (hadFocus && selectionStart >= 0 && selectionEnd >= 0) {
                    etContent.post {
                        val newLength = record.content.length
                        val safeStart = selectionStart.coerceIn(0, newLength)
                        val safeEnd = selectionEnd.coerceIn(safeStart, newLength)
                        etContent.setSelection(safeStart, safeEnd)
                    }
                }
            }

            // 创建新的TextWatcher，只更新本地 Map，不触发 ViewModel 更新
            contentTextWatcher =
                object : TextWatcher {
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
                        val newContent = s?.toString() ?: ""
                        val latestRecord = currentRecord ?: record
                        // 只更新本地 Map，不触发 ViewModel 更新
                        val updatedRecord = latestRecord.copy(content = newContent)
                        localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                    }
                }
            etContent.tag = contentTextWatcher
            etContent.addTextChangedListener(contentTextWatcher)
        }

        private fun setupTranslationOrAnswerEditText(
            record: LearningRecord,
            type: String,
        ) {
            // 移除之前的TextWatcher
            translationOrAnswerTextWatcher?.let { etTranslationOrAnswer.removeTextChangedListener(it) }

            // 保存当前焦点和光标位置
            val hadFocus = etTranslationOrAnswer.hasFocus()
            val selectionStart = etTranslationOrAnswer.selectionStart
            val selectionEnd = etTranslationOrAnswer.selectionEnd
            val currentText = etTranslationOrAnswer.text?.toString() ?: ""

            // 设置文本
            val text =
                when (type) {
                    "翻译" -> record.translation
                    "答案" -> record.answer
                    else -> ""
                }

            // 如果 EditText 有焦点且文本相同，完全跳过设置文本，避免光标位置被重置
            if (hadFocus && currentText == text) {
                // 更新 currentRecord
                currentRecord = record
                // 只重新添加 TextWatcher，不设置文本
                translationOrAnswerTextWatcher =
                    object : TextWatcher {
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
                            val newText = s?.toString() ?: ""
                            val latestRecord = currentRecord ?: record
                            // 只更新本地 Map，不触发 ViewModel 更新
                            val updatedRecord =
                                when (type) {
                                    "翻译" -> latestRecord.copy(translation = newText)
                                    "答案" -> latestRecord.copy(answer = newText)
                                    else -> latestRecord
                                }
                            localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                        }
                    }
                etTranslationOrAnswer.tag = translationOrAnswerTextWatcher
                etTranslationOrAnswer.addTextChangedListener(translationOrAnswerTextWatcher)
                return
            }

            // 只有在文本真的改变时才设置文本
            if (currentText != text) {
                etTranslationOrAnswer.setText(text)

                // 使用 post 延迟恢复光标位置，确保 setText 完成后再设置
                if (hadFocus && selectionStart >= 0 && selectionEnd >= 0) {
                    etTranslationOrAnswer.post {
                        val newLength = text.length
                        val safeStart = selectionStart.coerceIn(0, newLength)
                        val safeEnd = selectionEnd.coerceIn(safeStart, newLength)
                        etTranslationOrAnswer.setSelection(safeStart, safeEnd)
                    }
                }
            }

            // 创建新的TextWatcher，只更新本地 Map，不触发 ViewModel 更新
            translationOrAnswerTextWatcher =
                object : TextWatcher {
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
                        val newText = s?.toString() ?: ""
                        val latestRecord = currentRecord ?: record
                        // 只更新本地 Map，不触发 ViewModel 更新
                        val updatedRecord =
                            when (type) {
                                "翻译" -> latestRecord.copy(translation = newText)
                                "答案" -> latestRecord.copy(answer = newText)
                                else -> latestRecord
                            }
                        localEditedRecords[latestRecord.getUniqueId()] = updatedRecord
                    }
                }
            etTranslationOrAnswer.tag = translationOrAnswerTextWatcher
            etTranslationOrAnswer.addTextChangedListener(translationOrAnswerTextWatcher)
        }

        /**
         * 更新状态标签和错误计数的显示
         */
        private fun updateStatusDisplay(record: LearningRecord) {
            // 设置错误情况
            if (record.totalCount > 0) {
                tvErrorCount.text = "${record.errorCount}/${record.totalCount}"
            } else {
                tvErrorCount.text = ""
            }

            // 设置状态标签
            if (record.isCorrect) {
                tvStatus.text = "正确"
                tvStatus.setTextColor(itemView.context.resources.getColor(android.R.color.holo_green_dark, null))
            } else {
                tvStatus.text = "错误"
                tvStatus.setTextColor(itemView.context.resources.getColor(R.color.primary_blue, null))
            }
        }
    }
}
