package com.smartstudy.user.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.smartstudy.core.Logger
import com.smartstudy.storage.AppStorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 用户数据存储类
 *
 * 负责用户数据的存储和读取，将存储逻辑从业务逻辑中分离出来。
 *
 * 功能特性：
 * - 使用 SAF (Storage Access Framework) 存储方式
 * - 文件读写操作
 *
 * 使用示例：
 * ```
 * val storage = UserStorage(context)
 * val data = storage.readUserData()
 * storage.saveUserData(data)
 * ```
 *
 * @param context Android Context，用于访问存储
 */
class UserStorage(private val context: Context) {
    private val resolver = context.contentResolver
    private val fileName = "users.json"
    private val subFolderName = "UserManager"

    /**
     * 用户数据类
     */
    data class UserData(
        val users: List<com.smartstudy.user.User>,
        val currentUserId: String?,
    )

    /**
     * 读取用户数据文件
     */
    fun readUserData(): UserData {
        val treeUri = getAppStorageTreeUriOrNull()
        if (treeUri == null) {
            Logger.w(TAG, "SAF 权限未授权，无法读取用户数据")
            return UserData(emptyList(), null)
        }

        return runCatching {
            readUserDataViaSaf(treeUri)
        }.getOrElse { exception ->
            Logger.e(TAG, "SAF 读取用户数据失败", exception)
            UserData(emptyList(), null)
        }
    }

    /**
     * 保存用户数据到文件
     */
    fun saveUserData(data: UserData) {
        val jsonObj =
            JSONObject().apply {
                val usersArray = JSONArray()
                data.users.forEach { user ->
                    val userObj =
                        JSONObject().apply {
                            put("id", user.id)
                            put("name", user.name)
                        }
                    usersArray.put(userObj)
                }
                put("users", usersArray)
                if (data.currentUserId != null) {
                    put("currentUserId", data.currentUserId)
                }
            }

        val jsonString = jsonObj.toString()

        val treeUri = getAppStorageTreeUriOrNull()
        if (treeUri == null) {
            throw IOException("SAF 权限未授权，无法保存用户数据。请先授权存储权限。")
        }

        runCatching {
            saveUserDataViaSaf(treeUri, jsonString)
            Logger.d(TAG, "通过 SAF 保存用户数据: ${data.users.size} 个用户")
        }.onFailure { exception ->
            Logger.e(TAG, "SAF 保存用户数据失败", exception)
            throw IOException("保存用户数据失败: ${exception.message}", exception)
        }
    }

    /**
     * 获取应用存储文件夹的 treeUri（如果已授权）
     */
    private fun getAppStorageTreeUriOrNull(): Uri? {
        return AppStorageManager.getAppStorageTreeUri(context)
    }

    /**
     * 通过 SAF 读取用户数据
     */
    private fun readUserDataViaSaf(treeUri: Uri): UserData {
        val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return UserData(emptyList(), null)
        val subFolder = rootFolder.findFile(subFolderName) ?: return UserData(emptyList(), null)
        if (!subFolder.isDirectory) return UserData(emptyList(), null)

        val file = subFolder.findFile(fileName) ?: return UserData(emptyList(), null)

        return try {
            val jsonString =
                resolver.openInputStream(file.uri)?.use { input ->
                    BufferedReader(InputStreamReader(input)).use { reader ->
                        reader.readText()
                    }
                } ?: return UserData(emptyList(), null)

            val jsonObj = JSONObject(jsonString)
            val usersArray = jsonObj.optJSONArray("users") ?: JSONArray()
            val users = mutableListOf<com.smartstudy.user.User>()
            for (i in 0 until usersArray.length()) {
                val userObj = usersArray.getJSONObject(i)
                users.add(
                    com.smartstudy.user.User(
                        id = userObj.getString("id"),
                        name = userObj.getString("name"),
                    ),
                )
            }
            val currentUserId = jsonObj.optString("currentUserId", null).takeIf { it.isNotEmpty() }
            UserData(users, currentUserId)
        } catch (e: org.json.JSONException) {
            Logger.e(TAG, "JSON解析错误，通过 SAF 读取用户数据失败", e)
            UserData(emptyList(), null)
        } catch (e: IOException) {
            Logger.e(TAG, "IO错误，通过 SAF 读取用户数据失败", e)
            UserData(emptyList(), null)
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，通过 SAF 读取用户数据失败", e)
            UserData(emptyList(), null)
        }
    }

    /**
     * 通过 SAF 保存用户数据
     */
    private fun saveUserDataViaSaf(
        treeUri: Uri,
        jsonString: String,
    ) {
        val rootFolder =
            DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("无法打开 SAF 根文件夹（treeUri=$treeUri）")
        if (!rootFolder.isDirectory) throw IOException("SAF 目标不是文件夹（treeUri=$treeUri）")

        // 验证根文件夹是否为 smartstudy（可选，用于诊断）
        val rootFolderName = rootFolder.name
        if (rootFolderName != "smartstudy") {
            Logger.w(TAG, "警告：SAF 根文件夹名称不是 'smartstudy'，而是 '$rootFolderName'。这可能导致问题。")
        }

        // 获取或创建 UserManager 子文件夹
        var subFolder = rootFolder.findFile(subFolderName)
        if (subFolder == null || !subFolder.isDirectory) {
            subFolder = rootFolder.createDirectory(subFolderName)
                ?: throw IOException("无法创建 UserManager 子文件夹。请确保已正确授权 smartstudy 文件夹权限，并且 smartstudy 文件夹已存在。")
        }

        // 获取或创建文件
        val existing = subFolder.findFile(fileName)
        val target =
            existing ?: subFolder.createFile("application/json", fileName)
                ?: throw IOException("无法创建用户数据文件（fileName=$fileName）。请确保已正确授权 smartstudy 文件夹权限。")

        resolver.openOutputStream(target.uri, "wt")?.use { output ->
            OutputStreamWriter(output).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
        } ?: throw IOException("无法打开 SAF 输出流（uri=${target.uri}）。请确保已正确授权 smartstudy 文件夹权限。")
    }

    companion object {
        private const val TAG = "UserStorage"
    }
}
