package com.smartstudy.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.R
import com.smartstudy.core.ResourceManager
import com.smartstudy.core.ResourceManagerHelper
import com.smartstudy.databinding.ActivityUserManagementBinding
import com.smartstudy.user.managers.UserManagementManager
import com.smartstudy.utils.ToastHelper
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

/**
 * 用户管理Activity
 * 提供用户列表、切换用户、添加用户等功能
 */
class UserManagementActivity : AppCompatActivity(), ResourceManager {
    companion object {
        private const val TAG = "UserManagementActivity"
    }

    private lateinit var binding: ActivityUserManagementBinding
    private val viewModel: UserManagementViewModel by viewModel { parametersOf(this) }

    private var userAdapter: UserManagementAdapter? = null

    // 用户管理管理器
    private lateinit var manager: UserManagementManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化管理器
        initManager()

        initViews()
        observeViewModel()
    }

    /**
     * 初始化用户管理管理器
     */
    private fun initManager() {
        manager =
            UserManagementManager(
                activity = this,
                binding = binding,
                viewModel = viewModel,
                onUserAdded = { newUser ->
                    ToastHelper.showShort(this, R.string.user_added, newUser.name)
                },
                onUserSwitched = { userName ->
                    ToastHelper.showShort(this, R.string.user_switched, userName)
                },
            )
    }

    private fun initViews() {
        binding.rvUsers.layoutManager = LinearLayoutManager(this)

        // 设置 Toolbar
        setSupportActionBar(binding.toolbar.toolbar)
        // 隐藏默认标题，使用自定义布局中的标题
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // 设置自定义标题
        binding.toolbar.tvToolbarTitle.text = getString(R.string.user_management)

        // 注册按钮（右上角）
        binding.btnAddUser.setOnClickListener {
            manager.showAddUserDialog()
        }

        // 确定按钮（底部）
        binding.btnConfirm.setOnClickListener {
            val state = viewModel.uiState.value
            val selectedUserId = state.selectedUserId

            selectedUserId?.let { userId ->
                manager.switchUser(userId)
            }
            finish() // 返回上一页
        }
    }

    /**
     * 观察ViewModel状态变化
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 更新当前用户名显示
                    val currentUser = state.users.find { it.id == state.currentUserId }
                    binding.toolbar.tvCurrentUser.text = currentUser?.name ?: getString(R.string.guest)

                    // 更新空状态显示（委托给Manager）
                    manager.showEmptyState(state.isEmpty)

                    // 更新用户列表（委托给Manager）
                    if (state.users.isNotEmpty()) {
                        userAdapter =
                            manager.createAndSetUserAdapter(
                                recyclerView = binding.rvUsers,
                                users = state.users,
                                currentUserId = state.currentUserId,
                                selectedUserId = state.selectedUserId,
                                onUserSelect = { userId ->
                                    // 只选择，不切换
                                    viewModel.setSelectedUser(userId)
                                },
                            )
                    } else {
                        // 列表为空时，更新现有适配器的选中状态（如果适配器存在）
                        userAdapter?.let { adapter ->
                            manager.updateAdapterSelection(adapter, state.selectedUserId)
                        }
                    }

                    // 显示错误信息
                    state.error?.let { error ->
                        manager.handleError(error)
                        viewModel.clearError()
                    }
                }
            }
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
        // 清理适配器
        ResourceManagerHelper.safeRelease {
            userAdapter = null
        }
    }
}

/**
 * 用户管理列表适配器
 */
class UserManagementAdapter(
    private val users: List<User>,
    private val currentUserId: String?,
    private var selectedUserId: String?,
    private val onUserSelect: (String) -> Unit,
) : RecyclerView.Adapter<UserManagementAdapter.UserViewHolder>() {
    companion object {
        // 用户列表项背景颜色常量
        private const val COLOR_SELECTED_BACKGROUND = 0x3300AAFF.toInt() // 选中状态：蓝色半透明
        private const val COLOR_CURRENT_USER_BACKGROUND = 0x33000000.toInt() // 当前用户：灰色半透明
        private const val COLOR_NORMAL_BACKGROUND = 0x00000000.toInt() // 普通状态：透明
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvUserName: TextView = itemView.findViewById(R.id.tvUserName)
        val tvCurrentLabel: TextView = itemView.findViewById(R.id.tvCurrentLabel)
    }

    fun updateSelectedUser(userId: String?) {
        selectedUserId = userId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): UserViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_user_management, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: UserViewHolder,
        position: Int,
    ) {
        val user = users[position]
        val isCurrentUser = user.id == currentUserId
        val isSelected = user.id == selectedUserId

        holder.tvUserName.text = user.name
        holder.tvCurrentLabel.visibility = if (isCurrentUser) View.VISIBLE else View.GONE

        // 显示选中状态和当前用户状态
        when {
            isSelected -> {
                holder.itemView.setBackgroundColor(COLOR_SELECTED_BACKGROUND)
            }
            isCurrentUser -> {
                holder.itemView.setBackgroundColor(COLOR_CURRENT_USER_BACKGROUND)
            }
            else -> {
                holder.itemView.setBackgroundColor(COLOR_NORMAL_BACKGROUND)
            }
        }

        holder.itemView.setOnClickListener {
            onUserSelect(user.id)
        }
    }

    override fun getItemCount(): Int = users.size
}
