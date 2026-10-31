package com.smartstudy.core

import android.content.Context

/**
 * AI提示词配置
 * 统一管理所有AI服务的提示词，便于维护和修改
 *
 * 提示词从配置文件读取（Documents/smartstudy/config/prompt.json）
 * 如果配置文件不存在，会自动创建并写入默认值
 * 如果无法读取配置文件，则使用硬编码的默认值作为fallback
 */
object PromptConfig {
    private var appContext: Context? = null
    private var configManager: PromptConfigManager? = null
    private var cachedConfig: PromptConfigManager.PromptConfigData? = null

    // 默认提示词常量
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

    /**
     * 初始化PromptConfig（在应用启动时调用）
     * @param context Application Context
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        configManager = PromptConfigManager(appContext!!)
        // 预加载配置
        cachedConfig = configManager?.readPromptConfig()
    }

    /**
     * 获取配置管理器（懒加载）
     */
    private fun getConfigManager(): PromptConfigManager? {
        val context = appContext ?: return null
        if (configManager == null) {
            configManager = PromptConfigManager(context)
        }
        return configManager
    }

    /**
     * 获取配置数据（带缓存）
     */
    private fun getConfigData(): PromptConfigManager.PromptConfigData {
        if (cachedConfig != null) {
            return cachedConfig!!
        }
        val manager = getConfigManager()
        cachedConfig = manager?.readPromptConfig() ?: getDefaultConfig()
        return cachedConfig!!
    }

    /**
     * 获取默认配置（硬编码值，作为fallback）
     */
    private fun getDefaultConfig(): PromptConfigManager.PromptConfigData {
        val context = appContext
        return if (context != null) {
            PromptConfigManager(context).getDefaultPromptConfig()
        } else {
            getHardcodedDefault()
        }
    }

    /**
     * 获取硬编码默认值（当无法获取Context时使用）
     */
    private fun getHardcodedDefault(): PromptConfigManager.PromptConfigData {
        return PromptConfigManager.PromptConfigData(
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

    /**
     * 错题识别提示词
     * 用于识别英语试卷中的题目并提供正确答案
     */
    val ERROR_BOOK_RECOGNITION: String
        get() = getConfigData().errorBookRecognition

    /**
     * 错题识别下划线提示词（第二阶段）
     * 用于识别英语题目中的下划线并标记
     */
    val ERROR_BOOK_UNDERLINE_RECOGNITION: String
        get() = getConfigData().errorBookUnderlineRecognition

    /**
     * 中文听写图片理解提示词
     * @param userTask 用户自定义的额外要求（可选）
     */
    fun getChineseDictationPrompt(userTask: String? = null): String {
        val extraRequirement = userTask?.takeIf { it.isNotBlank() }?.let { "额外要求：$it\n" } ?: ""
        val basePrompt = getConfigData().chineseDictationBase
        return """
            $basePrompt
            $extraRequirement
            输出要求（非常重要）：
            1) 仅输出 JSON 数组，例如 ["春天","认真学习"]；
            2) 只输出中文词语/短语（必须包含汉字），不要输出英文、数字、拼音、音标、解释、题目说明、页码、日期等；
            3) 去掉序号与标点符号（如 1. 、（ ） 等），不要输出空字符串；
            4) 保持词语原顺序（从上到下、从左到右），不要自行编造。
            """.trimIndent()
    }

    /**
     * 英文听写图片理解提示词
     * @param userTask 用户自定义的额外要求（可选）
     */
    fun getEnglishDictationPrompt(userTask: String? = null): String {
        val extraRequirement = userTask?.takeIf { it.isNotBlank() }?.let { "额外要求：$it\n" } ?: ""
        val basePrompt = getConfigData().englishDictationBase
        return """
            $basePrompt
            $extraRequirement
            输出要求（非常重要）：
            1) 仅输出 JSON 数组，例如 ["apple","take care"]；
            2) 只输出英文内容（A-Z/a-z，可含空格、连字符-、撇号'），不要输出中文、数字、音标(/ˈ.../)、释义、例句、题目说明、页码等；
            3) 去掉序号与标点符号（如 1. 、( ) 等），不要输出空字符串；
            4) 根据语义保持句子、短语的完整性，不要都拆成单词；
            5) 保持原顺序（从上到下、从左到右），不要自行编造。
            """.trimIndent()
    }

    /**
     * 通用图片理解提示词
     * @param userTask 用户自定义的任务描述
     */
    fun getGenericPrompt(userTask: String? = null): String {
        return userTask ?: getConfigData().genericBase
    }

    /**
     * 题目生成提示词（举一反三）
     * @param originalQuestion 原始题目
     * @param originalAnswer 原始答案（可选）
     */
    fun getQuestionGenerationPrompt(
        originalQuestion: String,
        originalAnswer: String? = null,
    ): String {
        val answerContext = originalAnswer?.takeIf { it.isNotEmpty() }?.let { "\n原题答案：$it" } ?: ""
        val basePrompt = getConfigData().questionGenerationBase
        return """
            $basePrompt

            原始题目：$originalQuestion$answerContext
            """.trimIndent()
    }

    /**
     * 题目生成系统消息
     */
    fun getQuestionGenerationSystemMessage(): String {
        return getConfigData().questionGenerationSystem
    }

    /**
     * 翻译提示词
     * @param text 要翻译的英文文本
     */
    fun getTranslationPrompt(text: String): String {
        val basePrompt = getConfigData().translationBase
        return """
            $basePrompt
            英文：$text
            """.trimIndent()
    }
}
