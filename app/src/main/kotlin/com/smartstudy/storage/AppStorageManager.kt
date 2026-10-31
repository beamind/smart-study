package com.smartstudy.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.smartstudy.R
import com.smartstudy.core.Logger
import com.smartstudy.utils.ToastHelper

/**
 * 统一的应用存储管理类
 * 管理 Documents/smartstudy/ 文件夹的 SAF 权限
 *
 * 功能特性：
 * - 统一管理应用存储文件夹的 SAF 权限
 * - 自动创建 smartstudy 子文件夹
 * - 支持权限状态缓存
 * - 处理权限被永久拒绝的情况
 *
 * 注意：所有方法都是静态方法，可以直接通过类名调用
 */
object AppStorageManager {
    private const val TAG = "AppStorageManager"
    private const val BASE_PATH = "Documents/smartstudy/"
    private const val PREFS_NAME = "app_storage"
    private const val PREF_KEY_TREE_URI = "app_storage_tree_uri"
    private const val SMARTSTUDY_FOLDER_NAME = "smartstudy"

    /**
     * 获取应用存储文件夹的 treeUri（如果已授权）
     */
    fun getAppStorageTreeUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(PREF_KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    /**
     * 保存应用存储文件夹的 treeUri
     */
    fun saveAppStorageTreeUri(
        context: Context,
        treeUri: Uri,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_TREE_URI, treeUri.toString())
            .apply()
        Logger.d(TAG, "已保存应用存储文件夹 treeUri: $treeUri")
    }

    /**
     * 清除应用存储文件夹的 treeUri
     */
    fun clearAppStorageTreeUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_KEY_TREE_URI)
            .apply()
        Logger.d(TAG, "已清除应用存储文件夹 treeUri")
    }

    /**
     * 获取子路径
     * @param subFolder 子文件夹名称（如 "DictationQuestions"、"UserManager"）
     * @return 完整路径，如 "Documents/smartstudy/DictationQuestions/"
     */
    fun getSubPath(subFolder: String): String {
        return "$BASE_PATH$subFolder/"
    }

    /**
     * 检查是否已授权应用存储文件夹
     */
    fun hasAppStoragePermission(context: Context): Boolean {
        return getAppStorageTreeUri(context) != null
    }

    /**
     * 尝试通过 MediaStore 预先创建 smartstudy 文件夹
     * 这样用户在打开文件夹选择器时就能看到该文件夹
     * @return true 如果创建成功或已存在，false 如果创建失败
     */
    fun tryCreateSmartstudyFolderViaMediaStore(context: Context): Boolean {
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")

            // 检查文件夹是否已存在
            val projection =
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                )
            val selection =
                "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?"
            val selectionArgs = arrayOf(SMARTSTUDY_FOLDER_NAME, "Documents/")

            val cursor = resolver.query(collection, projection, selection, selectionArgs, null)
            val exists = cursor?.use { it.moveToFirst() }
            cursor?.close()

            if (exists == true) {
                Logger.d(TAG, "smartstudy 文件夹已存在（通过 MediaStore 检查）")
                return true
            }

            // 尝试创建文件夹（通过创建一个临时文件，然后删除它来触发文件夹创建）
            // 注意：在 Android 10+ 上，直接创建文件夹可能不可行
            // 这里尝试创建一个临时文件，如果成功则说明文件夹路径可用
            val tempFileName = ".smartstudy_temp_${System.currentTimeMillis()}"
            val values =
                ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, tempFileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, BASE_PATH)
                }

            val uri = resolver.insert(collection, values)
            if (uri != null) {
                // 删除临时文件
                resolver.delete(uri, null, null)
                Logger.d(TAG, "已通过 MediaStore 创建 smartstudy 文件夹路径")
                true
            } else {
                Logger.w(TAG, "无法通过 MediaStore 创建 smartstudy 文件夹（可能设备不支持，如华为设备）")
                false
            }
        } catch (e: SecurityException) {
            Logger.w(TAG, "权限不足，无法通过 MediaStore 创建 smartstudy 文件夹", e)
            false
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "状态错误，无法通过 MediaStore 创建 smartstudy 文件夹", e)
            false
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "参数错误，无法通过 MediaStore 创建 smartstudy 文件夹", e)
            false
        }
    }

    /**
     * 确保 smartstudy 文件夹存在，并返回其 treeUri
     * 如果用户选择的文件夹是 smartstudy，直接返回其 treeUri
     * 如果用户选择的是父文件夹（如 Documents），自动创建 smartstudy 子文件夹并返回其 treeUri
     *
     * @param context Context
     * @param selectedTreeUri 用户选择的文件夹的 treeUri
     * @return smartstudy 文件夹的 treeUri，如果创建失败返回 null
     */
    fun ensureSmartstudyFolder(
        context: Context,
        selectedTreeUri: Uri,
    ): Uri? {
        val selectedFolder =
            DocumentFile.fromTreeUri(context, selectedTreeUri)
                ?: return null
        if (!selectedFolder.isDirectory) return null

        // 检查选择的文件夹名称
        val folderName = selectedFolder.name
        if (folderName == SMARTSTUDY_FOLDER_NAME) {
            // 用户直接选择了 smartstudy 文件夹
            Logger.d(TAG, "用户选择了 smartstudy 文件夹")
            return selectedTreeUri
        }

        // 用户选择了父文件夹（如 Documents），需要创建 smartstudy 子文件夹
        Logger.d(TAG, "用户选择了父文件夹: $folderName，尝试自动创建 smartstudy 子文件夹")
        var smartstudyFolder = selectedFolder.findFile(SMARTSTUDY_FOLDER_NAME)
        if (smartstudyFolder == null || !smartstudyFolder.isDirectory) {
            // 尝试创建 smartstudy 文件夹
            try {
                smartstudyFolder = selectedFolder.createDirectory(SMARTSTUDY_FOLDER_NAME)
                if (smartstudyFolder == null) {
                    Logger.e(TAG, "无法创建 smartstudy 子文件夹（可能设备不支持自动创建，如华为设备）")
                    // 返回 null，让调用者知道需要用户手动创建
                    return null
                }
                Logger.d(TAG, "已创建 smartstudy 子文件夹")
            } catch (e: SecurityException) {
                Logger.e(TAG, "权限不足，无法创建 smartstudy 子文件夹", e)
                return null
            } catch (e: IllegalStateException) {
                Logger.e(TAG, "状态错误，无法创建 smartstudy 子文件夹", e)
                return null
            } catch (e: IllegalArgumentException) {
                Logger.e(TAG, "参数错误，无法创建 smartstudy 子文件夹", e)
                return null
            }
        } else {
            Logger.d(TAG, "smartstudy 子文件夹已存在")
        }

        // 获取 smartstudy 文件夹的 treeUri
        // DocumentFile.uri 返回的是 document URI，需要转换为 tree URI
        val smartstudyUri = smartstudyFolder.uri
        val smartstudyDocumentId = DocumentsContract.getDocumentId(smartstudyUri)
        val authority = selectedTreeUri.authority
        val treeUri =
            DocumentsContract.buildTreeDocumentUri(
                authority,
                smartstudyDocumentId,
            )

        Logger.d(TAG, "smartstudy 文件夹 treeUri: $treeUri")
        return treeUri
    }

    /**
     * 创建统一的存储权限请求对话框
     * @param activity Activity实例
     * @param message 提示消息（可选，使用默认消息如果为null）
     * @param onPermissionGranted 权限授予后的回调（可选）
     */
    fun showStoragePermissionDialog(
        activity: AppCompatActivity,
        launcher: ActivityResultLauncher<Uri?>,
        message: String? = null,
    ) {
        // 在弹出对话框之前，尝试通过 MediaStore 创建 smartstudy 文件夹
        tryCreateSmartstudyFolderViaMediaStore(activity)

        val dialogMessage = message ?: activity.getString(R.string.storage_permission_required)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.storage_permission_title))
            .setMessage(dialogMessage)
            .setPositiveButton(activity.getString(R.string.storage_permission_select_folder)) { _, _ ->
                // 注意：ActivityResultLauncher 必须在 Activity/Fragment 的初始化阶段注册
                // 这里仅负责触发选择器，不在工具类内注册 launcher（避免生命周期时机错误）
                launcher.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 处理存储权限请求结果
     * @param activity Activity实例
     * @param uri 用户选择的文件夹URI
     * @param onPermissionGranted 权限授予后的回调（可选）
     * @param pendingIntent 待处理的Intent（可选，权限授予后执行）
     * @param onNeedReselect 无法自动创建 smartstudy 文件夹时的"重新选择"动作（由 Activity 触发 launcher）
     */
    fun handleStoragePermissionResult(
        activity: AppCompatActivity,
        uri: Uri?,
        onPermissionGranted: (() -> Unit)? = null,
        pendingIntent: Intent? = null,
        onNeedReselect: (() -> Unit)? = null,
    ) {
        if (uri == null) return

        runCatching {
            // 先获取持久权限
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )

            // 自动创建 smartstudy 文件夹（如果用户选择的是父文件夹）
            val smartstudyTreeUri = ensureSmartstudyFolder(activity, uri)
            if (smartstudyTreeUri != null) {
                // 如果创建了 smartstudy 子文件夹，需要获取其持久权限
                try {
                    activity.contentResolver.takePersistableUriPermission(
                        smartstudyTreeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    Logger.w(TAG, "权限不足，无法获取 smartstudy 文件夹权限，使用父文件夹权限", e)
                } catch (e: IllegalArgumentException) {
                    Logger.w(TAG, "参数错误，无法获取 smartstudy 文件夹权限，使用父文件夹权限", e)
                }
                saveAppStorageTreeUri(activity, smartstudyTreeUri)
                ToastHelper.showShort(activity, R.string.storage_folder_auto_created)
            } else {
                // 检查用户选择的是否已经是 smartstudy 文件夹
                val selectedFolder = DocumentFile.fromTreeUri(activity, uri)
                val folderName = selectedFolder?.name
                if (folderName == SMARTSTUDY_FOLDER_NAME) {
                    // 用户直接选择了 smartstudy 文件夹
                    saveAppStorageTreeUri(activity, uri)
                    ToastHelper.showShort(activity, R.string.storage_folder_authorized)
                } else {
                    // 无法自动创建 smartstudy 文件夹（华为设备常见问题）
                    // 提示用户手动创建并选择 smartstudy 文件夹
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.storage_folder_manual_select_title)
                        .setMessage(R.string.storage_folder_manual_select_message)
                        .setPositiveButton(R.string.storage_permission_reselect) { _, _ ->
                            // 清除当前保存的 treeUri，让用户重新选择
                            clearAppStorageTreeUri(activity)
                            // 重新请求权限（由 Activity 已注册的 launcher 触发）
                            onNeedReselect?.invoke()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return@runCatching
                }
            }

            // 如果之前有待处理的Intent，现在执行它
            pendingIntent?.let { intent ->
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                }
            }

            // 执行权限授予后的回调
            onPermissionGranted?.invoke()
        }.onFailure {
            Logger.e(TAG, "保存应用存储文件夹权限失败", it)
            ToastHelper.showLong(activity, R.string.storage_permission_save_failed)
        }
    }

    /**
     * 检查存储权限，如果没有权限则显示对话框
     * @param activity Activity实例
     * @param message 提示消息（可选）
     * @param onPermissionGranted 权限授予后的回调（可选）
     * @return true 如果已有权限，false 如果显示权限请求对话框
     */
    fun checkAndRequestStoragePermission(
        activity: AppCompatActivity,
        launcher: ActivityResultLauncher<Uri?>,
        message: String? = null,
    ): Boolean {
        if (hasAppStoragePermission(activity)) {
            return true
        }

        showStoragePermissionDialog(activity, launcher, message)
        return false
    }
}
