package com.smartstudy.managers

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.smartstudy.MainViewModel
import com.smartstudy.core.Logger
import com.smartstudy.utils.ToastHelper

/**
 * 主界面管理器
 *
 * 负责管理主界面的业务逻辑，将业务逻辑从Activity中分离出来。
 *
 * 功能特性：
 * - Activity启动管理
 * - 错误处理管理
 *
 * 注意：权限已在应用启动时统一请求，此处不再检查权限。
 *
 * 使用示例：
 * ```
 * val manager = MainActivityManager(
 *     activity = this
 * )
 * manager.launchActivityWithStorageCheck(ChineseDictationActivity::class.java, getString(R.string.activity_name_chinese_dictation))
 * ```
 *
 * @param activity Activity实例
 */
class MainActivityManager(
    private val activity: AppCompatActivity,
) {
    companion object {
        private const val TAG = "MainActivityManager"
    }

    /**
     * 统一处理Activity启动逻辑
     *
     * 注意：权限已在应用启动时统一请求，此处直接启动Activity。
     *
     * @param activityClass 要启动的Activity类
     * @param activityName Activity名称（用于错误提示）
     */
    fun launchActivityWithStorageCheck(
        activityClass: Class<*>,
        activityName: String,
    ) {
        // 权限已在应用启动时统一请求，直接启动Activity
        launchActivity(activityClass, activityName)
    }

    /**
     * 启动Activity（带错误处理）
     */
    private fun launchActivity(
        activityClass: Class<*>,
        activityName: String,
    ) {
        try {
            val intent = Intent(activity, activityClass)
            if (intent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(intent)
            } else {
                Logger.e(TAG, "无法启动 $activityName")
                ToastHelper.showShort(activity, com.smartstudy.R.string.cannot_start_activity, activityName)
            }
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，无法启动 $activityName", e)
            ToastHelper.showShort(activity, com.smartstudy.R.string.activity_start_failed, "权限不足")
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "Intent参数错误，无法启动 $activityName", e)
            ToastHelper.showShort(activity, com.smartstudy.R.string.activity_start_failed, "参数错误")
        } catch (e: android.content.ActivityNotFoundException) {
            Logger.e(TAG, "Activity未找到，无法启动 $activityName", e)
            ToastHelper.showShort(activity, com.smartstudy.R.string.activity_start_failed, "功能不可用")
        }
    }
}
