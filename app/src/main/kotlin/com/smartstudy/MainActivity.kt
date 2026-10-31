package com.smartstudy

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smartstudy.core.Logger
import com.smartstudy.core.ResourceManager
import com.smartstudy.databinding.ActivityMainBinding
import com.smartstudy.dictation.chinese.ChineseDictationActivity
import com.smartstudy.dictation.english.EnglishDictationActivity
import com.smartstudy.english.EnglishErrorBookActivity
import com.smartstudy.managers.MainActivityManager
import com.smartstudy.storage.AppStorageManager
import com.smartstudy.user.UserManagementActivity
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class MainActivity : AppCompatActivity(), ResourceManager {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var appStorageLauncher: ActivityResultLauncher<Uri?>
    private lateinit var permissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModel { parametersOf(this) }

    // 主界面管理器
    private lateinit var manager: MainActivityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // DEBUG 模式下启用 StrictMode，用于检测性能问题
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }

        // 使用 View Binding 加载布局并设置内容视图
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 注册相机和麦克风权限请求的回调
        permissionsLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { permissions ->
                handlePermissionsResult(permissions)
            }

        // 注册存储权限请求的回调
        appStorageLauncher =
            registerForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                // 只有用户取消授权时才清除 pendingLaunchIntent
                if (uri == null) {
                    viewModel.clearPendingLaunchIntent()
                    return@registerForActivityResult
                }

                AppStorageManager.handleStoragePermissionResult(
                    this,
                    uri,
                    onPermissionGranted = {
                        viewModel.handleStoragePermissionGranted()
                        // 权限授予后，如果有待处理的Intent，现在执行它
                        viewModel.uiState.value.pendingLaunchIntent?.let { intent ->
                            viewModel.clearPendingLaunchIntent()
                            if (intent.resolveActivity(packageManager) != null) {
                                startActivity(intent)
                                // 启动目标 Activity 后，关闭当前的 MainActivity
                                // 避免返回栈中留下多余的 MainActivity 实例
                                // 这样用户从目标 Activity 返回时可以直接退出应用
                                finish()
                            }
                        }
                    },
                    pendingIntent = null, // 不在这里启动，避免重复启动 Activity
                    onNeedReselect = {
                        // 需要重新选择，保留 pendingLaunchIntent，以便用户重新选择后自动启动
                        appStorageLauncher.launch(null)
                    },
                )
            }

        // 在应用启动时检查并请求所有权限
        checkAndRequestAllPermissions()

        // 初始化管理器
        manager =
            MainActivityManager(
                activity = this,
            )

        // 观察ViewModel状态
        observeViewModel()

        // 中文听写模块
        binding.dictationCard.setOnClickListener {
            manager.launchActivityWithStorageCheck(ChineseDictationActivity::class.java, getString(R.string.activity_name_chinese_dictation))
        }

        // 英语听写模块
        binding.englishDictationCard.setOnClickListener {
            manager.launchActivityWithStorageCheck(EnglishDictationActivity::class.java, getString(R.string.activity_name_english_dictation))
        }

        // 英语错题本
        binding.englishErrorBookCard.setOnClickListener {
            manager.launchActivityWithStorageCheck(
                EnglishErrorBookActivity::class.java,
                getString(R.string.activity_name_english_error_book),
            )
        }

        // 学习记录
        binding.learningRecordCard.setOnClickListener {
            manager.launchActivityWithStorageCheck(
                com.smartstudy.learningrecord.LearningRecordActivity::class.java,
                getString(R.string.learning_record),
            )
        }

        // 用户管理
        binding.userManagementCard.setOnClickListener {
            manager.launchActivityWithStorageCheck(
                UserManagementActivity::class.java,
                getString(R.string.activity_name_user_management),
            )
        }
    }

    /**
     * 检查并请求所有权限
     * 在应用启动时调用，一次性请求所有必需的权限
     */
    private fun checkAndRequestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // 检查麦克风权限
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // 请求相机和麦克风权限
        if (permissionsToRequest.isNotEmpty()) {
            Logger.d(TAG, "请求权限: ${permissionsToRequest.joinToString(", ")}")
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Logger.d(TAG, "相机和麦克风权限已授予")
        }

        // 检查存储权限
        if (!AppStorageManager.hasAppStoragePermission(this)) {
            Logger.d(TAG, "请求存储权限")
            AppStorageManager.showStoragePermissionDialog(
                this,
                appStorageLauncher,
                message = getString(R.string.storage_permission_required),
            )
        } else {
            Logger.d(TAG, "存储权限已授予")
            viewModel.handleStoragePermissionGranted()
        }
    }

    /**
     * 处理权限请求结果
     */
    private fun handlePermissionsResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            Logger.d(TAG, "所有权限已授予")
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys
            Logger.w(TAG, "权限被拒绝: ${deniedPermissions.joinToString(", ")}")
        }
    }

    /**
     * 观察ViewModel状态变化
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 可以在这里处理状态变化，例如更新UI
                    // 当前MainActivity的UI比较简单，暂时不需要额外的状态处理
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 确保视图在恢复时正确显示和测量
        // 从其他 Activity 返回时，可能需要重新请求布局以确保视图正确显示
        if (::binding.isInitialized) {
            // 确保所有卡片视图可见
            binding.dictationCard.visibility = android.view.View.VISIBLE
            binding.englishDictationCard.visibility = android.view.View.VISIBLE
            binding.englishErrorBookCard.visibility = android.view.View.VISIBLE
            binding.userManagementCard.visibility = android.view.View.VISIBLE

            // 使用 post() 确保在主线程的下一个消息循环中执行布局请求
            // 这样可以确保布局已经完成初始化
            binding.root.post {
                binding.root.requestLayout()
                binding.root.invalidate()
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
     *
     * 此Activity的资源：
     * - View Binding：会在Activity销毁时自动清理
     * - ActivityResultLauncher：会在Activity销毁时自动清理
     * - pendingLaunchIntent：会在Activity销毁时自动清理
     *
     * 无需手动清理任何资源
     */
    override fun releaseResources() {
        // 此Activity没有需要手动清理的资源
        // View Binding、ActivityResultLauncher等会在Activity销毁时自动清理
    }
}
