package com.smartstudy

import android.content.Intent
import androidx.lifecycle.ViewModel
import com.smartstudy.storage.AppStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * MainActivity的ViewModel
 * 管理主界面的状态和业务逻辑
 *
 * 职责：
 * - 管理存储权限状态
 * - 管理Activity启动逻辑
 * - 管理待处理的Intent
 *
 * @param context Activity Context（用于检查权限和启动Activity）
 */
class MainViewModel(
    private val context: android.content.Context,
) : ViewModel() {
    companion object {
        private const val TAG = "MainViewModel"
    }

    /**
     * UI状态
     */
    data class MainState(
        val hasStoragePermission: Boolean = false,
        val pendingLaunchIntent: Intent? = null,
    )

    private val _uiState = MutableStateFlow(MainState())
    val uiState: StateFlow<MainState> = _uiState.asStateFlow()

    init {
        // 初始化时检查存储权限
        checkStoragePermission()
    }

    /**
     * 检查存储权限
     */
    fun checkStoragePermission() {
        val hasPermission = AppStorageManager.hasAppStoragePermission(context)
        _uiState.update { it.copy(hasStoragePermission = hasPermission) }
    }

    /**
     * 设置待处理的Intent
     */
    fun setPendingLaunchIntent(intent: Intent?) {
        _uiState.update { it.copy(pendingLaunchIntent = intent) }
    }

    /**
     * 清除待处理的Intent
     */
    fun clearPendingLaunchIntent() {
        _uiState.update { it.copy(pendingLaunchIntent = null) }
    }

    /**
     * 处理存储权限授予结果
     */
    fun handleStoragePermissionGranted() {
        _uiState.update { it.copy(hasStoragePermission = true) }
    }
}
