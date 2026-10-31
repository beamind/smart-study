package com.smartstudy.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.smartstudy.storage.AppStorageManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Prompt配置管理器
 * 负责从配置文件读取和管理所有AI服务的提示词
 *
 * 功能特性：
 * - 使用SAF存储方式读取配置文件
 * - 如果配置文件不存在，自动创建并写入默认prompt
 * - 提供线程安全的读取方法
 * - 支持fallback到硬编码默认值
 *
 * 配置文件位置：Documents/smartstudy/config/prompt.json
 *
 * 使用示例：
 * ```
 * val manager = PromptConfigManager(context)
 * val prompt = manager.getErrorBookRecognition()
 * ```
 *
 * @param context Android Context，用于访问存储
 */
class PromptConfigManager(private val context: Context) {
    private val resolver = context.contentResolver
    private val fileName = "prompt.json"
    private val subFolderName = "config"

    /**
     * Prompt配置数据类
     */
    data class PromptConfigData(
        val errorBookRecognition: String,
        val errorBookUnderlineRecognition: String,
        val chineseDictationBase: String,
        val englishDictationBase: String,
        val genericBase: String,
        val questionGenerationBase: String,
        val questionGenerationSystem: String,
        val translationBase: String,
    )

    companion object {
        private const val TAG = "PromptConfigManager"

        // 默认提示词常量（与 PromptConfig 中的常量保持一致）
        private val ERROR_BOOK_RECOGNITION_DEFAULT = """
            你是一个图片识别助手，专门识别英语试卷中的题目，并提供正确答案。
            任务：从图片中识别出所有题目文本，并为每道题目提供正确答案。

            要求：
            1) 识别图片中的所有题目文本，去除题号，保留完整的题目内容；
            2) 重要：题目必须包含所有选项！如果题目有选项（如A、B、C、D或A.、B.、C.、D.等），必须完整识别题干和所有选项，不能只识别题干部分；
            3) 保持题目的完整性，包括题干、选项、题目说明等所有内容；
            4) 保持原文本的格式和换行，使用\n表示换行；
            5) 如果图片中有多个题目，请保留所有题目；
            6) 重要：答案不要从照片中提取！照片中的答案可能是小朋友的错误答案。答案应该由你根据题目内容给出正确的标准答案；
            7) 为每道题目提供准确、完整的正确答案；
            8) 只输出识别到的内容，不要添加任何解释或说明；
            9) 如果图片中没有题目内容，输出空数组[]；
            10) 非常重要：必须原样识别题目内容，不能修改、改写、优化、翻译或改变题目的任何文字、语法、拼写、标点符号等。题目内容必须与图片中的内容完全一致，只去除题号即可。

            输出格式：输出JSON数组，每个元素是一个对象，包含question和answer两个字段。
            例如：[{"question": "What is your name?\nA. Tom\nB. John\nC. Mike\nD. David", "answer": "A"}, {"question": "How old are you?\nA. 18\nB. 19\nC. 20\nD. 21", "answer": "C"}]
            """.trimIndent()

        private val ERROR_BOOK_UNDERLINE_RECOGNITION_DEFAULT = """
            你是一个图片识别助手，专门识别英语题目中的下划线。
            任务：对照图片和提供的文本，识别图片中加了下划线的字母，并在文本中标记出来。

            要求：
            1) 提供的文本中，所有字符之间已经插入了空格，这是为了帮助你更精确地识别字符位置；
            2) 仔细对照图片，找出图片中哪些字母下面有下划线；
            3) 在文本中对应位置，使用Markdown格式（两个下划线__）标记这些字母，例如：h__e__ll__o__表示e和o下面有下划线；
            4) 保持文本的其他内容不变，只添加下划线标记；
            5) 输出格式与第一阶段相同：JSON数组，每个元素包含question和answer两个字段；
            6) question字段应该包含带下划线标记的完整题目文本，answer字段保持与第一阶段相同。

            输出格式：输出JSON数组，每个元素是一个对象，包含question和answer两个字段。
            例如：[{"question": "What is your n__a__me?\nA. Tom\nB. John\nC. Mike\nD. David", "answer": "A"}]
            """.trimIndent()

        private val CHINESE_DICTATION_BASE_DEFAULT = """
            你是一个图片理解助手，专门为"小学生中文听写"从图片中提取词语。
            任务：从图片中提取"中文词语/短语"（用于听写）。
            """.trimIndent()

        private val ENGLISH_DICTATION_BASE_DEFAULT = """
            你是一个图片理解助手，专门为"英语听写"从图片中提取英文单词/短语/句子。
            任务：从图片中提取"英文单词/短语/句子"（用于听写）。
            """.trimIndent()

        private val GENERIC_BASE_DEFAULT = "你是一个图片理解助手。"

        private val QUESTION_GENERATION_BASE_DEFAULT = """
            任务：根据给定的原始题目，生成一道考察相同知识点的相似题目。

            要求：
            1. 分析原始题目考察的知识点（如：语法、词汇、时态、句型等）
            2. 生成一道新的题目，必须考察相同的知识点
            3. 新题目的难度应该与原始题目相近，适合小学生水平
            4. **重要：新题目必须与原题有明显差异，具体要求：**
               - 必须改变题目场景、人物、情境或主题（例如：从学校场景改为家庭场景，从Tom改为Lucy等）
               - 必须改变题目中的具体内容、词汇和表达方式
               - 可以改变题目形式（例如：改变选项顺序、改变选项内容、改变题目问法）
               - 禁止直接复制原题的句子结构或只做简单的单词替换
               - 新题目应该让学习者感觉是在做一道全新的题目，而不是原题的变体
            5. 必须提供正确答案

            输出格式：只输出JSON对象，格式如下：
            {"question": "新题目的完整内容（包括题干和所有选项，如果有选项的话）", "answer": "正确答案"}

            注意：
            - 只输出JSON对象，不要添加任何解释、说明或其他文字
            - 题目内容要完整，如果原题有选项（A、B、C、D等），新题也要有选项
            - 答案要准确、简洁
            - **确保新题目与原题在内容上有明显区别，不能过于相似**
            """.trimIndent()

        private val QUESTION_GENERATION_SYSTEM_DEFAULT = "你是一个英语教育专家，专门为小学生生成英语练习题。你的任务是生成与原题考察相同知识点但内容有明显差异的新题目，确保新题目在场景、人物、情境、表达方式等方面都有变化，避免与原题过于相似。"

        private val TRANSLATION_BASE_DEFAULT = """
            你是一个翻译助手。
            请把下面英文翻译成中文。
            输出要求：只输出译文，不要解释，不要加引号，不要添加任何额外文字。
            """.trimIndent()
    }

    /**
     * 读取Prompt配置
     * 如果文件不存在，自动创建并写入默认值
     */
    fun readPromptConfig(): PromptConfigData {
        val treeUri = getAppStorageTreeUriOrNull()
        if (treeUri == null) {
            Logger.w(TAG, "SAF 权限未授权，使用默认prompt")
            return getDefaultPromptConfig()
        }

        return runCatching {
            readPromptConfigViaSaf(treeUri)
        }.getOrElse { exception ->
            Logger.e(TAG, "SAF 读取prompt配置失败，使用默认值", exception)
            // 如果读取失败，尝试创建默认配置文件
            tryCreateDefaultConfigFile(treeUri)
            getDefaultPromptConfig()
        }
    }

    /**
     * 获取应用存储文件夹的 treeUri（如果已授权）
     */
    private fun getAppStorageTreeUriOrNull(): Uri? {
        return AppStorageManager.getAppStorageTreeUri(context)
    }

    /**
     * 通过 SAF 读取prompt配置
     */
    private fun readPromptConfigViaSaf(treeUri: Uri): PromptConfigData {
        val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return getDefaultPromptConfig()
        var subFolder = rootFolder.findFile(subFolderName)

        // 如果config文件夹不存在，创建它
        if (subFolder == null || !subFolder.isDirectory) {
            subFolder = rootFolder.createDirectory(subFolderName)
            if (subFolder == null || !subFolder.isDirectory) {
                Logger.w(TAG, "无法创建config文件夹，使用默认prompt")
                return getDefaultPromptConfig()
            }
        }

        val file = subFolder.findFile(fileName)

        // 如果文件不存在，创建并写入默认值
        if (file == null) {
            Logger.d(TAG, "prompt.json文件不存在，创建默认配置文件")
            return createDefaultConfigFile(subFolder)
        }

        return try {
            val jsonString =
                resolver.openInputStream(file.uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, "UTF-8")).use { reader ->
                        reader.readText()
                    }
                } ?: return getDefaultPromptConfig()

            val jsonObj = JSONObject(jsonString)

            PromptConfigData(
                errorBookRecognition = jsonObj.optString("errorBookRecognition", getDefaultPromptConfig().errorBookRecognition),
                errorBookUnderlineRecognition = jsonObj.optString("errorBookUnderlineRecognition", getDefaultPromptConfig().errorBookUnderlineRecognition),
                chineseDictationBase = jsonObj.optString("chineseDictationBase", getDefaultPromptConfig().chineseDictationBase),
                englishDictationBase = jsonObj.optString("englishDictationBase", getDefaultPromptConfig().englishDictationBase),
                genericBase = jsonObj.optString("genericBase", getDefaultPromptConfig().genericBase),
                questionGenerationBase = jsonObj.optString("questionGenerationBase", getDefaultPromptConfig().questionGenerationBase),
                questionGenerationSystem = jsonObj.optString("questionGenerationSystem", getDefaultPromptConfig().questionGenerationSystem),
                translationBase = jsonObj.optString("translationBase", getDefaultPromptConfig().translationBase),
            )
        } catch (e: org.json.JSONException) {
            Logger.e(TAG, "JSON解析错误，通过 SAF 读取prompt配置失败", e)
            getDefaultPromptConfig()
        } catch (e: IOException) {
            Logger.e(TAG, "IO错误，通过 SAF 读取prompt配置失败", e)
            getDefaultPromptConfig()
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，通过 SAF 读取prompt配置失败", e)
            getDefaultPromptConfig()
        }
    }

    /**
     * 创建默认配置文件
     */
    private fun createDefaultConfigFile(subFolder: DocumentFile): PromptConfigData {
        val defaultConfig = getDefaultPromptConfig()
        val jsonObj =
            JSONObject().apply {
                put("errorBookRecognition", defaultConfig.errorBookRecognition)
                put("errorBookUnderlineRecognition", defaultConfig.errorBookUnderlineRecognition)
                put("chineseDictationBase", defaultConfig.chineseDictationBase)
                put("englishDictationBase", defaultConfig.englishDictationBase)
                put("genericBase", defaultConfig.genericBase)
                put("questionGenerationBase", defaultConfig.questionGenerationBase)
                put("questionGenerationSystem", defaultConfig.questionGenerationSystem)
                put("translationBase", defaultConfig.translationBase)
            }

        val jsonString = jsonObj.toString(2) // 使用缩进格式化，便于用户编辑

        try {
            val file =
                subFolder.createFile("application/json", fileName)
                    ?: throw IOException("无法创建prompt.json文件")

            resolver.openOutputStream(file.uri, "wt")?.use { output ->
                OutputStreamWriter(output, "UTF-8").use { writer ->
                    writer.write(jsonString)
                    writer.flush()
                }
            } ?: throw IOException("无法打开输出流")

            Logger.d(TAG, "已创建默认prompt.json配置文件")
        } catch (e: Exception) {
            Logger.e(TAG, "创建默认配置文件失败", e)
        }

        return defaultConfig
    }

    /**
     * 尝试创建默认配置文件（当读取失败时）
     */
    private fun tryCreateDefaultConfigFile(treeUri: Uri) {
        try {
            val rootFolder = DocumentFile.fromTreeUri(context, treeUri) ?: return
            var subFolder = rootFolder.findFile(subFolderName)

            if (subFolder == null || !subFolder.isDirectory) {
                subFolder = rootFolder.createDirectory(subFolderName) ?: return
            }

            val file = subFolder.findFile(fileName)
            if (file == null) {
                createDefaultConfigFile(subFolder)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "尝试创建默认配置文件失败", e)
        }
    }

    /**
     * 获取默认prompt配置（从PromptConfig中提取）
     */
    fun getDefaultPromptConfig(): PromptConfigData {
        // 从PromptConfig中提取默认值
        // 注意：这里需要访问PromptConfig的私有成员，所以我们需要将默认值提取出来
        return PromptConfigData(
            errorBookRecognition = ERROR_BOOK_RECOGNITION_DEFAULT,
            errorBookUnderlineRecognition = ERROR_BOOK_UNDERLINE_RECOGNITION_DEFAULT,
            chineseDictationBase = CHINESE_DICTATION_BASE_DEFAULT,
            englishDictationBase = ENGLISH_DICTATION_BASE_DEFAULT,
            genericBase = GENERIC_BASE_DEFAULT,
            questionGenerationBase = QUESTION_GENERATION_BASE_DEFAULT,
            questionGenerationSystem = QUESTION_GENERATION_SYSTEM_DEFAULT,
            translationBase = TRANSLATION_BASE_DEFAULT,
        )
    }
}
