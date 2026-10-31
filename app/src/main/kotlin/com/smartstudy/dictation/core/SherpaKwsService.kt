package com.smartstudy.dictation.core

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.smartstudy.BuildConfig
import com.smartstudy.R
import com.smartstudy.core.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 基于 Sherpa-onnx 官方库的关键词检测服务
 *
 * 使用官方 JNI 绑定进行关键词检测，支持中文和英文关键词。
 * 模型文件从 assets 目录自动复制到内部存储，支持两种监听模式：
 * - DICTATION: 听写关键词（"下一个"、"写好了"等）
 * - START: 开始关键词（"开始"、"开始听写"）
 */
class SherpaKwsService(private val context: Context) {
    companion object {
        private const val TAG = "SherpaKwsService"
        private const val SAMPLE_RATE = 16000 // 16kHz 采样率
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO // 单声道
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT // 16位 PCM
        private const val INTERVAL_MS = 100 // 100ms 音频缓冲区（参考官方示例）
        private const val BUFFER_SIZE = (INTERVAL_MS * SAMPLE_RATE / 1000.0).toInt() // 1600 samples

        // 听写关键词列表
        private val DICTATION_KEYWORDS = setOf("下一个", "下一题", "下一道", "写好了")

        // 开始关键词列表
        private val START_KEYWORDS = setOf("开始", "开始听写")

        // 模型文件名称（从 assets 加载）
        private const val MODEL_DIR = "models"
        private const val ENCODER_MODEL = "encoder-epoch-99-avg-1-chunk-16-left-64.onnx"
        private const val DECODER_MODEL = "decoder-epoch-99-avg-1-chunk-16-left-64.onnx"
        private const val JOINER_MODEL = "joiner-epoch-99-avg-1-chunk-16-left-64.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val KEYWORDS_FILE = "keywords.txt"
    }

    enum class ListeningMode {
        DICTATION, // 听写关键词模式
        START, // 开始关键词模式
    }

    private var keywordSpotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var callback: KeywordCallback? = null
    private var listeningMode: ListeningMode = ListeningMode.DICTATION
    private var recordingThread: Thread? = null

    // 模型文件路径（由用户设置或使用默认路径）
    private var encoderPath: String? = null
    private var decoderPath: String? = null
    private var joinerPath: String? = null
    private var tokensPath: String? = null
    private var keywordsPath: String? = null

    // 中文关键词到拼音的映射（从 keywords.txt 解析）
    private val keywordToPinyin = mutableMapOf<String, String>()

    /**
     * 设置模型文件路径
     * @param encoderPath encoder 模型文件路径
     * @param decoderPath decoder 模型文件路径
     * @param joinerPath joiner 模型文件路径
     * @param tokensPath tokens 文件路径
     * @param keywordsPath keywords 文件路径
     */
    fun setModelPaths(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        keywordsPath: String,
    ) {
        this.encoderPath = encoderPath
        this.decoderPath = decoderPath
        this.joinerPath = joinerPath
        this.tokensPath = tokensPath
        this.keywordsPath = keywordsPath
    }

    /**
     * 初始化 KWS 模型
     * @return 是否初始化成功
     */
    fun initialize(): Boolean {
        return try {
            if (!setupModelPathsIfNeeded()) {
                return false
            }

            if (!validateModelFiles()) {
                Logger.e(TAG, "模型文件验证失败，无法初始化")
                return false
            }

            val modelPaths = validateAndGetModelPaths() ?: return false
            val modelConfig = createModelConfigs(modelPaths)
            if (!createKeywordSpotter(modelConfig, modelPaths.encoderPath)) {
                return false
            }

            // 解析 keywords.txt 文件，建立中文关键词到拼音的映射
            parseKeywordsFile()

            Logger.i(TAG, "Sherpa-onnx KWS 模型初始化成功")
            true
        } catch (e: IOException) {
            handleInitializationError(e, context.getString(R.string.error_io))
        } catch (e: IllegalStateException) {
            handleInitializationError(e, context.getString(R.string.error_state))
        } catch (e: IllegalArgumentException) {
            handleInitializationError(e, context.getString(R.string.error_argument))
        }
    }

    /**
     * 模型路径数据类
     */
    private data class ModelPaths(
        val encoderPath: String,
        val decoderPath: String,
        val joinerPath: String,
        val tokensPath: String,
        val keywordsPath: String,
    )

    /**
     * 如果需要，设置模型路径
     * @return true 如果成功，false 如果失败
     */
    private fun setupModelPathsIfNeeded(): Boolean {
        // 如果模型路径未设置，从 assets 自动加载
        if (areModelPathsIncomplete()) {
            val modelDir = copyModelsToInternalStorage()
            if (modelDir == null) {
                Logger.e(TAG, "模型文件复制失败")
                return false
            }

            // 设置模型路径
            encoderPath = File(modelDir, ENCODER_MODEL).absolutePath
            decoderPath = File(modelDir, DECODER_MODEL).absolutePath
            joinerPath = File(modelDir, JOINER_MODEL).absolutePath
            tokensPath = File(modelDir, TOKENS_FILE).absolutePath
            keywordsPath = File(modelDir, KEYWORDS_FILE).absolutePath
        }
        return true
    }

    /**
     * 检查模型路径是否不完整
     */
    private fun areModelPathsIncomplete(): Boolean {
        if (encoderPath == null) return true
        if (decoderPath == null) return true
        if (joinerPath == null) return true
        if (tokensPath == null) return true
        if (keywordsPath == null) return true
        return false
    }

    /**
     * 验证并获取模型路径
     * @return ModelPaths 如果成功，null 如果失败
     */
    private fun validateAndGetModelPaths(): ModelPaths? {
        val encoderPathValue =
            encoderPath ?: run {
                Logger.e(TAG, "encoderPath 未设置，无法创建 OnlineTransducerModelConfig")
                return null
            }
        val decoderPathValue =
            decoderPath ?: run {
                Logger.e(TAG, "decoderPath 未设置，无法创建 OnlineTransducerModelConfig")
                return null
            }
        val joinerPathValue =
            joinerPath ?: run {
                Logger.e(TAG, "joinerPath 未设置，无法创建 OnlineTransducerModelConfig")
                return null
            }
        val tokensPathValue =
            tokensPath ?: run {
                Logger.e(TAG, "tokensPath 未设置，无法创建 OnlineModelConfig")
                return null
            }
        val keywordsPathValue =
            keywordsPath ?: run {
                Logger.e(TAG, "keywordsPath 未设置，无法创建 KeywordSpotter")
                return null
            }

        return ModelPaths(
            encoderPath = encoderPathValue,
            decoderPath = decoderPathValue,
            joinerPath = joinerPathValue,
            tokensPath = tokensPathValue,
            keywordsPath = keywordsPathValue,
        )
    }

    /**
     * 创建模型配置
     * @param modelPaths 模型路径
     * @return KeywordSpotterConfig
     */
    private fun createModelConfigs(modelPaths: ModelPaths): KeywordSpotterConfig {
        // 创建 OnlineTransducerModelConfig（按位置传递参数）
        val transducerConfig =
            OnlineTransducerModelConfig(
                modelPaths.encoderPath,
                modelPaths.decoderPath,
                modelPaths.joinerPath,
            )

        // 创建 FeatureConfig（按位置传递参数，避免 JNI 字段名不匹配）
        val featConfig =
            FeatureConfig(
                // sampleRate
                SAMPLE_RATE,
                // featureDim
                80,
            )

        // 创建空的配置对象（不使用但必须提供）
        val emptyParaformer = OnlineParaformerModelConfig("", "")
        val emptyZipformer = OnlineZipformer2CtcModelConfig("")
        val emptyNeMo = OnlineNeMoCtcModelConfig("")
        val emptyTone = OnlineToneCtcModelConfig("")

        // 创建 OnlineModelConfig（按位置传递参数）
        val onlineModelConfig =
            OnlineModelConfig(
                // OnlineTransducerModelConfig
                transducerConfig,
                // OnlineParaformerModelConfig
                emptyParaformer,
                // OnlineZipformer2CtcModelConfig
                emptyZipformer,
                // OnlineNeMoCtcModelConfig
                emptyNeMo,
                // OnlineToneCtcModelConfig
                emptyTone,
                // tokens
                modelPaths.tokensPath,
                // numThreads
                2,
                // debug
                true,
                // provider
                "cpu",
                // modelType
                "",
                // modelingUnit
                "",
                // bpeVocab
                "",
            )

        // 创建 KeywordSpotterConfig
        return KeywordSpotterConfig(
            featConfig = featConfig,
            modelConfig = onlineModelConfig,
            keywordsFile = modelPaths.keywordsPath,
        )
    }

    /**
     * 创建 KeywordSpotter
     * @param modelConfig 模型配置
     * @param encoderPathValue encoder 路径（用于判断是否为文件系统路径）
     * @return true 如果成功，false 如果失败
     */
    private fun createKeywordSpotter(
        modelConfig: KeywordSpotterConfig,
        encoderPathValue: String,
    ): Boolean {
        val isFileSystemPath = encoderPathValue.startsWith("/")

        keywordSpotter =
            if (isFileSystemPath) {
                KeywordSpotter(
                    assetManager = null,
                    config = modelConfig,
                )
            } else {
                KeywordSpotter(
                    assetManager = context.assets,
                    config = modelConfig,
                )
            }

        if (keywordSpotter == null) {
            Logger.e(TAG, "KeywordSpotter 创建失败，返回 null")
            return false
        }

        return true
    }

    /**
     * 处理初始化错误
     * @param e 异常对象
     * @param errorType 错误类型描述
     * @return false（表示初始化失败）
     */
    private fun handleInitializationError(
        e: Exception,
        errorType: String,
    ): Boolean {
        Logger.e(TAG, "$errorType，初始化 KWS 模型失败: ${e.message}", e)
        // 清理可能部分初始化的资源
        keywordSpotter = null
        return false
    }

    /**
     * 从 assets 复制模型文件到内部存储
     */
    private fun copyModelsToInternalStorage(): File? {
        return try {
            val modelDir = createModelDirectory() ?: return null

            val modelFiles = createModelFileReferences(modelDir)

            if (!checkAssetsFiles()) {
                return null
            }

            // 如果所有文件都存在且大小正常，跳过复制
            if (checkExistingModelFiles(modelFiles)) {
                return modelDir
            }

            // 从 assets 复制文件
            copyAllModelFiles(modelFiles)

            // 验证复制后的文件
            if (!validateCopiedFiles(modelFiles)) {
                Logger.e(TAG, "复制后的文件验证失败")
                return null
            }

            modelDir
        } catch (e: IOException) {
            Logger.e(TAG, "IO错误，复制模型文件失败: ${e.message}", e)
            null
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，复制模型文件失败: ${e.message}", e)
            null
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，复制模型文件失败: ${e.message}", e)
            null
        }
    }

    /**
     * 模型文件引用数据类
     */
    private data class ModelFiles(
        val encoderFile: File,
        val decoderFile: File,
        val joinerFile: File,
        val tokensFile: File,
        val keywordsFile: File,
    )

    /**
     * 创建模型目录
     * @return 模型目录，如果创建失败则返回null
     */
    private fun createModelDirectory(): File? {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) {
            val created = modelDir.mkdirs()
            if (!created) {
                Logger.e(TAG, "无法创建模型目录: ${modelDir.absolutePath}")
                return null
            }
        }
        return modelDir
    }

    /**
     * 创建模型文件引用
     * @param modelDir 模型目录
     * @return 模型文件引用
     */
    private fun createModelFileReferences(modelDir: File): ModelFiles {
        return ModelFiles(
            encoderFile = File(modelDir, ENCODER_MODEL),
            decoderFile = File(modelDir, DECODER_MODEL),
            joinerFile = File(modelDir, JOINER_MODEL),
            tokensFile = File(modelDir, TOKENS_FILE),
            keywordsFile = File(modelDir, KEYWORDS_FILE),
        )
    }

    /**
     * 检查 assets 中是否存在所需的文件
     * @return true 如果所有文件都存在，false 如果缺少文件
     */
    private fun checkAssetsFiles(): Boolean {
        val assets = context.assets
        val assetFiles =
            try {
                assets.list("")?.toSet() ?: emptySet()
            } catch (e: IOException) {
                Logger.e(TAG, "IO错误，无法列出 assets 文件", e)
                emptySet()
            }

        val requiredFiles = listOf(ENCODER_MODEL, DECODER_MODEL, JOINER_MODEL, TOKENS_FILE, KEYWORDS_FILE)
        val missingAssets = requiredFiles.filter { it !in assetFiles }
        if (missingAssets.isNotEmpty()) {
            Logger.e(TAG, "assets 中缺少以下文件: ${missingAssets.joinToString(", ")}")
            Logger.e(TAG, "assets 中可用的文件: ${assetFiles.take(20).joinToString(", ")}")
            return false
        }

        return true
    }

    /**
     * 检查已存在的模型文件
     * @param modelFiles 模型文件引用
     * @return true 如果所有文件都存在且有效，false 如果需要重新复制
     */
    private fun checkExistingModelFiles(modelFiles: ModelFiles): Boolean {
        if (areAllModelFilesExist(modelFiles)) {
            // 验证文件大小（ONNX 模型文件通常至少有几 KB）
            val allValid =
                listOf(modelFiles.encoderFile, modelFiles.decoderFile, modelFiles.joinerFile).all { file ->
                    val size = file.length()
                    if (size == 0L) {
                        Logger.w(TAG, "文件大小为 0，需要重新复制: ${file.name}")
                        false
                    } else {
                        true
                    }
                }

            if (allValid) {
                return true
            } else {
                Logger.w(TAG, "部分文件无效，将重新复制")
            }
        }
        return false
    }

    /**
     * 检查所有模型文件是否存在
     */
    private fun areAllModelFilesExist(modelFiles: ModelFiles): Boolean {
        if (!modelFiles.encoderFile.exists()) return false
        if (!modelFiles.decoderFile.exists()) return false
        if (!modelFiles.joinerFile.exists()) return false
        if (!modelFiles.tokensFile.exists()) return false
        if (!modelFiles.keywordsFile.exists()) return false
        return true
    }

    /**
     * 复制所有模型文件
     * @param modelFiles 模型文件引用
     */
    private fun copyAllModelFiles(modelFiles: ModelFiles) {
        val assets = context.assets
        // 注意：模型文件在 assets 根目录，不在 models 子目录
        copyAssetFile(assets, ENCODER_MODEL, modelFiles.encoderFile)
        copyAssetFile(assets, DECODER_MODEL, modelFiles.decoderFile)
        copyAssetFile(assets, JOINER_MODEL, modelFiles.joinerFile)
        copyAssetFile(assets, TOKENS_FILE, modelFiles.tokensFile)
        copyAssetFile(assets, KEYWORDS_FILE, modelFiles.keywordsFile)
    }

    /**
     * 验证复制后的文件
     * @param modelFiles 模型文件引用
     * @return true 如果所有文件都有效，false 如果有文件无效
     */
    private fun validateCopiedFiles(modelFiles: ModelFiles): Boolean {
        val allFilesValid =
            listOf(
                modelFiles.encoderFile to "encoder",
                modelFiles.decoderFile to "decoder",
                modelFiles.joinerFile to "joiner",
                modelFiles.tokensFile to "tokens",
                modelFiles.keywordsFile to "keywords",
            ).all { (file, name) ->
                if (!file.exists()) {
                    Logger.e(TAG, "$name 文件不存在: ${file.absolutePath}")
                    false
                } else if (!file.canRead()) {
                    Logger.e(TAG, "$name 文件不可读: ${file.absolutePath}")
                    false
                } else if (file.length() == 0L) {
                    Logger.e(TAG, "$name 文件大小为 0: ${file.absolutePath}")
                    false
                } else {
                    true
                }
            }

        return allFilesValid
    }

    /**
     * 验证模型文件是否存在且可读
     */
    private fun validateModelFiles(): Boolean {
        val files =
            mapOf(
                "encoder" to encoderPath,
                "decoder" to decoderPath,
                "joiner" to joinerPath,
                "tokens" to tokensPath,
                "keywords" to keywordsPath,
            )

        for ((name, path) in files) {
            if (path == null) {
                Logger.e(TAG, "$name 文件路径为 null")
                return false
            }

            val file = File(path)
            if (!file.exists()) {
                Logger.e(TAG, "$name 文件不存在: $path")
                return false
            }

            if (!file.canRead()) {
                Logger.e(TAG, "$name 文件不可读: $path")
                return false
            }

            val size = file.length()
            if (size == 0L) {
                Logger.e(TAG, "$name 文件大小为 0: $path")
                return false
            }

            // ONNX 模型文件通常至少有几 KB
            if (name in listOf("encoder", "decoder", "joiner") && size < 1024) {
                Logger.w(TAG, "$name 文件大小异常小: $path, 大小: $size 字节")
            }
        }

        return true
    }

    /**
     * 解析 keywords.txt 文件，建立中文关键词到拼音的映射
     * 文件格式：拼音 @中文关键词
     * 例如：k āi sh ǐ @开始
     */
    private fun parseKeywordsFile() {
        keywordToPinyin.clear()

        val keywordsFile = validateKeywordsFile() ?: return

        try {
            parseKeywordsFromFile(keywordsFile)
            Logger.i(TAG, "成功解析 ${keywordToPinyin.size} 个关键词映射")
        } catch (e: IOException) {
            Logger.e(TAG, "IO错误，解析 keywords.txt 失败", e)
        } catch (e: java.nio.charset.CharacterCodingException) {
            Logger.e(TAG, "字符编码错误，解析 keywords.txt 失败", e)
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，解析 keywords.txt 失败", e)
        }
    }

    /**
     * 验证 keywords.txt 文件
     */
    private fun validateKeywordsFile(): File? {
        val keywordsFile = keywordsPath?.let { File(it) }
        if (keywordsFile == null || !keywordsFile.exists() || !keywordsFile.canRead()) {
            Logger.e(TAG, "keywords.txt 文件不存在或不可读: $keywordsPath")
            return null
        }
        return keywordsFile
    }

    /**
     * 从文件中解析关键词
     */
    private fun parseKeywordsFromFile(keywordsFile: File) {
        keywordsFile.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                parseKeywordLine(line)
            }
        }
    }

    /**
     * 解析单行关键词
     */
    private fun parseKeywordLine(line: String) {
        val trimmedLine = line.trim()
        if (trimmedLine.isEmpty() || !trimmedLine.contains("@")) {
            return
        }

        // 解析格式：拼音 @中文关键词
        val parts = trimmedLine.split("@", limit = 2)
        if (parts.size == 2) {
            val pinyin = parts[0].trim()
            val chineseKeyword = parts[1].trim()

            if (pinyin.isNotBlank() && chineseKeyword.isNotBlank()) {
                keywordToPinyin[chineseKeyword] = pinyin
            }
        }
    }

    /**
     * 复制单个 asset 文件
     */
    private fun copyAssetFile(
        assets: AssetManager,
        assetPath: String,
        targetFile: File,
    ) {
        if (targetFile.exists()) {
            val existingSize = targetFile.length()
            if (existingSize > 0) {
                return
            } else {
                Logger.w(TAG, "文件存在但大小为 0，将重新复制: ${targetFile.absolutePath}")
                targetFile.delete()
            }
        }

        try {
            // 检查 asset 文件是否存在
            val inputStream =
                try {
                    assets.open(assetPath)
                } catch (e: java.io.FileNotFoundException) {
                    Logger.e(TAG, "Asset 文件不存在: $assetPath", e)
                    throw IOException("Asset 文件不存在: $assetPath", e)
                } catch (e: IOException) {
                    Logger.e(TAG, "IO错误，无法打开 asset 文件: $assetPath", e)
                    throw IOException("无法打开 asset 文件: $assetPath", e)
                }

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 验证复制后的文件
            if (!targetFile.exists() || targetFile.length() == 0L) {
                throw IllegalStateException("复制后文件验证失败: ${targetFile.absolutePath}, 大小: ${targetFile.length()}")
            }
        } catch (e: IOException) {
            Logger.e(TAG, "IO错误，复制文件失败: $assetPath -> ${targetFile.absolutePath}", e)
            // 如果复制失败，删除可能损坏的文件
            if (targetFile.exists()) {
                try {
                    targetFile.delete()
                } catch (e2: SecurityException) {
                    Logger.w(TAG, "无法删除损坏的文件", e2)
                }
            }
            throw e // 重新抛出异常，让调用者知道复制失败
        }
    }

    /**
     * 开始监听关键词
     */
    fun startListening(
        callback: KeywordCallback,
        mode: ListeningMode,
    ) {
        if (keywordSpotter == null) {
            callback.onError(context.getString(R.string.kws_model_not_initialized))
            return
        }

        this.callback = callback
        this.listeningMode = mode

        // 根据监听模式获取关键词列表
        val keywordList = getKeywordsForMode(mode)
        if (keywordList.isEmpty()) {
            callback.onError(context.getString(R.string.kws_no_keywords))
            return
        }

        // 创建音频流
        val streamResult = createAudioStream(keywordList)
        if (streamResult == null) {
            callback.onError(context.getString(R.string.kws_cannot_create_stream, keywordList.joinToString(", ")))
            return
        }

        stream = streamResult.stream
        Logger.i(TAG, "使用关键词 \"${streamResult.keyword}\" 的 stream 开始监听，可识别关键词列表: ${keywordList.joinToString(", ")}")

        // 初始化并启动录音
        if (!initializeAndStartRecording(callback)) {
            releaseStreamSafely()
            return
        }
    }

    /**
     * 根据监听模式获取关键词列表
     */
    private fun getKeywordsForMode(mode: ListeningMode): List<String> {
        return when (mode) {
            ListeningMode.DICTATION -> DICTATION_KEYWORDS.toList()
            ListeningMode.START -> START_KEYWORDS.toList()
        }
    }

    /**
     * 创建音频流的结果
     */
    private data class StreamResult(
        val stream: OnlineStream,
        val keyword: String,
    )

    /**
     * 为关键词列表创建音频流
     * @param keywordList 关键词列表
     * @return StreamResult 如果成功，null 如果失败
     */
    private fun createAudioStream(keywordList: List<String>): StreamResult? {
        val kws = keywordSpotter ?: return null

        // 对关键词列表进行排序，优先尝试"下一个"
        val sortedKeywordList = keywordList.sortedBy { if (it == "下一个") 0 else 1 }

        for (keyword in sortedKeywordList) {
            val pinyin = keywordToPinyin[keyword] ?: keyword
            // 只处理有有效拼音的关键词
            if (pinyin.isNotBlank()) {
                val stream = tryCreateStream(kws, keyword, pinyin)
                if (stream != null) {
                    return StreamResult(stream, keyword)
                }
            } else {
                Logger.w(TAG, "关键词 \"$keyword\" 没有对应的拼音映射，跳过")
            }
        }

        Logger.e(TAG, "所有关键词都无法创建 stream: $keywordList")
        return null
    }

    /**
     * 尝试为单个关键词创建stream
     * @param kws 关键词检测器
     * @param keyword 关键词
     * @param pinyin 拼音
     * @return OnlineStream 如果成功，null 如果失败
     */
    private fun tryCreateStream(
        kws: KeywordSpotter,
        keyword: String,
        pinyin: String,
    ): OnlineStream? {
        return try {
            if (BuildConfig.DEBUG) {
                Logger.d(TAG, "尝试为关键词 \"$keyword\" (拼音: \"$pinyin\") 创建 stream")
            }
            val newStream = kws.createStream(pinyin)

            if (newStream.ptr != 0L) {
                Logger.i(TAG, "成功为关键词 \"$keyword\" 创建 stream")
                newStream
            } else {
                Logger.w(TAG, "关键词 \"$keyword\" 创建的 stream 无效")
                null
            }
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "状态错误，为关键词 \"$keyword\" 创建 stream 失败: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "参数错误，为关键词 \"$keyword\" 创建 stream 失败: ${e.message}")
            null
        }
    }

    /**
     * 初始化并启动录音
     * @param callback 回调接口
     * @return true 如果成功，false 如果失败
     */
    private fun initializeAndStartRecording(
        callback: KeywordCallback,
    ): Boolean {
        val bufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
            )

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            callback.onError(context.getString(R.string.kws_cannot_init_recorder))
            return false
        }

        return try {
            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2,
                )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val state = audioRecord?.state ?: "null"
                Logger.e(TAG, "录音器初始化失败，状态: $state")
                callback.onError(context.getString(R.string.kws_recorder_init_failed, state))
                releaseAudioRecordSafely()
                false
            } else {
                startRecording()
            }
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，启动录音失败", e)
            handleRecordingStartError(e, callback)
            false
        } catch (e: SecurityException) {
            Logger.e(TAG, "权限不足，启动录音失败", e)
            handleRecordingStartError(e, callback)
            false
        }
    }

    /**
     * 启动录音
     * @return true 如果成功，false 如果失败
     */
    private fun startRecording(): Boolean {
        isRunning.set(true)
        audioRecord?.startRecording() ?: run {
            Logger.e(TAG, "audioRecord 未初始化，无法开始录音")
            isRunning.set(false)
            return false
        }

        // 在后台线程处理音频
        recordingThread =
            thread(true) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                processAudio()
            }
        return true
    }

    /**
     * 处理录音启动错误
     */
    private fun handleRecordingStartError(
        e: Exception,
        callback: KeywordCallback,
    ) {
        isRunning.set(false)
        releaseAudioRecordSafely()
        releaseStreamSafely()
        callback.onError(context.getString(R.string.kws_start_recording_failed, e.message ?: ""))
    }

    /**
     * 安全释放AudioRecord
     */
    private fun releaseAudioRecordSafely() {
        audioRecord?.release()
        audioRecord = null
    }

    /**
     * 安全释放Stream
     */
    private fun releaseStreamSafely() {
        try {
            stream?.release()
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，释放 stream 失败", e)
        }
        stream = null
    }

    /**
     * 处理音频流
     */
    private fun processAudio() {
        val stream = this.stream
        val kws = this.keywordSpotter

        if (!validateAudioComponents(stream, kws)) {
            return
        }

        // 验证后确保非空（validateAudioComponents 已检查）
        val nonNullStream = stream ?: return
        val nonNullKws = kws ?: return

        val buffer = ShortArray(BUFFER_SIZE)
        var streamReleased = false // 添加标志防止重复释放

        try {
            processAudioLoop(nonNullStream, nonNullKws, buffer)
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，processAudio 异常", e)
        } catch (e: IOException) {
            Logger.e(TAG, "IO错误，processAudio 异常", e)
        } finally {
            // 释放流资源
            if (!streamReleased) {
                releaseStreamInProcessAudio(nonNullStream)
            }
        }
    }

    /**
     * 验证音频组件
     * @param stream 音频流
     * @param kws 关键词检测器
     * @return true 如果有效，false 如果无效
     */
    private fun validateAudioComponents(
        stream: OnlineStream?,
        kws: KeywordSpotter?,
    ): Boolean {
        if (stream == null || kws == null) {
            Logger.e(TAG, "Stream 或 KeywordSpotter 为 null")
            callback?.onError(context.getString(R.string.kws_audio_stream_not_initialized))
            return false
        }
        return true
    }

    /**
     * 处理音频循环
     * @param stream 音频流
     * @param kws 关键词检测器
     * @param buffer 音频缓冲区
     */
    private fun processAudioLoop(
        stream: OnlineStream,
        kws: KeywordSpotter,
        buffer: ShortArray,
    ) {
        while (isRunning.get()) {
            val audioRecord = this.audioRecord
            if (audioRecord == null) {
                Logger.e(TAG, "AudioRecord 为 null，停止音频处理")
                break
            }

            val ret = audioRecord.read(buffer, 0, buffer.size)

            if (ret > 0) {
                // 处理音频数据
                if (!processAudioData(stream, kws, buffer, ret)) {
                    break
                }
            } else {
                // 处理读取错误
                if (!handleAudioReadError(ret)) {
                    break
                }
            }
        }
    }

    /**
     * 在 processAudio 中释放流资源
     * @param stream 音频流
     */
    private fun releaseStreamInProcessAudio(stream: OnlineStream) {
        try {
            stream.release()
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，释放 stream 失败", e)
        }
    }

    /**
     * 处理音频数据
     * @param stream 音频流
     * @param kws 关键词检测器
     * @param buffer 音频缓冲区
     * @param ret 读取的字节数
     * @return true 如果继续处理，false 如果应该停止
     */
    private fun processAudioData(
        stream: OnlineStream,
        kws: KeywordSpotter,
        buffer: ShortArray,
        ret: Int,
    ): Boolean {
        // 转换为 float 数组（归一化到 -1.0 到 1.0）
        val samples = FloatArray(ret) { buffer[it] / 32768.0f }

        // 输入音频数据（参考官方示例）
        return try {
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            // 检测关键词
            detectKeywords(stream, kws)
            true
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，acceptWaveform 失败", e)
            false
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "参数错误，acceptWaveform 失败", e)
            false
        }
    }

    /**
     * 检测关键词
     * @param stream 音频流
     * @param kws 关键词检测器
     */
    private fun detectKeywords(
        stream: OnlineStream,
        kws: KeywordSpotter,
    ) {
        try {
            while (kws.isReady(stream)) {
                kws.decode(stream)
                val result = kws.getResult(stream)
                val text = result.keyword

                if (text.isNotBlank()) {
                    handleDetectedKeyword(stream, kws, text)
                }
            }
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，关键词检测过程出错", e)
            // 不立即退出，继续尝试
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "参数错误，关键词检测过程出错", e)
            // 不立即退出，继续尝试
        }
    }

    /**
     * 处理检测到的关键词
     * @param stream 音频流
     * @param kws 关键词检测器
     * @param text 检测到的关键词文本
     */
    private fun handleDetectedKeyword(
        stream: OnlineStream,
        kws: KeywordSpotter,
        text: String,
    ) {
        // 记录所有识别到的关键词（用于调试）
        if (BuildConfig.DEBUG) {
            Logger.d(TAG, "模型识别到关键词: \"$text\"")
        }

        // 根据监听模式过滤关键词
        val isRelevant =
            when (listeningMode) {
                ListeningMode.DICTATION -> text in DICTATION_KEYWORDS
                ListeningMode.START -> text in START_KEYWORDS
            }

        if (isRelevant) {
            Logger.i(TAG, "检测到有效关键词: $text (模式: $listeningMode)")
            invokeKeywordCallback(text)
        } else {
            if (BuildConfig.DEBUG) {
                Logger.d(TAG, "关键词 \"$text\" 不在当前模式的关键词列表中 (模式: $listeningMode)")
            }
        }

        // 检测到关键词后立即重置流（参考官方示例）
        resetStream(stream, kws)
    }

    /**
     * 调用关键词检测回调
     * @param text 检测到的关键词
     */
    private fun invokeKeywordCallback(text: String) {
        val currentCallback = callback
        if (currentCallback != null) {
            try {
                currentCallback.onKeywordDetected(text)
            } catch (e: IllegalStateException) {
                Logger.e(TAG, "状态错误，回调执行失败", e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消是正常情况，重新抛出
                throw e
            }
        }
    }

    /**
     * 重置音频流
     * @param stream 音频流
     * @param kws 关键词检测器
     */
    private fun resetStream(
        stream: OnlineStream,
        kws: KeywordSpotter,
    ) {
        try {
            kws.reset(stream)
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "状态错误，reset stream 失败", e)
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "参数错误，reset stream 失败", e)
        }
    }

    /**
     * 处理音频读取错误
     * @param ret 读取返回值
     * @return true 如果继续处理，false 如果应该停止
     */
    private fun handleAudioReadError(ret: Int): Boolean {
        return when (ret) {
            AudioRecord.ERROR_INVALID_OPERATION -> {
                Logger.e(TAG, "AudioRecord 读取错误: ERROR_INVALID_OPERATION")
                callback?.onError(context.getString(R.string.kws_recorder_operation_invalid))
                false
            }
            AudioRecord.ERROR_BAD_VALUE -> {
                Logger.e(TAG, "AudioRecord 读取错误: ERROR_BAD_VALUE")
                callback?.onError(context.getString(R.string.kws_recorder_argument_error))
                false
            }
            else -> true
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        isRunning.set(false)

        val threadToJoin = recordingThread
        recordingThread = null

        // 优先停止/释放 AudioRecord：可显著降低 read() 阻塞导致线程无法及时退出的概率
        val recordToRelease = audioRecord
        audioRecord = null
        recordToRelease?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            } catch (e: IllegalStateException) {
                Logger.w(TAG, "状态错误，停止录音失败（将继续释放）", e)
            } finally {
                try {
                    it.release()
                } catch (e: IllegalStateException) {
                    Logger.e(TAG, "状态错误，释放录音器失败", e)
                }
            }
            Unit
        }

        // stream 的释放交给 processAudio() 的 finally；这里仅断开引用，避免并发重复释放
        stream = null

        if (threadToJoin != null) {
            // 避免在主线程 join 造成卡顿：主线程改为后台等待
            if (Looper.myLooper() == Looper.getMainLooper()) {
                thread(isDaemon = true, name = "SherpaKwsStopper") {
                    runCatching { threadToJoin.join(1000) }
                        .onFailure { Logger.w(TAG, "等待录音线程结束失败", it) }
                }
            } else {
                runCatching { threadToJoin.join(1000) }
                    .onFailure { Logger.w(TAG, "等待录音线程结束失败", it) }
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopListening()

        // 释放官方库资源
        keywordSpotter?.release()
        keywordSpotter = null

        callback = null
    }

    /**
     * 关键词检测回调接口
     */
    interface KeywordCallback {
        fun onKeywordDetected(keyword: String)

        fun onError(error: String)
    }
}
