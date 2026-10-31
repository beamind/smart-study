package com.smartstudy

import android.content.Context
import android.util.Log
import com.smartstudy.core.Logger
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日志文件写入器
 * 将日志保存到文件，方便在无法连接电脑时查看日志
 */
object LogFileWriter {
    private const val TAG = "LogFileWriter"
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_PREFIX = "app_log_"
    private const val MAX_LOG_FILE_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_LOG_FILES = 5 // 最多保留5个日志文件
    private const val IO_BUFFER_SIZE = 16 * 1024

    private var logFile: File? = null
    private var writer: BufferedWriter? = null
    private var appContext: Context? = null
    private val isClosed = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // 串行化文件 IO：避免 UI 线程写日志、避免并发写入造成日志错乱
    private val ioExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "LogFileWriter").apply { isDaemon = true }
        }

    /**
     * 初始化日志文件
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (isClosed.get()) return

        ioExecutor.execute {
            runCatching { ensureInitializedLocked() }
                .onFailure { Logger.e(TAG, "初始化日志文件失败", it) }
        }
    }

    /**
     * 写入日志
     */
    fun writeLog(
        level: String,
        tag: String,
        message: String,
    ) {
        if (isClosed.get()) return

        ioExecutor.execute {
            try {
                ensureInitializedLocked()
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] [$level] [$tag] $message\n"

                writer?.write(logEntry)

                // 检查文件大小，如果太大则轮换日志
                logFile?.let { file ->
                    if (file.length() > MAX_LOG_FILE_SIZE) {
                        file.parentFile?.let { dir ->
                            rotateLogFileLocked(dir)
                        }
                    }
                }
            } catch (e: IOException) {
                Logger.e(TAG, "IO错误，写入日志失败", e)
            } catch (e: SecurityException) {
                Logger.e(TAG, "权限不足，写入日志失败", e)
            }
        }
    }

    /**
     * 写入调试日志
     */
    fun d(
        tag: String,
        message: String,
    ) {
        writeLog("DEBUG", tag, message)
        Logger.d(tag, message)
    }

    /**
     * 写入信息日志
     */
    fun i(
        tag: String,
        message: String,
    ) {
        writeLog("INFO", tag, message)
        Logger.i(tag, message)
    }

    /**
     * 写入警告日志
     */
    fun w(
        tag: String,
        message: String,
    ) {
        writeLog("WARN", tag, message)
        Logger.w(tag, message)
    }

    /**
     * 写入错误日志
     */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val fullMessage =
            if (throwable != null) {
                "$message\n${Log.getStackTraceString(throwable)}"
            } else {
                message
            }
        writeLog("ERROR", tag, fullMessage)
        Logger.e(tag, message, throwable)
    }

    /**
     * 获取日志文件路径
     */
    fun getLogFile(context: Context): File? {
        if (logFile == null) {
            init(context)
        }
        return logFile
    }

    /**
     * 获取所有日志文件
     */
    fun getAllLogFiles(context: Context): List<File> {
        val logDir = File(context.getExternalFilesDir(null), LOG_DIR_NAME)
        if (!logDir.exists()) {
            return emptyList()
        }

        return logDir.listFiles { _, name ->
            name.startsWith(LOG_FILE_PREFIX) && name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    /**
     * 清理旧日志文件
     */
    private fun cleanOldLogFiles(logDir: File) {
        val logFiles =
            logDir.listFiles { _, name ->
                name.startsWith(LOG_FILE_PREFIX) && name.endsWith(".txt")
            }?.sortedByDescending { it.lastModified() }?.toList() ?: return

        // 删除超过最大数量的旧文件
        if (logFiles.size > MAX_LOG_FILES) {
            logFiles.subList(MAX_LOG_FILES, logFiles.size).forEach { file ->
                file.delete()
            }
        }
    }

    /**
     * 轮换日志文件（当文件太大时）
     */
    private fun rotateLogFile(logDir: File) {
        if (isClosed.get()) return
        ioExecutor.execute {
            runCatching { rotateLogFileLocked(logDir) }
                .onFailure { Logger.e(TAG, "轮换日志文件失败", it) }
        }
    }

    /**
     * 关闭日志文件
     */
    fun close() {
        if (!isClosed.compareAndSet(false, true)) return

        ioExecutor.execute {
            runCatching {
                writer?.flush()
                writer?.close()
            }.onFailure {
                Logger.e(TAG, "关闭日志文件失败", it)
            }
            writer = null
        }

        ioExecutor.shutdown()
    }

    private fun ensureInitializedLocked() {
        if (writer != null && logFile != null) return

        val context = appContext ?: return
        val logDir = File(context.getExternalFilesDir(null), LOG_DIR_NAME)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        cleanOldLogFiles(logDir)

        val timestamp = logDateFormat.format(Date())
        logFile = File(logDir, "${LOG_FILE_PREFIX}$timestamp.txt")
        writer = BufferedWriter(FileWriter(logFile, true), IO_BUFFER_SIZE)

        Logger.d(TAG, "日志文件已初始化: ${logFile?.absolutePath}")
        val now = dateFormat.format(Date())
        writer?.write("[$now] [INFO] [$TAG] 日志系统已启动\n")
    }

    private fun rotateLogFileLocked(logDir: File) {
        writer?.flush()
        writer?.close()
        val timestamp = logDateFormat.format(Date())
        logFile = File(logDir, "${LOG_FILE_PREFIX}$timestamp.txt")
        writer = BufferedWriter(FileWriter(logFile, true), IO_BUFFER_SIZE)

        val now = dateFormat.format(Date())
        writer?.write("[$now] [INFO] [$TAG] 日志文件已轮换\n")
    }
}
