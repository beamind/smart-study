package com.smartstudy.dictation

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.dictation.core.DictationWordStat

/**
 * 听写记录适配器
 */
class DictationRecordAdapter(
    private val stats: List<DictationWordStat>,
    private val isEditMode: Boolean,
    private val selectedWords: Set<String>,
    private val editedWords: Map<String, String>, // 从 ViewModel State 读取编辑内容
    private val onItemLongClick: () -> Unit = {}, // 已废弃，保留以保持兼容性
    private val onWordSelected: (String, Boolean) -> Unit,
    private val onWordUpdated: (String, String) -> Unit, // 旧词 -> 新词，更新 ViewModel State
) : RecyclerView.Adapter<DictationRecordAdapter.RecordViewHolder>() {
    class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        val etWord: EditText = itemView.findViewById(R.id.etWord)
        val tvErrorCount: TextView = itemView.findViewById(R.id.tvErrorCount)
        val llSourceTags: LinearLayout = itemView.findViewById(R.id.llSourceTags)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecordViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dictation_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: RecordViewHolder,
        position: Int,
    ) {
        val stat = stats[position]
        val word = stat.word
        val originalWord = word
        val isSelected = selectedWords.contains(word)

        // 设置词语文本和监听器
        setupWordText(holder, position, originalWord)

        // 设置错误情况（a/b格式）
        holder.tvErrorCount.text = "${stat.errorCount}/${stat.totalCount}"

        // 设置来源标签
        setupSourceTags(holder, stat)

        // 编辑模式：显示复选框
        if (isEditMode) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.isChecked = isSelected
            holder.cbSelect.setOnCheckedChangeListener { _, checked ->
                onWordSelected(word, checked)
            }
        } else {
            holder.cbSelect.visibility = View.GONE
        }

        // 编辑模式下，点击item切换选中状态
        if (isEditMode) {
            holder.itemView.setOnClickListener {
                holder.cbSelect.isChecked = !holder.cbSelect.isChecked
            }
        } else {
            holder.itemView.setOnClickListener(null)
        }
    }

    /**
     * 设置词语文本和监听器
     * @param holder ViewHolder
     * @param position 位置
     * @param word 原始词语文本
     */
    private fun setupWordText(
        holder: RecordViewHolder,
        position: Int,
        word: String,
    ) {
        // 获取编辑后的文本（如果有），否则使用原始文本
        val displayText = editedWords[word] ?: word

        // 先移除之前的TextWatcher，避免在设置文本时触发
        holder.etWord.removeTextChangedListener(holder.etWord.tag as? TextWatcher)

        // 设置EditText的文本
        holder.etWord.setText(displayText)

        // 根据编辑模式控制词语是否可编辑
        holder.etWord.isFocusable = isEditMode
        holder.etWord.isFocusableInTouchMode = isEditMode
        holder.etWord.isEnabled = isEditMode
        holder.etWord.isClickable = isEditMode

        // 非编辑模式下，设置背景为透明，看起来像只读文本
        if (!isEditMode) {
            holder.etWord.background = null
        } else {
            holder.etWord.background = holder.itemView.context.resources.getDrawable(android.R.drawable.edit_text)
        }

        // 创建新的TextWatcher来监听文本变化
        val textWatcher = createWordTextWatcher(position, word)
        holder.etWord.tag = textWatcher
        holder.etWord.addTextChangedListener(textWatcher)
    }

    /**
     * 创建词语文本监听器
     * @param position 位置
     * @param originalWord 原始词语文本
     * @return TextWatcher
     */
    private fun createWordTextWatcher(
        position: Int,
        originalWord: String,
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
                // 获取编辑后的文本
                val newText = s?.toString() ?: ""
                // 通知 ViewModel 更新编辑内容（ViewModel 会更新 State）
                onWordUpdated(originalWord, newText)
            }
        }
    }

    override fun getItemCount(): Int = stats.size

    /**
     * 设置来源标签
     */
    private fun setupSourceTags(holder: RecordViewHolder, stat: DictationWordStat) {
        holder.llSourceTags.removeAllViews()

        if (stat.sourceTypes.contains("new")) {
            val tagView = createSourceTag(
                holder.itemView.context,
                holder.itemView.context.getString(R.string.source_new),
                android.R.color.holo_blue_dark,
            )
            holder.llSourceTags.addView(tagView)
        }

        if (stat.sourceTypes.contains("error")) {
            val tagView = createSourceTag(
                holder.itemView.context,
                holder.itemView.context.getString(R.string.source_error),
                android.R.color.holo_red_dark,
            )
            holder.llSourceTags.addView(tagView)
        }

        // 显示最近一次听写结果标签
        stat.lastDictationIsCorrect?.let { isCorrect ->
            val tagView = createSourceTag(
                holder.itemView.context,
                holder.itemView.context.getString(
                    if (isCorrect) R.string.source_last_correct else R.string.source_last_error,
                ),
                if (isCorrect) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
            )
            holder.llSourceTags.addView(tagView)
        }
    }

    /**
     * 创建来源标签视图
     */
    private fun createSourceTag(context: android.content.Context, text: String, textColorRes: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(context.resources.getColor(textColorRes, null))
            val padding = context.resources.getDimensionPixelSize(R.dimen.spacing_xs)
            setPadding(padding, padding / 2, padding, padding / 2)
            background = context.resources.getDrawable(R.drawable.bg_tag, null)
        }
    }
}
