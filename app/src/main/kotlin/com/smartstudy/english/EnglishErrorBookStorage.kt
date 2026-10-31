package com.smartstudy.english

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
 * 英语错题本存储类
 * 使用 SAF (Storage Access Framework) 存储错题记录到 Documents/smartstudy/EnglishErrorBook/ 目录
 *
 * 功能特性：
 * - 支持错题的保存、查询、更新、删除
 * - 自动维护文件历史，支持回退到上一个版本
 * - 只负责数据存储，不包含业务逻辑（统计计算等）
 *
 * 注意：统计计算逻辑已移至 ErrorBookStatisticsService，通过 ErrorBookRepository 使用
 *
 * 使用示例：
 * ```
 * val storage = EnglishErrorBookStorage(context)
 * storage.saveQuestionsWithAnswers(userName, questionAnswers)
 * val json = storage.getAllQuestionsJson(userName)
 * ```
 *
 * @param context Android Context，用于访问存储
 */
class EnglishErrorBookStorage(private val context: Context) {
    private val resolver = context.contentResolver
    private val relativePath = AppStorageManager.getSubPath("EnglishErrorBook")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val subFolderName = "EnglishErrorBook"
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

    private fun buildFileNamePattern(userName: String): Regex {
        val baseName =
            if (userName.isNotEmpty()) {
                "${sanitizeFileName(userName)}_english_error_book"
            } else {
                "english_error_book"
            }
        return Regex("^${Regex.escape(baseName)}_\\d{8}_\\d{6}\\.json$")
    }

    /**
     * 题目统计信息数据类
     */
    data class QuestionWithStats(
        val question: String,
        val errorCount: Int,
        val totalCount: Int,
    ) {
        fun getErrorRateText(): String = "$errorCount/$totalCount"
    }

    /**
     * 题目完整统计信息数据类（用于采样）
     */
    data class QuestionStat(
        val question: String,
        val errorCount: Int,
        val totalCount: Int,
        val errorRate: Double,
        val lastPracticeTimestamp: Long,
    )

    /**
     * 获取所有错题数据的原始JSON（不进行统计计算）
     * @param userName 用户名
     * @return JSON对象，如果不存在则返回null
     */
    suspend fun getAllQuestionsJson(userName: String): JSONObject? =
        withContext(Dispatchers.IO) {
            readLatestFileJson(userName)
        }

    /**
     * 加载所有错题题目列表
     * @param userName 用户名
     * @return 题目列表，如果不存在则返回空列表
     */
    suspend fun loadAllQuestions(userName: String): List<String> =
        withContext(Dispatchers.IO) {
            val json = readLatestFileJson(userName)
            if (json == null || json.length() == 0) {
                return@withContext emptyList()
            }

            val questions = mutableListOf<String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val question = keys.next()
                // 跳过 schemaVersion 键
                if (question != SCHEMA_VERSION_KEY) {
                    questions.add(question)
                }
            }

            return@withContext questions
        }

    /**
     * 更新错题（当题目内容改变时，需要更新key）
     * @param userName 用户名
     * @param oldQuestion 旧的题目内容
     * @param newQuestion 新的题目内容
     */
    suspend fun updateQuestion(
        userName: String,
        oldQuestion: String,
        newQuestion: String,
    ) =
        withContext(Dispatchers.IO) {
            if (oldQuestion == newQuestion) {
                Logger.d(TAG, "题目内容未改变，无需更新")
                return@withContext
            }

            val json = readLatestFileJson(userName) ?: JSONObject()
            ensureSchemaVersion(json)

            // 如果旧题目存在，获取其记录数组
            val oldRecords =
                if (json.has(oldQuestion)) {
                    json.getJSONArray(oldQuestion)
                } else {
                    // 如果旧题目不存在，创建新的记录数组
                    JSONArray()
                }

            // 如果新题目已经存在，合并记录数组
            val newRecords =
                if (json.has(newQuestion)) {
                    val existingRecords = json.getJSONArray(newQuestion)
                    // 合并记录：先添加旧记录，再添加新记录（避免重复）
                    val mergedRecords = JSONArray()
                    // 添加旧记录
                    for (i in 0 until oldRecords.length()) {
                        mergedRecords.put(oldRecords.getJSONObject(i))
                    }
                    // 添加新记录（如果不存在）
                    for (i in 0 until existingRecords.length()) {
                        mergedRecords.put(existingRecords.getJSONObject(i))
                    }
                    mergedRecords
                } else {
                    oldRecords
                }

            // 删除旧的key
            if (json.has(oldQuestion)) {
                json.remove(oldQuestion)
            }

            // 添加新的key，使用合并后的记录数组
            json.put(newQuestion, newRecords)

            // 保存更新后的JSON
            saveJsonToLatestFile(userName, json)

            Logger.d(TAG, "更新了错题: $oldQuestion -> $newQuestion")
        }

    /**
     * 更新错题的答案
     * @param userName 用户名
     * @param question 题目内容
     * @param newAnswer 新的答案
     */
    suspend fun updateAnswer(
        userName: String,
        question: String,
        newAnswer: String,
    ) =
        withContext(Dispatchers.IO) {
            val json = readLatestFileJson(userName) ?: JSONObject()
            ensureSchemaVersion(json)

            // 如果题目不存在，直接返回
            if (!json.has(question)) {
                Logger.d(TAG, "题目不存在，无法更新答案: $question")
                return@withContext
            }

            val records = json.getJSONArray(question)

            // 如果记录为空，不应该创建新记录，因为这只是更新答案，不是创建新记录
            // 记录为空说明题目还没有被练习过，更新答案没有意义，直接返回
            if (records.length() == 0) {
                Logger.d(TAG, "题目记录为空，无法更新答案: $question")
                return@withContext
            }

            // 更新最新记录的答案（最后一个记录）
            val latestRecord = records.getJSONObject(records.length() - 1)
            if (newAnswer.isNotEmpty()) {
                latestRecord.put("answer", newAnswer)
            } else {
                // 如果新答案为空，移除答案字段
                latestRecord.remove("answer")
            }

            // 保存更新后的JSON
            saveJsonToLatestFile(userName, json)

            Logger.d(TAG, "更新了错题答案: $question -> $newAnswer")
        }

    /**
     * 更新题目最新记录的状态
     * @param userName 用户名
     * @param question 题目内容
     * @param isCorrect 是否正确
     */
    suspend fun updateLatestRecordStatus(
        userName: String,
        question: String,
        isCorrect: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            val json = readLatestFileJson(userName) ?: JSONObject()
            ensureSchemaVersion(json)

            // 如果题目不存在，直接返回
            if (!json.has(question)) {
                Logger.d(TAG, "题目不存在，无法更新状态: $question")
                return@withContext
            }

            val records = json.getJSONArray(question)

            // 如果记录为空，不应该创建新记录，因为这只是更新状态，不是创建新记录
            if (records.length() == 0) {
                Logger.d(TAG, "题目记录为空，无法更新状态: $question")
                return@withContext
            }

            // 更新最新记录的状态（最后一个记录）
            val latestRecord = records.getJSONObject(records.length() - 1)
            val status = if (isCorrect) "正确" else "错误"
            latestRecord.put("status", status)

            // 保存更新后的JSON
            saveJsonToLatestFile(userName, json)

            Logger.d(TAG, "更新了题目状态: $question -> $status")
        }

    /**
     * 删除错题
     * @param userName 用户名
     * @param questionsToDelete 要删除的题目列表
     */
    suspend fun deleteQuestions(
        userName: String,
        questionsToDelete: List<String>,
    ) =
        withContext(Dispatchers.IO) {
            if (questionsToDelete.isEmpty()) {
                Logger.d(TAG, "没有题目需要删除")
                return@withContext
            }

            val json = readLatestFileJson(userName) ?: JSONObject()
            ensureSchemaVersion(json)

            var deletedCount = 0
            questionsToDelete.forEach { question ->
                if (json.has(question)) {
                    json.remove(question)
                    deletedCount++
                }
            }

            if (deletedCount > 0) {
                // 保存更新后的JSON
                saveJsonToLatestFile(userName, json)
                Logger.d(TAG, "删除了 $deletedCount 道错题")
            } else {
                Logger.d(TAG, "没有找到要删除的题目")
            }
        }

    /**
     * 按照题目顺序重新排列JSON对象
     * @param userName 用户名
     * @param questionOrder 题目顺序列表（按显示顺序）
     */
    suspend fun reorderJsonByQuestionOrder(
        userName: String,
        questionOrder: List<String>,
    ) =
        withContext(Dispatchers.IO) {
            val json = readLatestFileJson(userName) ?: return@withContext

            // 如果题目顺序为空或只有一个，无需重新排序
            if (questionOrder.isEmpty() || questionOrder.size == 1) {
                return@withContext
            }

            // 创建新的JSON对象，按照指定顺序添加keys
            val newJson = JSONObject()

            // 先按照指定顺序添加题目
            questionOrder.forEach { question ->
                if (json.has(question)) {
                    newJson.put(question, json.getJSONArray(question))
                }
            }

            // 添加不在顺序列表中的其他题目（保持原有顺序）
            val keys = json.keys()
            val remainingQuestions = mutableListOf<String>()
            while (keys.hasNext()) {
                val question = keys.next()
                // 跳过 schemaVersion 键，它不是题目数据
                if (question != SCHEMA_VERSION_KEY && !questionOrder.contains(question)) {
                    remainingQuestions.add(question)
                }
            }
            // 添加剩余的题目
            for (question in remainingQuestions) {
                newJson.put(question, json.getJSONArray(question))
            }

            // 确保新JSON包含 schemaVersion
            ensureSchemaVersion(newJson)

            // 保存重新排序后的JSON
            saveJsonToLatestFile(userName, newJson)

            Logger.d(TAG, "按照题目顺序重新排列了JSON，共 ${newJson.length()} 道题目")
        }

    /**
     * 保存JSON到最新文件（覆盖模式）
     * @param userName 用户名
     * @param json JSON对象
     */
    private suspend fun saveJsonToLatestFile(
        userName: String,
        json: JSONObject,
    ) =
        withContext(Dispatchers.IO) {
            val fileName = generateFileNameWithTimestamp(userName)

            historyStore.writeJsonAndCleanup(
                fileName = fileName,
                json = json,
                cleanupPattern = buildFileNamePattern(userName),
                timestampFromFileName = ::parseTimestampFromFileName,
                compact = true,
            )

            Logger.d(TAG, "保存了更新后的JSON到 $fileName")
        }

    /**
     * 保存错题记录（带答案）
     * @param userName 用户名
     * @param questionAnswers 题目和答案对列表（题目内容作为key）
     * @throws IllegalArgumentException 如果题目列表为空
     * @throws IOException 如果文件写入失败
     */
    suspend fun saveQuestionsWithAnswers(
        userName: String,
        questionAnswers: List<com.smartstudy.english.QuestionAnswer>,
    ) =
        withContext(Dispatchers.IO) {
            validateInput(questionAnswers)
            Logger.d(TAG, "开始保存 ${questionAnswers.size} 道错题，用户名: $userName")

            try {
                val fileName = generateFileNameWithTimestamp(userName)
                Logger.d(TAG, "生成文件名: $fileName")

                val json = prepareJsonFromLatestFile(userName)
                ensureSchemaVersion(json)

                val addedCount = addQuestionRecordsToJson(json, questionAnswers)
                validateAddedCount(addedCount, questionAnswers.size)

                Logger.d(TAG, "成功添加 $addedCount/${questionAnswers.size} 条记录到JSON对象，准备写入文件")

                // 写入文件
                historyStore.writeJsonAndCleanup(
                    fileName = fileName,
                    json = json,
                    cleanupPattern = buildFileNamePattern(userName),
                    timestampFromFileName = ::parseTimestampFromFileName,
                    compact = true,
                )

                Logger.d(TAG, "成功保存 $addedCount 条新记录到 $fileName（全量存储）")
            } catch (e: java.io.IOException) {
                Logger.e(TAG, "保存错题失败：文件写入错误", e)
                throw e // 重新抛出IOException，让上层处理
            } catch (e: OutOfMemoryError) {
                Logger.e(TAG, "保存错题失败：内存不足", e)
                throw IOException("保存失败：内存不足，请清理空间后重试", e)
            } catch (e: Exception) {
                Logger.e(TAG, "保存错题失败：未知错误", e)
                throw IOException("保存失败：${e.message ?: "未知错误"}", e)
            }
        }

    /**
     * 保存错题记录
     * @param userName 用户名
     * @param questions 题目列表（题目内容作为key）
     * @throws IllegalArgumentException 如果题目列表为空
     * @throws IOException 如果文件写入失败
     */
    suspend fun saveQuestions(
        userName: String,
        questions: List<String>,
    ) =
        withContext(Dispatchers.IO) {
            // 检查题目列表是否为空，如果为空抛出异常而不是静默返回
            if (questions.isEmpty()) {
                val errorMsg = "没有题目需要保存"
                Logger.w(TAG, errorMsg)
                throw IllegalArgumentException(errorMsg)
            }

            Logger.d(TAG, "开始保存 ${questions.size} 道错题，用户名: $userName")

            try {
                // 生成带时间戳的文件名
                val fileName = generateFileNameWithTimestamp(userName)
                Logger.d(TAG, "生成文件名: $fileName")

                // 读取最新的文件（全量存储，最新文件包含所有历史记录）
                val latestFileJson = readLatestFileJson(userName)
                Logger.d(TAG, if (latestFileJson != null) "读取到现有文件，包含 ${latestFileJson.length()} 个题目" else "未找到现有文件，创建新文件")

                // 使用最新文件的 JSON 对象作为基础（如果存在）
                val json =
                    try {
                        if (latestFileJson != null) {
                            // 深拷贝：先转换为字符串再解析，避免引用问题
                            JSONObject(latestFileJson.toString())
                        } else {
                            JSONObject()
                        }
                    } catch (e: OutOfMemoryError) {
                        Logger.e(TAG, "JSON深拷贝失败：内存不足，尝试使用原始对象", e)
                        latestFileJson ?: JSONObject()
                    } catch (e: org.json.JSONException) {
                        Logger.e(TAG, "JSON深拷贝失败：JSON格式错误", e)
                        throw IOException("读取现有错题文件失败：JSON格式错误", e)
                    } catch (e: Exception) {
                        Logger.e(TAG, "JSON深拷贝失败：未知错误", e)
                        throw IOException("读取现有错题文件失败：${e.message}", e)
                    }

                ensureSchemaVersion(json)

                // 将新记录添加到 JSON 对象
                val currentTime = System.currentTimeMillis()
                val dateStr = formatTimestamp(currentTime)

                var addedCount = 0
                questions.forEach { question ->
                    try {
                        // 获取或创建该题目的记录数组
                        val questionArray =
                            if (json.has(question)) {
                                json.getJSONArray(question)
                            } else {
                                JSONArray().also { json.put(question, it) }
                            }

                        // 添加新记录（初始状态为"错误"）
                        val recordObj =
                            JSONObject().apply {
                                put("date", dateStr)
                                put("status", "错误")
                            }
                        questionArray.put(recordObj)
                        addedCount++
                    } catch (e: Exception) {
                        Logger.e(TAG, "添加题目记录失败: $question", e)
                        // 继续处理其他题目，不因为单个题目失败而中断整个保存过程
                    }
                }

                if (addedCount == 0) {
                    val errorMsg = "所有题目都添加失败，无法保存"
                    Logger.e(TAG, errorMsg)
                    throw IOException(errorMsg)
                }

                Logger.d(TAG, "成功添加 $addedCount/${questions.size} 条记录到JSON对象，准备写入文件")

                // 写入文件
                historyStore.writeJsonAndCleanup(
                    fileName = fileName,
                    json = json,
                    cleanupPattern = buildFileNamePattern(userName),
                    timestampFromFileName = ::parseTimestampFromFileName,
                    compact = true,
                )

                Logger.d(TAG, "成功保存 $addedCount 条新记录到 $fileName（全量存储）")
            } catch (e: java.io.IOException) {
                Logger.e(TAG, "保存错题失败：文件写入错误", e)
                throw e // 重新抛出IOException，让上层处理
            } catch (e: OutOfMemoryError) {
                Logger.e(TAG, "保存错题失败：内存不足", e)
                throw IOException("保存失败：内存不足，请清理空间后重试", e)
            } catch (e: Exception) {
                Logger.e(TAG, "保存错题失败：未知错误", e)
                throw IOException("保存失败：${e.message ?: "未知错误"}", e)
            }
        }


    /**
     * 格式化时间戳为日期字符串
     */
    private fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(java.util.Date(timestamp))
    }


    /**
     * 列出所有匹配的文件及其修改时间
     * @param userName 用户名
     * @return 文件 URI 和修改时间的列表，按修改时间降序排序（最新的在前）
     */
    private fun listFilesByPattern(userName: String): List<Pair<Uri, Long>> {
        val result = mutableListOf<Pair<Uri, Long>>()
        val pattern = buildFileNamePattern(userName)

        val treeUri = getErrorBookFolderTreeUriOrNull()
        if (treeUri == null) {
            Logger.w(TAG, "SAF 权限未授权，无法列出文件")
            return result.sortedByDescending { it.second }
        }

        processFilesInSubFolder(treeUri, pattern, result)

        // 按修改时间降序排序（最新的在前）
        return result.sortedByDescending { it.second }
    }

    /**
     * 处理子文件夹中的文件
     */
    private fun processFilesInSubFolder(
        treeUri: Uri,
        pattern: Regex,
        result: MutableList<Pair<Uri, Long>>,
    ) {
        runCatching {
            val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching
            val subFolder = rootFolder.findFile(subFolderName) ?: return@runCatching
            if (!subFolder.isDirectory) return@runCatching

            subFolder.listFiles().forEach { file ->
                processFileForPattern(file, pattern, result)
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
     * @return 最新文件的 JSON 对象，如果不存在则返回 null
     */
    private suspend fun readLatestFileJson(userName: String): JSONObject? =
        historyStore
            .readLatestJson(
                pattern = buildFileNamePattern(userName),
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
     * 获取错题本文件夹的 treeUri（如果已授权）
     */
    private fun getErrorBookFolderTreeUriOrNull(): Uri? {
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
     * @return 带时间戳的文件名，格式：{userName}_english_error_book_{timestamp}.json
     */
    private fun generateFileNameWithTimestamp(userName: String): String {
        val timestamp = fileNameDateFormat.format(java.util.Date())
        val baseName =
            if (userName.isNotEmpty()) {
                "${sanitizeFileName(userName)}_english_error_book"
            } else {
                "english_error_book"
            }
        return "${baseName}_$timestamp.json"
    }

    /**
     * 从文件名解析时间戳
     * @param fileName 文件名
     * @return 时间戳（毫秒），如果解析失败返回 null
     */
    private fun parseTimestampFromFileName(fileName: String): Long? {
        // 文件名格式：{userName}_english_error_book_{timestamp}.json
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

    /**
     * 保存练习题目到文件
     * @param userName 用户名
     * @param questions 题目列表
     */
    suspend fun savePracticeQuestions(
        userName: String,
        questions: List<String>,
    ) =
        withContext(Dispatchers.IO) {
            if (questions.isEmpty()) {
                Logger.d(TAG, "没有题目需要保存")
                return@withContext
            }

            val fileName = generatePracticeFileName(userName)
            val content = buildPracticeFileContent(userName, questions)

            // 使用 SAF
            val treeUri = getErrorBookFolderTreeUriOrNull()
            if (treeUri == null) {
                throw IOException("SAF 权限未授权，无法保存练习文件。请先授权存储权限。")
            }

            savePracticeFileViaSaf(treeUri, fileName, content, questions.size)
        }

    /**
     * 生成练习文件名
     * @param userName 用户名
     * @return 文件名
     */
    private fun generatePracticeFileName(userName: String): String {
        val timestamp = fileNameDateFormat.format(java.util.Date())
        val sanitizedUserName = sanitizeFileName(userName)
        return if (sanitizedUserName.isNotEmpty()) {
            "${sanitizedUserName}_practice_$timestamp.txt"
        } else {
            "practice_$timestamp.txt"
        }
    }

    /**
     * 构建练习文件内容
     * @param userName 用户名
     * @param questions 题目列表
     * @return 文件内容
     */
    private fun buildPracticeFileContent(
        userName: String,
        questions: List<String>,
    ): String {
        val currentDate = dateFormat.format(java.util.Date())

        val questionsText =
            questions.mapIndexed { index, question ->
                "${index + 1}. $question"
            }.joinToString("\n\n")

        return """
            英语错题练习

            学生姓名：$userName
            生成时间：$currentDate
            题目数量：${questions.size} 道

            ${"=".repeat(50)}

            $questionsText
            """.trimIndent()
    }

    /**
     * 通过 SAF 保存练习文件
     * @param treeUri SAF tree URI
     * @param fileName 文件名
     * @param content 文件内容
     * @param questionCount 题目数量
     * @return true 如果保存成功，false 如果失败
     */
    private suspend fun savePracticeFileViaSaf(
        treeUri: Uri,
        fileName: String,
        content: String,
        questionCount: Int,
    ): Boolean {
        return runCatching {
            val rootFolder =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IOException("无法打开 SAF 根文件夹（treeUri=$treeUri）")
            if (!rootFolder.isDirectory) throw IOException("SAF 目标不是文件夹（treeUri=$treeUri）")

            val practiceFolder = getOrCreatePracticeFolder(rootFolder)
            val file =
                practiceFolder.createFile("text/plain", fileName)
                    ?: throw IOException("无法创建练习文件（fileName=$fileName）")

            writeContentToFile(file.uri, content)

            Logger.d(TAG, "通过 SAF 保存了 $questionCount 道练习题目到 $fileName")
            true
        }.onFailure {
            Logger.w(TAG, "writeViaSaf failed, fallback to MediaStore. reason=${it.message}", it)
        }.getOrDefault(false)
    }

    /**
     * 获取或创建 Practice 文件夹
     * @param rootFolder SAF 根文件夹
     * @return Practice 文件夹
     */
    private fun getOrCreatePracticeFolder(rootFolder: DocumentFile): DocumentFile {
        // 获取或创建 EnglishErrorBook 子文件夹
        var errorBookFolder = rootFolder.findFile(subFolderName)
        if (errorBookFolder == null || !errorBookFolder.isDirectory) {
            errorBookFolder = rootFolder.createDirectory(subFolderName)
                ?: throw IOException("无法创建 EnglishErrorBook 子文件夹")
        }

        // 获取或创建 Practice 子文件夹
        val practiceSubFolderName = "Practice"
        var practiceFolder = errorBookFolder.findFile(practiceSubFolderName)
        if (practiceFolder == null || !practiceFolder.isDirectory) {
            practiceFolder = errorBookFolder.createDirectory(practiceSubFolderName)
                ?: throw IOException("无法创建 Practice 子文件夹")
        }

        return practiceFolder
    }

    /**
     * 将内容写入文件
     * @param uri 文件 URI
     * @param content 文件内容
     */
    private fun writeContentToFile(
        uri: Uri,
        content: String,
    ) {
        resolver.openOutputStream(uri, "wt")?.use { out ->
            OutputStreamWriter(out).use { writer ->
                writer.write(content)
                writer.flush()
            }
        } ?: throw IOException("无法打开输出流（uri=$uri）")
    }

    /**
     * 写入文本内容到 URI
     */
    private fun writeTextToUri(
        uri: Uri,
        text: String,
    ) {
        runCatching {
            val output =
                resolver.openOutputStream(uri, "wt")
                    ?: throw IOException("无法打开文件输出流（uri=$uri）")
            output.use {
                OutputStreamWriter(it).use { writer ->
                    writer.write(text)
                    writer.flush()
                }
            }
        }.onFailure {
            throw IOException("写入文件失败", it)
        }
    }

    companion object {
        private const val TAG = "EnglishErrorBookStorage"
        private const val PREFS_NAME = "english_error_book_storage"
        private const val SCHEMA_VERSION_KEY = "schemaVersion"
        private const val SCHEMA_VERSION = 1
    }

    /**
     * 验证输入参数
     */
    private fun validateInput(questionAnswers: List<com.smartstudy.english.QuestionAnswer>) {
        if (questionAnswers.isEmpty()) {
            val errorMsg = "没有题目需要保存"
            Logger.w(TAG, errorMsg)
            throw IllegalArgumentException(errorMsg)
        }
    }

    /**
     * 从最新文件准备 JSON 对象
     */
    private suspend fun prepareJsonFromLatestFile(userName: String): JSONObject {
        val latestFileJson = readLatestFileJson(userName)
        Logger.d(TAG, if (latestFileJson != null) "读取到现有文件，包含 ${latestFileJson.length()} 个题目" else "未找到现有文件，创建新文件")

        return try {
            if (latestFileJson != null) {
                // 深拷贝：先转换为字符串再解析，避免引用问题
                // 如果JSON很大可能抛出OutOfMemoryError，需要捕获
                JSONObject(latestFileJson.toString())
            } else {
                JSONObject()
            }
        } catch (e: OutOfMemoryError) {
            Logger.e(TAG, "JSON深拷贝失败：内存不足，尝试使用原始对象", e)
            // 内存不足时，尝试直接使用原始对象（虽然不理想，但比失败好）
            // 注意：这可能导致并发问题，但至少能保存数据
            latestFileJson ?: JSONObject()
        } catch (e: org.json.JSONException) {
            Logger.e(TAG, "JSON深拷贝失败：JSON格式错误", e)
            throw IOException("读取现有错题文件失败：JSON格式错误", e)
        } catch (e: Exception) {
            Logger.e(TAG, "JSON深拷贝失败：未知错误", e)
            throw IOException("读取现有错题文件失败：${e.message}", e)
        }
    }

    /**
     * 将题目记录添加到 JSON 对象
     */
    private fun addQuestionRecordsToJson(
        json: JSONObject,
        questionAnswers: List<com.smartstudy.english.QuestionAnswer>,
    ): Int {
        val currentTime = System.currentTimeMillis()
        val dateStr = formatTimestamp(currentTime)

        var addedCount = 0
        questionAnswers.forEach { qa ->
            try {
                val question = qa.question
                val answer = qa.answer

                // 获取或创建该题目的记录数组
                val questionArray =
                    if (json.has(question)) {
                        json.getJSONArray(question)
                    } else {
                        JSONArray().also { json.put(question, it) }
                    }

                // 添加新记录（根据isCorrect字段设置状态，包含答案）
                val recordObj =
                    JSONObject().apply {
                        put("date", dateStr)
                        put("status", if (qa.isCorrect) "正确" else "错误")
                        if (answer.isNotEmpty()) {
                            put("answer", answer)
                        }
                        // 保存是否为大模型生成的题目
                        if (qa.isAiGenerated) {
                            put("isAiGenerated", true)
                        }
                    }
                questionArray.put(recordObj)
                addedCount++
            } catch (e: org.json.JSONException) {
                Logger.e(TAG, "添加题目记录失败: ${qa.question} - JSON格式错误", e)
                // 继续处理其他题目，不因为单个题目失败而中断整个保存过程
            } catch (e: IllegalArgumentException) {
                Logger.e(TAG, "添加题目记录失败: ${qa.question} - 参数错误", e)
                // 继续处理其他题目，不因为单个题目失败而中断整个保存过程
            } catch (e: Exception) {
                Logger.e(TAG, "添加题目记录失败: ${qa.question} - 未知错误", e)
                // 继续处理其他题目，不因为单个题目失败而中断整个保存过程
            }
        }
        return addedCount
    }

    /**
     * 验证添加的记录数量
     */
    private fun validateAddedCount(addedCount: Int, totalCount: Int) {
        if (addedCount == 0) {
            val errorMsg = "所有题目都添加失败，无法保存"
            Logger.e(TAG, errorMsg)
            throw IOException(errorMsg)
        }
    }
}
