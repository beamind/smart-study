package com.smartstudy.dictation

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.AppConfig
import com.smartstudy.core.Logger
import com.smartstudy.databinding.ActivityDictationRecordBinding
import com.smartstudy.databinding.DialogDateRangePickerBinding
import com.smartstudy.dictation.managers.DictationRecordManager
import com.smartstudy.utils.ToastHelper
import com.smartstudy.utils.showToast
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 听写记录Activity
 */
class DictationRecordActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DictationRecordActivity"
        const val EXTRA_TYPE = "type" // "chinese" 或 "english"
        const val EXTRA_USER_ID = "user_id" // 用户ID
    }

    private lateinit var binding: ActivityDictationRecordBinding
    private val viewModel: DictationRecordViewModel by viewModel { parametersOf(this) }

    private var adapter: DictationRecordAdapter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private lateinit var manager: DictationRecordManager
    private var dateRangeStart: Long? = null
    private var dateRangeEnd: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityDictationRecordBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 设置Toolbar
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)

            // 从Intent获取类型和用户ID
            val type = intent.getStringExtra(EXTRA_TYPE) ?: AppConfig.TYPE_CHINESE
            val userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""

            // 初始化ViewModel
            viewModel.initialize(type, userId)

            // 初始化管理器
            manager = DictationRecordManager(this, viewModel)

            // 初始化视图
            initViews()

            // 观察ViewModel
            observeViewModel()
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "DictationRecordActivity 初始化失败：状态错误", e)
            ToastHelper.showShort(this, "初始化失败：${e.message}")
            finish()
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "DictationRecordActivity 初始化失败：参数错误", e)
            ToastHelper.showShort(this, "初始化失败：${e.message}")
            finish()
        } catch (e: SecurityException) {
            Logger.e(TAG, "DictationRecordActivity 初始化失败：权限错误", e)
            ToastHelper.showShort(this, "初始化失败：权限不足")
            finish()
        } catch (e: Exception) {
            Logger.e(TAG, "DictationRecordActivity 初始化失败：未知错误", e)
            ToastHelper.showShort(this, "初始化失败：${e.message}")
            // 延迟关闭，让用户看到错误提示
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }, 2000)
        }
    }

    private fun initViews() {
        // RecyclerView
        binding.rvRecords.layoutManager = LinearLayoutManager(this)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 更新标题
                    val title =
                        if (state.currentType == AppConfig.TYPE_CHINESE) {
                            "语文听写记录"
                        } else {
                            "英语听写记录"
                        }
                    supportActionBar?.title = title

                    // 更新日期范围文本
                    val dateRangeText = "${dateFormat.format(state.startDate)} 至 ${dateFormat.format(state.endDate)}"
                    binding.tvDateRange.text = dateRangeText

                    // 更新记录数量文本
                    val recordCountText = "共 ${state.records.size} 条"
                    binding.tvRecordCount.text = recordCountText

                    // 更新列表
                    if (state.records.isEmpty() && !state.isLoading) {
                        binding.tvEmpty.visibility = TextView.VISIBLE
                        binding.rvRecords.visibility = RecyclerView.GONE
                    } else {
                        binding.tvEmpty.visibility = TextView.GONE
                        binding.rvRecords.visibility = RecyclerView.VISIBLE
                        updateAdapter(state.records, state.isEditMode, state.selectedWords, state.editedWords)
                    }

                    // 显示错误
                    state.error?.let { error ->
                        ToastHelper.showShort(this@DictationRecordActivity, error)
                        viewModel.clearError()
                    }

                    // 编辑模式变化时刷新菜单
                    if (adapter != null) {
                        invalidateOptionsMenu()
                    }
                }
            }
        }
    }

    private fun updateAdapter(
        records: List<com.smartstudy.dictation.core.DictationWordStat>,
        isEditMode: Boolean,
        selectedWords: Set<String>,
        editedWords: Map<String, String>,
    ) {
        if (adapter == null) {
            adapter =
                DictationRecordAdapter(
                    stats = records,
                    isEditMode = isEditMode,
                    selectedWords = selectedWords,
                    editedWords = editedWords,
                    onItemLongClick = {}, // 已废弃，不再使用长按进入编辑模式
                    onWordSelected = { word, isSelected ->
                        viewModel.toggleWordSelection(word, isSelected)
                    },
                    onWordUpdated = { oldWord, newWord ->
                        // 更新 ViewModel State 中的编辑内容
                        viewModel.updateEditedWord(oldWord, newWord)
                    },
                )
            binding.rvRecords.adapter = adapter
        } else {
            adapter =
                DictationRecordAdapter(
                    stats = records,
                    isEditMode = isEditMode,
                    selectedWords = selectedWords,
                    editedWords = editedWords,
                    onItemLongClick = {}, // 已废弃，不再使用长按进入编辑模式
                    onWordSelected = { word, isSelected ->
                        viewModel.toggleWordSelection(word, isSelected)
                    },
                    onWordUpdated = { oldWord, newWord ->
                        // 更新 ViewModel State 中的编辑内容
                        viewModel.updateEditedWord(oldWord, newWord)
                    },
                )
            binding.rvRecords.adapter = adapter
        }
    }

    private fun showDateRangePickerDialog() {
        val state = viewModel.uiState.value
        val dialogBinding = DialogDateRangePickerBinding.inflate(layoutInflater)

        // 初始化日期范围
        dateRangeStart = state.startDate
        dateRangeEnd = state.endDate

        // 更新显示
        updateDateRangeDisplay(dialogBinding.tvStartDate, dialogBinding.tvEndDate)

        // 设置日历初始日期
        dialogBinding.calendarView.date = state.startDate

        // 设置日历点击监听
        dialogBinding.calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val selectedDate =
                Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

            when {
                dateRangeStart == null -> {
                    // 第一次点击：选择开始日期
                    dateRangeStart = selectedDate
                    dateRangeEnd = null
                }
                dateRangeEnd == null -> {
                    // 第二次点击：选择结束日期
                    if (selectedDate >= dateRangeStart!!) {
                        dateRangeEnd = selectedDate
                    } else {
                        // 如果选择的日期早于开始日期，则重新选择开始日期
                        dateRangeEnd = dateRangeStart
                        dateRangeStart = selectedDate
                    }
                }
                else -> {
                    // 重新选择开始日期
                    dateRangeStart = selectedDate
                    dateRangeEnd = null
                }
            }
            updateDateRangeDisplay(dialogBinding.tvStartDate, dialogBinding.tvEndDate)
        }

        // 创建对话框
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.date_range))
                .setView(dialogBinding.root)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                    dateRangeStart?.let { start ->
                        val end = dateRangeEnd ?: start
                        viewModel.setStartDate(start)
                        viewModel.setEndDate(
                            Calendar.getInstance().apply {
                                timeInMillis = end
                                set(Calendar.HOUR_OF_DAY, 23)
                                set(Calendar.MINUTE, 59)
                                set(Calendar.SECOND, 59)
                                set(Calendar.MILLISECOND, 999)
                            }.timeInMillis,
                        )
                        // 自动执行筛选
                        viewModel.loadRecords()
                    } ?: run {
                        // 如果没有选择开始日期，使用当前日期范围
                        ToastHelper.showShort(this, "请选择日期范围")
                    }
                }
                .create()

        dialog.show()
    }

    private fun updateDateRangeDisplay(
        tvStartDate: TextView,
        tvEndDate: TextView,
    ) {
        tvStartDate.text =
            if (dateRangeStart != null) {
                dateFormat.format(Date(dateRangeStart!!))
            } else {
                getString(R.string.not_selected)
            }

        tvEndDate.text =
            if (dateRangeEnd != null) {
                dateFormat.format(Date(dateRangeEnd!!))
            } else {
                getString(R.string.not_selected)
            }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dictation_record, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val state = viewModel.uiState.value
        val editItem = menu.findItem(R.id.menu_edit)
        if (editItem != null) {
            if (state.isEditMode) {
                editItem.setTitle("完成")
            } else {
                editItem.setTitle("编辑")
            }
        }

        // 根据编辑模式显示/隐藏编辑相关菜单项
        menu.findItem(R.id.menu_select_all)?.isVisible = state.isEditMode
        menu.findItem(R.id.menu_deselect_all)?.isVisible = state.isEditMode
        menu.findItem(R.id.menu_delete)?.isVisible = state.isEditMode
        menu.findItem(R.id.menu_save)?.isVisible = state.isEditMode

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                val state = viewModel.uiState.value
                if (state.isEditMode) {
                    viewModel.exitEditMode()
                    invalidateOptionsMenu()
                } else {
                    finish()
                }
                true
            }
            R.id.menu_change_date -> {
                showDateRangePickerDialog()
                true
            }
            R.id.menu_edit -> {
                val state = viewModel.uiState.value
                if (state.isEditMode) {
                    viewModel.exitEditMode()
                } else {
                    viewModel.enterEditMode()
                    binding.root.post {
                        showToast(com.smartstudy.R.string.error_book_enter_edit_mode)
                    }
                }
                invalidateOptionsMenu()
                true
            }
            R.id.menu_select_all -> {
                viewModel.selectAll()
                invalidateOptionsMenu()
                true
            }
            R.id.menu_deselect_all -> {
                viewModel.deselectAll()
                invalidateOptionsMenu()
                true
            }
            R.id.menu_delete -> {
                manager.performDelete()
                true
            }
            R.id.menu_save -> {
                manager.saveEditedItems()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
