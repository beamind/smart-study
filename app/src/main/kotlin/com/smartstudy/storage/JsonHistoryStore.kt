package com.smartstudy.storage

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.smartstudy.core.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter

/**
 * 通用的"JSON 历史文件存储"工具：
 * - 使用 SAF (Storage Access Framework) 存储到用户授权的 Documents/smartstudy 根目录
 * - 维护"损坏文件"标记，读取最新文件失败时自动回退到上一个可用文件
 * - 保留最近 N 个文件，自动清理旧文件
 * - 使用流式读写，优化大文件性能
 *
 * 设计目标：降低各业务模块（听写/错题本/用户等）中重复的存储样板代码。
 *
 * @param context Android Context
 * @param subFolderName 子文件夹名称（如 "DictationQuestions"、"UserManager"）
 * @param prefsName SharedPreferences 名称，用于存储文件状态
 * @param keepLatest 保留的最新文件数量，默认 10 个
 */
class JsonHistoryStore(
    private val context: Context,
    private val subFolderName: String,
    prefsName: String,
    private val keepLatest: Int = 10,
) {
    companion object {
        private const val TAG = "JsonHistoryStore"
        private const val PREF_KEY_CORRUPT_PREFIX = "corrupt_"
    }

    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun corruptPrefKey(uri: Uri): String = "${PREF_KEY_CORRUPT_PREFIX}${uri.hashCode()}"

    private fun markCorrupt(uri: Uri) {
        prefs.edit().putBoolean(corruptPrefKey(uri), true).apply()
    }

    private fun isMarkedCorrupt(uri: Uri): Boolean = prefs.getBoolean(corruptPrefKey(uri), false)

    private fun getRootTreeUriOrNull(): Uri? = AppStorageManager.getAppStorageTreeUri(context)

    /**
     * 列出匹配 pattern 的文件（按时间降序：最新在前）。
     *
     * @param timestampFromFileName 尝试从文件名解析时间戳（毫秒）。解析失败时将回退到文件的 lastModified/DATE_MODIFIED。
     */
    suspend fun listFilesByPattern(
        pattern: Regex,
        timestampFromFileName: (String) -> Long?,
    ): List<Pair<Uri, Long>> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<Pair<Uri, Long>>()

            val treeUri = getRootTreeUriOrNull()
            if (treeUri == null) {
                Logger.w(TAG, "SAF 权限未授权，无法列出文件")
                return@withContext result.sortedByDescending { it.second }
            }

            processFilesInSubFolder(treeUri, pattern, timestampFromFileName, result)

            return@withContext result.sortedByDescending { it.second }
        }

    /**
     * 处理子文件夹中的文件
     */
    private fun processFilesInSubFolder(
        treeUri: Uri,
        pattern: Regex,
        timestampFromFileName: (String) -> Long?,
        result: MutableList<Pair<Uri, Long>>,
    ) {
        runCatching {
            val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching
            val subFolder = rootFolder.findFile(subFolderName) ?: return@runCatching
            if (!subFolder.isDirectory) return@runCatching

            subFolder.listFiles().forEach { file ->
                processFileForPattern(file, pattern, timestampFromFileName, result)
            }
        }.onFailure { exception ->
            Logger.e(TAG, "SAF 列出文件失败", exception)
        }
    }

    /**
     * 处理单个文件以匹配模式
     */
    private fun processFileForPattern(
        file: DocumentFile,
        pattern: Regex,
        timestampFromFileName: (String) -> Long?,
        result: MutableList<Pair<Uri, Long>>,
    ) {
        val name = file.name ?: return
        if (!pattern.matches(name)) return

        val ts = timestampFromFileName(name) ?: file.lastModified()
        result.add(file.uri to ts)
    }

    /**
     * 读取最新可用 JSON：从最新文件开始回退，跳过/标记损坏文件。
     */
    suspend fun readLatestJson(
        pattern: Regex,
        timestampFromFileName: (String) -> Long?,
        isValidRootJson: (JSONObject) -> Boolean,
        onReadOk: ((uri: Uri) -> Unit)? = null,
    ): JSONObject? =
        withContext(Dispatchers.IO) {
            val files = listFilesByPattern(pattern, timestampFromFileName)
            if (files.isEmpty()) return@withContext null

            // 使用findFirstValidJson提取逻辑，减少循环中的return语句
            val validJson = findFirstValidJson(files, isValidRootJson, onReadOk)
            return@withContext validJson
        }

    /**
     * 查找第一个有效的JSON文件
     */
    private fun findFirstValidJson(
        files: List<Pair<Uri, Long>>,
        isValidRootJson: (JSONObject) -> Boolean,
        onReadOk: ((uri: Uri) -> Unit)?,
    ): JSONObject? {
        for ((uri, _) in files) {
            // 跳过已标记为损坏的文件
            if (isMarkedCorrupt(uri)) {
                continue
            }

            val json = readJsonFromUri(uri)
            if (json != null && isValidRootJson(json)) {
                onReadOk?.invoke(uri)
                return json
            }

            // 如果读取失败或结构异常，标记为损坏
            markCorruptAndLog(uri, json == null)
        }
        return null
    }

    /**
     * 标记文件为损坏并记录日志
     */
    private fun markCorruptAndLog(uri: Uri, isReadFailed: Boolean) {
        val fileName = queryDisplayName(uri) ?: uri.toString()
        val message = if (isReadFailed) {
            "读取 JSON 失败，标记为损坏并回退: $fileName"
        } else {
            "JSON 结构异常，标记为损坏并回退: $fileName"
        }
        Logger.w(TAG, message)
        markCorrupt(uri)
    }

    /**
     * 写入一个新的 JSON 文件，并清理旧文件（仅保留 keepLatest 个）。
     *
     * @param cleanupPattern 用于清理旧文件的匹配规则（通常与 list/read 使用同一 pattern）
     * @param compact true 表示不缩进（更小、更快），false 表示格式化输出
     */
    suspend fun writeJsonAndCleanup(
        fileName: String,
        json: JSONObject,
        cleanupPattern: Regex,
        timestampFromFileName: (String) -> Long?,
        compact: Boolean = true,
    ) =
        withContext(Dispatchers.IO) {
            // 使用 SAF
            val treeUri = getRootTreeUriOrNull()
            if (treeUri == null) {
                throw IOException("SAF 权限未授权，无法写入文件。请先授权存储权限。")
            }

            runCatching {
                writeViaSaf(treeUri, fileName, json, compact)
                cleanupOldFiles(cleanupPattern, timestampFromFileName)
            }.onFailure { exception ->
                Logger.e(TAG, "SAF 写入文件失败", exception)
                throw IOException("写入文件失败: ${exception.message}", exception)
            }
        }

    private fun writeViaSaf(
        treeUri: Uri,
        fileName: String,
        json: JSONObject,
        compact: Boolean,
    ) {
        val rootFolder =
            DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("无法打开 SAF 根文件夹（treeUri=$treeUri）")
        if (!rootFolder.isDirectory) throw IOException("SAF 目标不是文件夹（treeUri=$treeUri）")

        var subFolder = rootFolder.findFile(subFolderName)
        if (subFolder == null || !subFolder.isDirectory) {
            subFolder =
                rootFolder.createDirectory(subFolderName)
                    ?: throw IOException("无法创建子文件夹（$subFolderName）")
        }

        val existing = subFolder.findFile(fileName)
        val target =
            existing ?: subFolder.createFile("application/json", fileName)
                ?: throw IOException("无法创建 JSON 文件（fileName=$fileName）")

        resolver.openOutputStream(target.uri, "wt")?.use { out ->
            OutputStreamWriter(out).use { writer ->
                writer.write(if (compact) json.toString() else json.toString(2))
                writer.flush()
            }
        } ?: throw IOException("无法打开 SAF 输出流（uri=${target.uri}）")
    }

    @Suppress("UNUSED_PRIVATE_MEMBER")
    private fun writeJsonToUri(
        uri: Uri,
        json: JSONObject,
        compact: Boolean,
    ) {
        runCatching {
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开文件输出流（uri=$uri）")
            output.use {
                OutputStreamWriter(it).use { writer ->
                    writer.write(if (compact) json.toString() else json.toString(2))
                    writer.flush()
                }
            }
        }.onFailure {
            throw IOException("写入文件失败", it)
        }
    }

    private fun readJsonFromUri(uri: Uri): JSONObject? {
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                // 使用流式读取，避免大文件一次性加载到内存
                val buffer = CharArray(8192) // 8KB 缓冲区
                val reader = input.bufferedReader()
                val content =
                    buildString {
                        var read: Int
                        while (reader.read(buffer).also { read = it } != -1) {
                            append(buffer, 0, read)
                        }
                    }
                if (content.isBlank()) return JSONObject()
                JSONObject(content)
            }
        }.onFailure { e ->
            // 记录详细的错误信息，便于问题定位
            val fileName = queryDisplayName(uri) ?: uri.toString()
            when {
                e is java.io.FileNotFoundException -> {
                    Logger.w(TAG, "文件不存在: $fileName", e)
                }
                e is SecurityException -> {
                    Logger.w(TAG, "文件访问权限不足: $fileName", e)
                }
                e is org.json.JSONException -> {
                    Logger.w(TAG, "JSON 解析失败（文件可能损坏）: $fileName", e)
                }
                e is java.io.IOException -> {
                    val msg = e.message ?: "未知IO错误"
                    if (msg.contains("空间", ignoreCase = true) || msg.contains("ENOSPC", ignoreCase = true)) {
                        Logger.w(TAG, "存储空间不足: $fileName", e)
                    } else {
                        Logger.w(TAG, "读取文件失败: $fileName, 错误: $msg", e)
                    }
                }
                else -> {
                    Logger.w(TAG, "读取文件时发生未知错误: $fileName", e)
                }
            }
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? {
        // 对于 SAF URI，使用 DocumentFile 获取文件名
        return runCatching {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            documentFile?.name
        }.getOrNull() ?: runCatching {
            // 回退到 ContentResolver 查询（兼容其他 URI 类型）
            val projection = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    cursor.getString(index)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private suspend fun cleanupOldFiles(
        pattern: Regex,
        timestampFromFileName: (String) -> Long?,
    ) =
        withContext(Dispatchers.IO) {
            val files = listFilesByPattern(pattern, timestampFromFileName)
            if (files.size <= keepLatest) return@withContext

            val filesToDelete = files.drop(keepLatest)
            filesToDelete.forEach { (uri, _) ->
                runCatching {
                    // 使用 DocumentFile 删除 SAF URI
                    val doc = DocumentFile.fromSingleUri(context, uri)
                    if (doc?.delete() != true) {
                        Logger.w(TAG, "无法删除文件: ${queryDisplayName(uri) ?: uri}")
                    }
                }.onFailure { exception ->
                    Logger.w(TAG, "删除旧文件失败: ${queryDisplayName(uri) ?: uri}", exception)
                }
            }
        }
}
