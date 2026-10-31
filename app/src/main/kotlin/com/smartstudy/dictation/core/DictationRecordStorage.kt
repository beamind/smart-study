package com.smartstudy.dictation.core

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.smartstudy.core.Logger
import com.smartstudy.storage.AppStorageManager
import com.smartstudy.storage.JsonHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 听写记录存储类
 * 使用 SAF (Storage Access Framework) 存储听写记录到 Documents/smartstudy/DictationQuestions/ 目录
 *
 * 功能特性：
 * - 支持中文和英文听写记录的保存和查询
 * - 记录每道题目的听写历史（正确/错误状态、时间戳）
 * - 自动维护文件历史，支持回退到上一个版本
 * - 只负责数据存储，不包含业务逻辑（统计计算等）
 *
 * 注意：统计计算逻辑已移至 DictationStatisticsService，通过 DictationRepository 使用
 *
 * 使用示例：
 * ```
 * val storage = DictationRecordStorage(context)
 * storage.saveQuestions(userName, "chinese", questionRecords)
 * val records = storage.getAllRecords(userName, "chinese")
 * ```
 *
 * @param context Android Context，用于访问存储
 */
class DictationRecordStorage(private val context: Context) {
    private val resolver = context.contentResolver
    private val relativePath = AppStorageManager.getSubPath("DictationQuestions")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val subFolderName = "DictationQuestions"
    private val historyStore =
        JsonHistoryStore(
            context = context,
            subFolderName = subFolderName,
            prefsName = PREFS_NAME,
            keepLatest = 10,
        )

    // 日期格式化器
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // 文件名时间戳格式化器
    private val fileNameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private fun ensureSchemaVersion(json: JSONObject) {
        if (!json.has(SCHEMA_VERSION_KEY)) {
            json.put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
        }
    }


    private fun isValidRootJson(json: JSONObject): Boolean {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == SCHEMA_VERSION_KEY) continue
            if (json.optJSONArray(key) == null) return false
        }
        return true
    }

    /**
     * 保存题目记录
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @param questions 题目记录列表
     */
    suspend fun saveQuestions(
        userName: String,
        type: String,
        questions: List<QuestionRecord>,
    ) =
        withContext(Dispatchers.IO) {
            validateSaveInput(questions, userName, type)

            val fileName = generateFileNameWithTimestamp(userName, type)
            val recordsWithTimestamp = ensureValidTimestamps(questions)
            val json = prepareJsonForSave(userName, type)

            addRecordsToJson(json, recordsWithTimestamp)

            saveJsonToFile(userName, type, fileName, json, questions.size)
        }

    /**
     * 验证保存输入
     */
    private fun validateSaveInput(
        questions: List<QuestionRecord>,
        userName: String,
        type: String,
    ) {
        if (questions.isEmpty()) {
            Logger.d(TAG, "没有题目需要保存")
            return
        }

        if (userName.isBlank()) {
            val errorMessage = context.getString(com.smartstudy.R.string.validation_error_username_empty)
            Logger.e(TAG, errorMessage)
            throw IllegalArgumentException(errorMessage)
        }

        if (type.isBlank()) {
            Logger.e(TAG, "类型不能为空")
            throw IllegalArgumentException("类型不能为空")
        }

        if (questions.size > 1000) {
            Logger.w(TAG, "题目数量过多（${questions.size}），可能影响性能")
        }
    }

    /**
     * 确保所有记录都有有效的时间戳
     */
    private fun ensureValidTimestamps(questions: List<QuestionRecord>): List<QuestionRecord> {
        return questions.map { record ->
            if (record.timestamp <= 0L) {
                record.copy(timestamp = System.currentTimeMillis())
            } else {
                record
            }
        }
    }

    /**
     * 准备用于保存的 JSON 对象
     */
    private suspend fun prepareJsonForSave(userName: String, type: String): JSONObject {
        val latestFileJson = readLatestFileJson(userName, type)

        val json =
            if (latestFileJson != null) {
                JSONObject(latestFileJson.toString())
            } else {
                JSONObject()
            }
        ensureSchemaVersion(json)

        return json
    }

    /**
     * 将记录添加到 JSON 对象
     */
    private fun addRecordsToJson(
        json: JSONObject,
        records: List<QuestionRecord>,
    ) {
        records.forEach { record ->
            val wordArray = getOrCreateWordArray(json, record.word)
            val recordObj = createRecordObject(record)
            wordArray.put(recordObj)
        }
    }

    /**
     * 获取或创建单词的记录数组
     */
    private fun getOrCreateWordArray(json: JSONObject, word: String): JSONArray {
        return if (json.has(word)) {
            json.getJSONArray(word)
        } else {
            JSONArray().also { json.put(word, it) }
        }
    }

    /**
     * 创建记录对象
     */
    private fun createRecordObject(record: QuestionRecord): JSONObject {
        val dateStr = formatTimestamp(record.timestamp)
        val status = if (record.isCorrect) "正确" else "错误"

        return JSONObject().apply {
            put("date", dateStr)
            put("status", status)
            if (record.chineseTranslation.isNotEmpty()) {
                put("translation", record.chineseTranslation)
            }
            put("sourceType", record.sourceType)
        }
    }

    /**
     * 保存 JSON 到文件
     */
    private suspend fun saveJsonToFile(
        userName: String,
        type: String,
        fileName: String,
        json: JSONObject,
        recordCount: Int,
    ) {
        val pattern = buildFileNamePattern(userName, type)

        try {
            historyStore.writeJsonAndCleanup(
                fileName = fileName,
                json = json,
                cleanupPattern = pattern,
                timestampFromFileName = ::parseTimestampFromFileName,
                compact = true,
            )
            Logger.d(TAG, "保存了 $recordCount 条新记录到 $fileName（全量存储）")
        } catch (e: SecurityException) {
            Logger.e(TAG, "保存文件权限不足: $fileName", e)
            throw FilePermissionException(fileName)
        } catch (e: IOException) {
            Logger.e(TAG, "保存文件IO错误: $fileName", e)
            throw FilePermissionException(fileName)
        }
    }

    /**
     * 获取所有题目记录（原始数据，不进行统计计算）
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @return 题目记录列表
     */
    suspend fun getAllRecords(
        userName: String,
        type: String,
    ): List<QuestionRecord> =
        withContext(Dispatchers.IO) {
            // 读取最新的文件（全量存储，最新文件包含所有历史记录）
            val latestFileJson = readLatestFileJson(userName, type)

            if (latestFileJson == null || latestFileJson.length() == 0) {
                Logger.d(TAG, "没有找到任何记录")
                return@withContext emptyList()
            }

            // 将 JSON 对象转换为记录列表
            val records = jsonToRecords(latestFileJson)

            if (records.isEmpty()) {
                Logger.d(TAG, "最新文件中没有记录")
                return@withContext emptyList()
            }

            return@withContext records
        }

    /**
     * 按日期范围获取题目记录
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @param startDate 开始日期（时间戳，毫秒）
     * @param endDate 结束日期（时间戳，毫秒）
     * @return 题目记录列表
     */
    suspend fun getRecordsByDateRange(
        userName: String,
        type: String,
        startDate: Long,
        endDate: Long,
    ): List<QuestionRecord> =
        withContext(Dispatchers.IO) {
            // 获取所有记录
            val allRecords = getAllRecords(userName, type)

            // 筛选日期范围内的记录
            // 开始日期：当天的00:00:00
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = startDate
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            val startTimestamp = calendar.timeInMillis

            // 结束日期：当天的23:59:59.999
            calendar.timeInMillis = endDate
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
            calendar.set(java.util.Calendar.MINUTE, 59)
            calendar.set(java.util.Calendar.SECOND, 59)
            calendar.set(java.util.Calendar.MILLISECOND, 999)
            val endTimestamp = calendar.timeInMillis

            val filteredRecords =
                allRecords.filter { record ->
                    record.timestamp >= startTimestamp && record.timestamp <= endTimestamp
                }

            Logger.d(TAG, "日期范围筛选：${allRecords.size} 条记录中筛选出 ${filteredRecords.size} 条")
            return@withContext filteredRecords
        }

    /**
     * 删除指定词语的所有记录
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @param words 要删除的词语列表
     */
    suspend fun deleteWords(
        userName: String,
        type: String,
        words: List<String>,
    ) =
        withContext(Dispatchers.IO) {
            if (words.isEmpty()) {
                Logger.d(TAG, "没有词语需要删除")
                return@withContext
            }

            val wordsToDelete = words.toSet()

            // 读取最新的文件（全量存储，最新文件包含所有历史记录）
            val latestFileJson = readLatestFileJson(userName, type)

            if (latestFileJson == null || latestFileJson.length() == 0) {
                Logger.d(TAG, "没有找到任何记录")
                return@withContext
            }

            // 使用最新文件的 JSON 对象作为基础（深拷贝）
            val json = JSONObject(latestFileJson.toString())
            ensureSchemaVersion(json)

            // 删除指定的单词键
            var deletedCount = 0
            wordsToDelete.forEach { word ->
                if (json.has(word)) {
                    json.remove(word)
                    deletedCount++
                }
            }

            if (deletedCount == 0) {
                Logger.d(TAG, "没有找到要删除的词语记录")
                return@withContext
            }

            // 生成带时间戳的新文件名
            val fileName = generateFileNameWithTimestamp(userName, type)

            val pattern = buildFileNamePattern(userName, type)
            try {
                historyStore.writeJsonAndCleanup(
                    fileName = fileName,
                    json = json,
                    cleanupPattern = pattern,
                    timestampFromFileName = ::parseTimestampFromFileName,
                    compact = true,
                )
                Logger.d(TAG, "删除了 $deletedCount 个词语，保存到 $fileName")
            } catch (e: SecurityException) {
                // 权限失败时抛出 FilePermissionException，便于 Activity 引导用户授权
                Logger.e(TAG, "删除文件权限不足: $fileName", e)
                throw FilePermissionException(fileName)
            } catch (e: IOException) {
                // IO失败时抛出 FilePermissionException，便于 Activity 引导用户授权
                Logger.e(TAG, "删除文件IO错误: $fileName", e)
                throw FilePermissionException(fileName)
            }
        }

    /**
     * 更新单词名称和翻译
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @param oldWord 旧单词名称
     * @param newWord 新单词名称
     * @param newTranslation 新翻译（可选，仅英文类型）
     */
    suspend fun updateWord(
        userName: String,
        type: String,
        oldWord: String,
        newWord: String,
        newTranslation: String = "",
    ) =
        withContext(Dispatchers.IO) {
            if (oldWord == newWord && newTranslation.isEmpty()) {
                Logger.d(TAG, "单词和翻译都没有变化，无需更新")
                return@withContext
            }

            val json = prepareJsonForUpdate(userName, type, oldWord) ?: return@withContext

            if (oldWord == newWord) {
                updateTranslationOnly(json, oldWord, type, newTranslation)
            } else {
                migrateWordRecords(json, oldWord, newWord, type, newTranslation)
            }

            saveUpdatedJson(userName, type, json, oldWord, newWord)
        }

    /**
     * 准备用于更新的 JSON 对象
     */
    private suspend fun prepareJsonForUpdate(
        userName: String,
        type: String,
        oldWord: String,
    ): JSONObject? {
        val latestFileJson = readLatestFileJson(userName, type)

        if (latestFileJson == null || latestFileJson.length() == 0) {
            Logger.d(TAG, "没有找到任何记录")
            return null
        }

        val json = JSONObject(latestFileJson.toString())
        ensureSchemaVersion(json)

        if (!json.has(oldWord)) {
            Logger.d(TAG, "没有找到要更新的词语: $oldWord")
            return null
        }

        return json
    }

    /**
     * 仅更新翻译字段（单词名称不变）
     */
    private fun updateTranslationOnly(
        json: JSONObject,
        word: String,
        type: String,
        newTranslation: String,
    ) {
        if (type != "english" || newTranslation.isEmpty()) {
            return
        }

        val wordArray = json.getJSONArray(word)
        for (i in 0 until wordArray.length()) {
            val recordObj = wordArray.getJSONObject(i)
            recordObj.put("translation", newTranslation)
        }
    }

    /**
     * 迁移单词记录（单词名称改变）
     */
    private fun migrateWordRecords(
        json: JSONObject,
        oldWord: String,
        newWord: String,
        type: String,
        newTranslation: String,
    ) {
        val oldWordArray = json.getJSONArray(oldWord)

        val newWordArray =
            if (json.has(newWord)) {
                json.getJSONArray(newWord)
            } else {
                JSONArray().also { json.put(newWord, it) }
            }

        for (i in 0 until oldWordArray.length()) {
            val recordObj = oldWordArray.getJSONObject(i)
            val newRecordObj = JSONObject(recordObj.toString())

            if (type == "english" && newTranslation.isNotEmpty()) {
                newRecordObj.put("translation", newTranslation)
            }

            newWordArray.put(newRecordObj)
        }

        json.remove(oldWord)
    }

    /**
     * 保存更新后的 JSON
     */
    private suspend fun saveUpdatedJson(
        userName: String,
        type: String,
        json: JSONObject,
        oldWord: String,
        newWord: String,
    ) {
        val fileName = generateFileNameWithTimestamp(userName, type)
        val pattern = buildFileNamePattern(userName, type)

        try {
            historyStore.writeJsonAndCleanup(
                fileName = fileName,
                json = json,
                cleanupPattern = pattern,
                timestampFromFileName = ::parseTimestampFromFileName,
                compact = true,
            )
            Logger.d(TAG, "更新了单词: $oldWord -> $newWord，保存到 $fileName")
        } catch (e: SecurityException) {
            Logger.e(TAG, "更新文件权限不足: $fileName", e)
            throw FilePermissionException(fileName)
        } catch (e: IOException) {
            Logger.e(TAG, "更新文件IO错误: $fileName", e)
            throw FilePermissionException(fileName)
        }
    }

    /**
     * 更新词语最新记录的状态
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @param word 词语
     * @param isCorrect 是否正确
     */
    suspend fun updateLatestRecordStatus(
        userName: String,
        type: String,
        word: String,
        isCorrect: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            val json = readLatestFileJson(userName, type) ?: run {
                Logger.d(TAG, "没有找到记录文件")
                return@withContext
            }
            ensureSchemaVersion(json)

            if (!json.has(word)) {
                Logger.d(TAG, "词语不存在，无法更新状态: $word")
                return@withContext
            }

            val wordArray = json.getJSONArray(word)
            if (wordArray.length() == 0) {
                Logger.d(TAG, "词语记录为空，无法更新状态: $word")
                return@withContext
            }

            // 更新最新记录的状态（最后一个记录）
            val latestRecord = wordArray.getJSONObject(wordArray.length() - 1)
            val status = if (isCorrect) "正确" else "错误"
            latestRecord.put("status", status)

            // 保存更新后的JSON
            val fileName = generateFileNameWithTimestamp(userName, type)
            saveJsonToFile(userName, type, fileName, json, 0)

            Logger.d(TAG, "更新了词语状态: $word -> $status")
        }

    /**
     * 构建当前用户+类型的文件名匹配规则（用于读取最新文件与清理旧文件）。
     */
    private fun buildFileNamePattern(
        userName: String,
        type: String,
    ): Regex {
        val baseName =
            if (userName.isNotEmpty()) {
                "${sanitizeFileName(userName)}_${type}_questions"
            } else {
                "${type}_questions"
            }
        return Regex("^${Regex.escape(baseName)}_\\d{8}_\\d{6}\\.json$")
    }

    /**
     * 将 JSON 对象转换为记录列表
     * @param json JSON 对象
     * @return 记录列表
     */
    private fun jsonToRecords(json: JSONObject): List<QuestionRecord> {
        val result = mutableListOf<QuestionRecord>()
        val keys = json.keys()

        // 使用序列化方式处理键，减少循环中的跳转语句
        val validKeys = mutableListOf<String>()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != SCHEMA_VERSION_KEY && json.optJSONArray(key) != null) {
                validKeys.add(key)
            }
        }

        // 处理有效的键
        for (word in validKeys) {
            val wordArray = json.getJSONArray(word)
            addRecordsFromWordArray(result, word, wordArray)
        }

        return result
    }

    /**
     * 从单词数组中添加记录
     */
    private fun addRecordsFromWordArray(
        result: MutableList<QuestionRecord>,
        word: String,
        wordArray: org.json.JSONArray,
    ) {
        for (i in 0 until wordArray.length()) {
            val recordObj = wordArray.getJSONObject(i)
            val dateStr = recordObj.getString("date")
            val status = recordObj.getString("status")
            // 读取翻译信息（如果存在）
            val translation = recordObj.optString("translation", "")
            // 读取来源类型（如果不存在，默认为"error"以兼容旧数据）
            val sourceType = recordObj.optString("sourceType", "error")

            val timestamp = parseDate(dateStr)
            val isCorrect = status == "正确"

            result.add(QuestionRecord(word, isCorrect, timestamp, translation, sourceType))
        }
    }


    /**
     * 格式化时间戳为日期字符串
     */
    private fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(java.util.Date(timestamp))
    }

    /**
     * 解析日期字符串为时间戳
     */
    private fun parseDate(dateString: String): Long {
        return try {
            dateFormat.parse(dateString)?.time ?: System.currentTimeMillis()
        } catch (e: java.text.ParseException) {
            Logger.w(TAG, "日期格式错误，解析日期失败: $dateString", e)
            System.currentTimeMillis()
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "参数错误，解析日期失败: $dateString", e)
            System.currentTimeMillis()
        }
    }


    /**
     * 列出所有匹配的文件及其修改时间
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @return 文件 URI 和修改时间的列表，按修改时间降序排序（最新的在前）
     */
    private fun listFilesByPattern(
        userName: String,
        type: String,
    ): List<Pair<Uri, Long>> {
        val result = mutableListOf<Pair<Uri, Long>>()
        val pattern = buildFileNamePattern(userName, type)

        val treeUri = getDictationFolderTreeUriOrNull()
        if (treeUri == null) {
            Logger.w(TAG, "SAF 权限未授权，无法列出文件")
            return result.sortedByDescending { it.second }
        }

        processFilesInFolder(treeUri, pattern, result)

        // 按修改时间降序排序（最新的在前）
        return result.sortedByDescending { it.second }
    }


    /**
     * 处理文件夹中的文件
     */
    private fun processFilesInFolder(
        treeUri: Uri,
        pattern: Regex,
        result: MutableList<Pair<Uri, Long>>,
    ) {
        runCatching {
            val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching
            val subFolder = rootFolder.findFile(subFolderName) ?: return@runCatching
            if (!subFolder.isDirectory) return@runCatching

            subFolder.listFiles().forEach { file ->
                processFile(file, pattern, result)
            }
        }.onFailure { exception ->
            Logger.e(TAG, "SAF 列出文件失败", exception)
        }
    }

    /**
     * 处理单个文件
     */
    private fun processFile(
        file: DocumentFile,
        pattern: Regex,
        result: MutableList<Pair<Uri, Long>>,
    ) {
        val fileName = file.name ?: return
        if (!pattern.matches(fileName)) return

        val timestamp = parseTimestampFromFileName(fileName) ?: file.lastModified()
        result.add(file.uri to timestamp)
    }


    /**
     * 读取最新的文件 JSON 对象（全量存储，最新文件包含所有历史记录）
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @return 最新文件的 JSON 对象，如果不存在则返回 null
     */
    private suspend fun readLatestFileJson(
        userName: String,
        type: String,
    ): JSONObject? =
        historyStore
            .readLatestJson(
                pattern = buildFileNamePattern(userName, type),
                timestampFromFileName = ::parseTimestampFromFileName,
                isValidRootJson = ::isValidRootJson,
            )
            ?.also { ensureSchemaVersion(it) }

    /**
     * 查询文件显示名称
     */
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


    /**
     * 获取听写记录文件夹的 treeUri（如果已授权）
     */
    private fun getDictationFolderTreeUriOrNull(): Uri? {
        // 使用统一的 AppStorageManager 获取根 treeUri
        return AppStorageManager.getAppStorageTreeUri(context)
    }

    /**
     * 清理用户名，使其适合作为文件名
     * 移除或替换不适合文件名的字符
     */
    private fun sanitizeFileName(userName: String): String {
        // Windows/Linux 文件名不允许的字符: < > : " / \ | ? *
        // 同时移除前后空格和点号（避免文件名以点结尾）
        return userName
            .replace(Regex("""[<>:"/\\|?*]"""), "_") // 替换不允许的字符为下划线
            .trim() // 移除前后空格
            .replace(Regex("""\.+$"""), "") // 移除末尾的点号
            .takeIf { it.isNotEmpty() } ?: "用户" // 如果清理后为空，使用默认值
    }

    /**
     * 生成带时间戳的文件名
     * @param userName 用户名
     * @param type 类型（"chinese" 或 "english"）
     * @return 带时间戳的文件名，格式：{userName}_{type}_questions_{timestamp}.json
     */
    private fun generateFileNameWithTimestamp(
        userName: String,
        type: String,
    ): String {
        val timestamp = fileNameDateFormat.format(java.util.Date())
        val baseName =
            if (userName.isNotEmpty()) {
                "${sanitizeFileName(userName)}_${type}_questions"
            } else {
                "${type}_questions"
            }
        return "${baseName}_$timestamp.json"
    }

    /**
     * 从文件名解析时间戳
     * @param fileName 文件名
     * @return 时间戳（毫秒），如果解析失败返回 null
     */
    private fun parseTimestampFromFileName(fileName: String): Long? {
        // 文件名格式：{userName}_{type}_questions_{timestamp}.json
        // 提取时间戳部分（最后的下划线到 .json 之间的部分）
        val regex = Regex("""_(\d{8}_\d{6})\.json$""")
        val match = regex.find(fileName) ?: return null
        val timestampStr = match.groupValues[1]
        return try {
            fileNameDateFormat.parse(timestampStr)?.time
        } catch (e: java.text.ParseException) {
            Logger.w(TAG, "日期格式错误，解析文件名时间戳失败: $timestampStr", e)
            null
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "参数错误，解析文件名时间戳失败: $timestampStr", e)
            null
        }
    }

    companion object {
        private const val TAG = "DictationRecordStorage"
        private const val PREFS_NAME = "dictation_record_storage"
        private const val SCHEMA_VERSION_KEY = "schemaVersion"
        private const val SCHEMA_VERSION = 1
    }
}
