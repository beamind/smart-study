package com.smartstudy.learningrecord

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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.databinding.ActivityLearningRecordBinding
import com.smartstudy.databinding.DialogDateRangePickerBinding
import com.smartstudy.learningrecord.managers.LearningRecordManager
import com.smartstudy.utils.ToastHelper
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 学习记录Activity
 */
class LearningRecordActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "LearningRecordActivity"
    }

    private lateinit var binding: ActivityLearningRecordBinding
    private val viewModel: LearningRecordViewModel by viewModel { parametersOf(this) }

    private var adapter: LearningRecordAdapter? = null
    private lateinit var manager: LearningRecordManager
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var dateRangeStart: Long? = null
    private var dateRangeEnd: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityLearningRecordBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 设置Toolbar
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "学习记录"

            // 初始化管理器
            manager = LearningRecordManager(this, viewModel)

            // 初始化视图
            initViews()

            // 观察ViewModel
            observeViewModel()
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "LearningRecordActivity 初始化失败：状态错误", e)
            ToastHelper.showShort(this, "初始化失败：${e.message}")
            finish()
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "LearningRecordActivity 初始化失败：参数错误", e)
            ToastHelper.showShort(this, "初始化失败：${e.message}")
            finish()
        } catch (e: SecurityException) {
            Logger.e(TAG, "LearningRecordActivity 初始化失败：权限错误", e)
            ToastHelper.showShort(this, "初始化失败：权限不足")
            finish()
        } catch (e: Exception) {
            Logger.e(TAG, "LearningRecordActivity 初始化失败：未知错误", e)
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
        
        // 设置滑动删除
        setupSwipeToDelete()
    }
    
    /**
     * 设置滑动删除功能
     */
    private fun setupSwipeToDelete() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    // 检查是否是分组标题，分组标题不支持滑动删除
                    if (viewHolder is LearningRecordAdapter.HeaderViewHolder) {
                        // 恢复 item 位置
                        adapter?.notifyItemChanged(position)
                        return
                    }
                    
                    // 获取记录并显示确认对话框
                    val record = adapter?.getRecordAtPosition(position)
                    if (record != null) {
                        showDeleteConfirmDialog(record)
                    } else {
                        // 如果获取失败，恢复 item 位置
                        adapter?.notifyItemChanged(position)
                    }
                }
            }
            
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                // 分组标题不支持滑动删除
                if (viewHolder is LearningRecordAdapter.HeaderViewHolder) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvRecords)
    }
    
    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog(record: LearningRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定要删除这条记录吗？\n\n${record.content}")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteRecord(record.getUniqueId())
            }
            .setNegativeButton("取消") { _, _ ->
                // 取消时恢复 item 位置
                adapter?.notifyDataSetChanged()
            }
            .setOnCancelListener {
                // 取消时恢复 item 位置
                adapter?.notifyDataSetChanged()
            }
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousState: LearningRecordState? = null

                viewModel.uiState.collect { state ->
                    // 更新日期范围文本
                    val dateRangeText = "${dateFormat.format(Date(state.startDate))} 至 ${dateFormat.format(Date(state.endDate))}"
                    binding.tvDateRange.text = dateRangeText

                    // 计算总记录数
                    val totalCount = state.recordGroups.sumOf { it.records.size }
                    val recordCountText = "共 $totalCount 条"
                    binding.tvRecordCount.text = recordCountText

                    // 更新列表
                    if (state.recordGroups.isEmpty() && !state.isLoading) {
                        binding.tvEmpty.visibility = TextView.VISIBLE
                        binding.rvRecords.visibility = RecyclerView.GONE
                    } else {
                        binding.tvEmpty.visibility = TextView.GONE
                        binding.rvRecords.visibility = RecyclerView.VISIBLE

                        // 如果 Adapter 已存在，只更新数据，不重新创建
                        if (adapter == null) {
                            updateAdapter(
                                state.recordGroups,
                                state.isEditMode,
                                state.editedRecords,
                            )
                        } else {
                            // 更新现有 Adapter 的数据
                            adapter?.updateData(
                                state.recordGroups,
                                state.isEditMode,
                                state.editedRecords,
                            )
                        }
                    }

                    // 显示错误
                    state.error?.let { error ->
                        ToastHelper.showShort(this@LearningRecordActivity, error)
                        viewModel.clearError()
                    }

                    // 编辑模式变化时刷新菜单
                    // 使用 post 延迟菜单更新，让列表先更新完成，避免卡顿和菜单闪烁
                    if (adapter != null) {
                        binding.root.post {
                            invalidateOptionsMenu()
                        }
                    }

                }
            }
        }
    }

    private fun updateAdapter(
        recordGroups: List<LearningRecordGroup>,
        isEditMode: Boolean,
        editedRecords: Map<String, LearningRecord>,
    ) {
        adapter =
            LearningRecordAdapter(
                recordGroups = recordGroups,
                isEditMode = isEditMode,
                editedRecords = editedRecords,
                onRecordUpdated = { recordId, updatedRecord ->
                    // 这个回调现在不会被调用，因为 TextWatcher 只更新本地 Map
                    // 保留它以防将来需要
                },
            )
        binding.rvRecords.adapter = adapter
        // 设置 RecyclerView 引用，用于优化更新性能
        adapter?.attachRecyclerView(binding.rvRecords)
        // 设置 adapter 到 Manager，用于保存时获取编辑后的内容
        manager.adapter = adapter
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

        // 在编辑模式下隐藏"编辑"菜单项，因为返回按钮已经可以退出编辑模式
        if (editItem != null) {
            if (state.isEditMode) {
                editItem.isVisible = false // 隐藏，避免与返回按钮重复
            } else {
                editItem.isVisible = true
                editItem.setTitle("编辑")
            }
        }

        // 根据编辑模式显示/隐藏编辑相关菜单项
        // 移除全选、取消全选、删除菜单项（已改用滑动删除）
        menu.findItem(R.id.menu_select_all)?.isVisible = false
        menu.findItem(R.id.menu_deselect_all)?.isVisible = false
        menu.findItem(R.id.menu_delete)?.isVisible = false

        menu.findItem(R.id.menu_save)?.isVisible = state.isEditMode
        // 更改日期在编辑模式下隐藏，因为编辑时通常不需要更改日期
        menu.findItem(R.id.menu_change_date)?.isVisible = !state.isEditMode

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
                }
                // 移除这里的 invalidateOptionsMenu()，因为 observeViewModel() 中已经有了
                // 这样可以避免双重调用，减少卡顿
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
