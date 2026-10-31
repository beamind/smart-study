package com.smartstudy.dictation

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.ResourceManager
import com.smartstudy.core.ResourceManagerHelper
import com.smartstudy.databinding.ActivityErrorWordListBinding
import com.smartstudy.dictation.core.DictationWordStat
import com.smartstudy.dictation.managers.ErrorWordListManager
import com.smartstudy.dictation.managers.ErrorWordListUIManager
import com.smartstudy.utils.ToastHelper
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

/**
 * 错题列表展示Activity
 * 按照错误率从高到低展示错题（只显示有错误的题目）
 */
class ErrorWordListActivity : AppCompatActivity(), ResourceManager {
    companion object {
        private const val TAG = "ErrorWordListActivity"
        const val EXTRA_TYPE = "type" // "chinese" 或 "english"
        const val EXTRA_USER_ID = "user_id" // 用户ID
    }

    private lateinit var binding: ActivityErrorWordListBinding
    private val viewModel: ErrorWordListViewModel by viewModel { parametersOf(this) }

    // 错词列表管理器
    private lateinit var manager: ErrorWordListManager

    // UI管理器
    private lateinit var uiManager: ErrorWordListUIManager

    // 保存之前的状态，用于比较变化
    private var previousState: ErrorWordListState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErrorWordListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 从Intent获取类型和用户ID
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "chinese"
        val userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""

        // 初始化ViewModel
        viewModel.initialize(type, userId)

        // 初始化管理器
        initManager()

        initViews()
        observeViewModel()
    }

    /**
     * 初始化错词列表管理器
     */
    private fun initManager() {
        manager =
            ErrorWordListManager(
                activity = this,
                viewModel = viewModel,
                adapterGetter = { uiManager.getAdapter() },
                onAddSuccess = { count ->
                    ToastHelper.showShort(this, R.string.error_word_list_add_success, count)
                },
            )

        // 初始化UI管理器
        uiManager =
            ErrorWordListUIManager(
                activity = this,
                binding = binding,
            )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_error_word_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // 在菜单显示前更新菜单项，确保菜单始终反映最新状态
        val state = viewModel.uiState.value
        val editItem = menu.findItem(R.id.menu_edit)
        val selectAllItem = menu.findItem(R.id.menu_select_all)
        val deleteItem = menu.findItem(R.id.menu_delete)
        val saveItem = menu.findItem(R.id.menu_save)
        val cancelItem = menu.findItem(R.id.menu_cancel)

        if (editItem != null) {
            if (state.isEditMode) {
                editItem.setTitle("完成")
                editItem.setIcon(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                editItem.setTitle("编辑")
                editItem.setIcon(android.R.drawable.ic_menu_edit)
            }
        }

        // 根据编辑模式显示/隐藏编辑相关菜单项
        if (state.isEditMode) {
            selectAllItem?.isVisible = true
            deleteItem?.isVisible = true
            saveItem?.isVisible = true
            cancelItem?.isVisible = true

            // 更新全选菜单项标题
            uiManager.getAdapter()?.let { adapter ->
                val allWords = adapter.getAllWords()
                val isAllSelected = allWords.isNotEmpty() && state.selectedWords.size == allWords.size
                selectAllItem?.setTitle(
                    if (isAllSelected) {
                        getString(R.string.error_word_list_deselect_all)
                    } else {
                        getString(R.string.error_word_list_select_all)
                    },
                )
            }

            // 根据选中数量启用/禁用删除按钮
            deleteItem?.isEnabled = state.selectedWords.isNotEmpty()
        } else {
            selectAllItem?.isVisible = false
            deleteItem?.isVisible = false
            saveItem?.isVisible = false
            cancelItem?.isVisible = false
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            R.id.menu_edit -> {
                val state = viewModel.uiState.value
                if (state.isEditMode) {
                    viewModel.exitEditMode()
                } else {
                    viewModel.enterEditMode()
                    binding.root.post {
                        ToastHelper.showShort(this@ErrorWordListActivity, R.string.error_book_enter_edit_mode)
                    }
                }
                invalidateOptionsMenu()
                return true
            }
            R.id.menu_add_words -> {
                manager.showAddWordsDialog()
                return true
            }
            R.id.menu_select_all -> {
                uiManager.getAdapter()?.let { adapter ->
                    val allWords = adapter.getAllWords()
                    viewModel.toggleSelectAll(allWords)
                    invalidateOptionsMenu()
                }
                return true
            }
            R.id.menu_delete -> {
                manager.performDelete()
                return true
            }
            R.id.menu_save -> {
                manager.saveEditedWords()
                return true
            }
            R.id.menu_cancel -> {
                viewModel.exitEditMode()
                invalidateOptionsMenu()
                return true
            }
            else -> {
                return super.onOptionsItemSelected(item)
            }
        }
    }

    /**
     * 观察 ViewModel 的状态变化
     * 使用 StateFlow 统一管理状态，提高性能和代码可维护性
     * 使用 repeatOnLifecycle 确保只在 Activity 处于活跃状态时收集状态，避免资源浪费
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val prevState = previousState

                    updateTitleIfNeeded(prevState, state)
                    updateErrorWordList(prevState, state)
                    updateAdapterState(prevState, state)

                    handleErrorState(state)

                    // 保存当前状态作为下一次的前一个状态
                    previousState = state
                }
            }
        }
    }

    private fun initViews() {
        // 设置 Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 设置RecyclerView（委托给UI Manager）
        uiManager.setupRecyclerView()
    }

    override fun onBackPressed() {
        val state = viewModel.uiState.value

        if (state.isEditMode) {
            viewModel.exitEditMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
    }

    /**
     * 释放所有资源
     * 实现ResourceManager接口，统一管理资源释放，防止内存泄漏
     */
    override fun releaseResources() {
        // 清理适配器（委托给UI Manager）
        ResourceManagerHelper.safeRelease {
            uiManager.setAdapter(null)
        }
    }

    /**
     * 检查是否保存成功（从编辑模式退出且有编辑内容）
     */
    private fun isSaveSuccess(
        prevState: ErrorWordListState?,
        state: ErrorWordListState,
    ): Boolean {
        return prevState != null &&
            prevState.isEditMode &&
            !state.isEditMode &&
            prevState.editedTexts.isNotEmpty()
    }

    /**
     * 更新标题（如果需要）
     */
    private fun updateTitleIfNeeded(prevState: ErrorWordListState?, state: ErrorWordListState) {
        if (prevState == null || prevState.currentType != state.currentType) {
            uiManager.updateTitle(state.currentType == "chinese")
        }
    }

    /**
     * 更新错题列表
     */
    private fun updateErrorWordList(prevState: ErrorWordListState?, state: ErrorWordListState) {
        val adapter = uiManager.getAdapter()
        if (state.errorWordStats.isNotEmpty()) {
            uiManager.showEmptyState(false)
            if (adapter == null) {
                // 首次创建适配器
                uiManager.updateErrorWordList(
                    stats = state.errorWordStats,
                    viewModel = viewModel,
                )
            } else if (prevState?.errorWordStats != state.errorWordStats) {
                // 适配器已存在且数据变化，更新数据
                adapter.updateStats(state.errorWordStats)
            }
        } else if (!state.isLoading) {
            uiManager.showEmptyState(true)
        }
    }

    /**
     * 更新适配器状态和UI
     */
    private fun updateAdapterState(prevState: ErrorWordListState?, state: ErrorWordListState) {
        val adapter = uiManager.getAdapter() ?: return
        val isFirstState = prevState == null
        val editModeChanged = isFirstState || prevState?.isEditMode != state.isEditMode

        // 编辑模式或选中状态变化时，通知适配器更新
        if (isFirstState ||
            editModeChanged ||
            prevState?.selectedWords != state.selectedWords
        ) {
            adapter.notifyStateChanged()
        }

        // 更新编辑模式UI（只在编辑模式状态变化时更新）
        if (editModeChanged) {
            handleEditModeChange(prevState, state)
        }

        // 更新编辑工具栏（只在选中数量或总数变化时更新）
        updateEditToolbarIfNeeded(prevState, state, adapter, isFirstState)
    }

    /**
     * 处理编辑模式变化
     */
    private fun handleEditModeChange(prevState: ErrorWordListState?, state: ErrorWordListState) {
        uiManager.updateEditMode()
        invalidateOptionsMenu()

        // 检测从编辑模式退出，且之前有编辑内容，说明是保存成功
        if (isSaveSuccess(prevState, state)) {
            ToastHelper.showShort(this@ErrorWordListActivity, R.string.error_word_list_save_success)
        }
    }

    /**
     * 更新编辑工具栏（如果需要）
     */
    private fun updateEditToolbarIfNeeded(
        prevState: ErrorWordListState?,
        state: ErrorWordListState,
        adapter: com.smartstudy.dictation.ErrorWordAdapter,
        isFirstState: Boolean,
    ) {
        if (isFirstState ||
            prevState?.selectedWords?.size != state.selectedWords.size ||
            adapter.itemCount != prevState?.errorWordStats?.size
        ) {
            uiManager.updateEditToolbar()
            // 更新菜单以反映选中状态变化
            if (state.isEditMode) {
                invalidateOptionsMenu()
            }
        }
    }

    /**
     * 处理错误状态
     */
    private fun handleErrorState(state: ErrorWordListState) {
        state.error?.let { error ->
            ToastHelper.showLong(this@ErrorWordListActivity, error)
            // 如果还在编辑模式，恢复保存按钮状态（保存失败时）
            if (state.isEditMode) {
                uiManager.updateSaveButtonState()
            }
            viewModel.clearError()
        }
    }
}

/**
 * 错题列表适配器
 *
 * 状态管理已迁移到 ViewModel，适配器不再维护自己的状态。
 * 所有状态（isEditMode, selectedWords, editedTexts）都从 ViewModel 获取。
 */
class ErrorWordAdapter(
    // 改为 var，以便更新数据
    var stats: List<DictationWordStat>,
    private val viewModel: ErrorWordListViewModel,
    private val currentType: String = "chinese",
) : RecyclerView.Adapter<ErrorWordAdapter.ErrorWordViewHolder>() {
    companion object {
        // 错词列表项背景颜色常量
        private const val COLOR_SELECTED_BACKGROUND = 0x33000000.toInt() // 选中状态：半透明黑色
        private const val COLOR_NORMAL_BACKGROUND = 0x00000000.toInt() // 普通状态：透明
    }

    // 保存之前的状态，用于比较变化
    private var previousSelectedWordsState: Set<String>? = null
    private var previousEditMode: Boolean? = null

    class ErrorWordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        val etWord: EditText = itemView.findViewById(R.id.etWord)
        val etTranslation: EditText = itemView.findViewById(R.id.etTranslation)
        val tvErrorCount: TextView = itemView.findViewById(R.id.tvErrorCount)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ErrorWordViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_error_word, parent, false)
        return ErrorWordViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ErrorWordViewHolder,
        position: Int,
    ) {
        val stat = stats[position]
        val word = stat.word
        val state = viewModel.uiState.value
        val isEditMode = state.isEditMode
        val isSelected = state.selectedWords.contains(word)

        // 获取编辑后的文本（如果有），否则使用原始文本
        val editedWord = state.editedTexts[word]?.first ?: word
        val editedTranslation = state.editedTexts[word]?.second ?: stat.chineseTranslation

        setupTextViews(holder, editedWord, editedTranslation, stat)
        setupEditTexts(holder, isEditMode)
        setupTextWatchers(holder, word, stat)
        setupCheckbox(holder, isEditMode, isSelected, word)
        setupItemView(holder, isEditMode, isSelected)
    }

    private fun setupTextViews(
        holder: ErrorWordViewHolder,
        editedWord: String,
        editedTranslation: String,
        stat: DictationWordStat,
    ) {
        // 先移除之前的 TextWatcher，避免在设置文本时触发
        holder.etWord.removeTextChangedListener(holder.etWord.tag as? TextWatcher)
        holder.etTranslation.removeTextChangedListener(holder.etTranslation.tag as? TextWatcher)

        // 设置 EditText 的文本
        holder.etWord.setText(editedWord)
        holder.tvErrorCount.text = "${stat.errorCount}/${stat.totalCount}"

        // 显示翻译（仅英文类型）
        if (currentType == "english") {
            holder.etTranslation.visibility = View.VISIBLE
            holder.etTranslation.setText(editedTranslation)
        } else {
            holder.etTranslation.visibility = View.GONE
        }

        // 序号始终隐藏
        holder.tvRank.visibility = View.GONE
    }

    private fun setupEditTexts(
        holder: ErrorWordViewHolder,
        isEditMode: Boolean,
    ) {
        // 根据编辑模式设置 EditText 的可编辑状态
        holder.etWord.isFocusable = isEditMode
        holder.etWord.isFocusableInTouchMode = isEditMode
        holder.etWord.isEnabled = isEditMode
        holder.etWord.isClickable = isEditMode

        val isTranslationEditable = isEditMode && currentType == "english"
        holder.etTranslation.isFocusable = isTranslationEditable
        holder.etTranslation.isFocusableInTouchMode = isTranslationEditable
        holder.etTranslation.isEnabled = isTranslationEditable
        holder.etTranslation.isClickable = isTranslationEditable
    }

    private fun setupTextWatchers(
        holder: ErrorWordViewHolder,
        word: String,
        stat: DictationWordStat,
    ) {
        // 创建单词的 TextWatcher
        val wordWatcher = createWordWatcher(word, stat)
        // 创建翻译的 TextWatcher
        val translationWatcher = createTranslationWatcher(word)

        // 保存 TextWatcher 到 tag 中，以便后续移除
        holder.etWord.tag = wordWatcher
        holder.etTranslation.tag = translationWatcher
        holder.etWord.addTextChangedListener(wordWatcher)
        holder.etTranslation.addTextChangedListener(translationWatcher)
    }

    private fun createWordWatcher(word: String, stat: DictationWordStat): TextWatcher {
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
                val newWord = s?.toString() ?: ""
                val currentState = viewModel.uiState.value
                val currentTranslation = currentState.editedTexts[word]?.second ?: stat.chineseTranslation
                viewModel.updateEditedText(word, newWord, currentTranslation)
            }
        }
    }

    private fun createTranslationWatcher(word: String): TextWatcher {
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
                val newTranslation = s?.toString() ?: ""
                val currentState = viewModel.uiState.value
                val currentWord = currentState.editedTexts[word]?.first ?: word
                viewModel.updateEditedText(word, currentWord, newTranslation)
            }
        }
    }

    private fun setupCheckbox(
        holder: ErrorWordViewHolder,
        isEditMode: Boolean,
        isSelected: Boolean,
        word: String,
    ) {
        // 编辑模式：显示复选框
        holder.cbSelect.visibility = if (isEditMode) View.VISIBLE else View.GONE

        // 复选框点击监听（先清除之前的监听器，避免在设置状态时触发）
        holder.cbSelect.setOnCheckedChangeListener(null)
        // 先设置状态，此时没有监听器，不会触发回调
        holder.cbSelect.isChecked = isSelected
        // 再设置监听器
        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            if (isEditMode) {
                viewModel.toggleWordSelection(word, isChecked)
                // 状态变化会通过 observeViewModel 触发适配器更新，无需手动调用 notifyItemChanged
            }
        }
    }

    private fun setupItemView(
        holder: ErrorWordViewHolder,
        isEditMode: Boolean,
        isSelected: Boolean,
    ) {
        // 设置选中状态背景
        val backgroundColor = if (isEditMode && isSelected) {
            COLOR_SELECTED_BACKGROUND
        } else {
            COLOR_NORMAL_BACKGROUND
        }
        holder.itemView.setBackgroundColor(backgroundColor)

        // 编辑模式下，点击item切换选中状态
        holder.itemView.setOnClickListener(
            if (isEditMode) {
                { holder.cbSelect.isChecked = !holder.cbSelect.isChecked }
            } else {
                null
            },
        )
    }

    override fun getItemCount(): Int = stats.size

    /**
     * 更新统计数据
     * @param newStats 新的统计数据列表
     */
    fun updateStats(newStats: List<DictationWordStat>) {
        stats = newStats
        notifyDataSetChanged()
    }

    /**
     * 通知适配器状态已更新（由 Activity 在观察 ViewModel 状态时调用）
     * 适配器不再维护自己的状态，所有状态都从 ViewModel 获取
     */
    fun notifyStateChanged() {
        val state = viewModel.uiState.value
        // 保存之前的状态用于比较
        val previousSelectedWords = previousSelectedWordsState ?: emptySet()
        val wasEditMode = previousEditMode ?: false

        // 检查编辑模式是否变化
        val editModeChanged = wasEditMode != state.isEditMode

        // 更新保存的状态
        previousSelectedWordsState = state.selectedWords
        previousEditMode = state.isEditMode

        // 如果编辑模式变化，需要通知所有项更新（因为编辑模式影响所有项的显示）
        if (editModeChanged) {
            notifyDataSetChanged()
            return
        }

        // 只通知选中状态变化的项，避免全部刷新
        val changedPositions = mutableListOf<Int>()
        stats.forEachIndexed { index, stat ->
            val wasSelected = previousSelectedWords.contains(stat.word)
            val isSelected = state.selectedWords.contains(stat.word)
            if (wasSelected != isSelected) {
                changedPositions.add(index)
            }
        }

        // 如果有变化，通知变化的项；否则通知全部更新（其他状态变化）
        if (changedPositions.isNotEmpty()) {
            changedPositions.forEach { position ->
                notifyItemChanged(position)
            }
        } else {
            notifyDataSetChanged()
        }
    }

    fun getAllWords(): List<String> {
        return stats.map { it.word }
    }
}
