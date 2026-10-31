package com.smartstudy

import android.app.Application
import com.smartstudy.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * 应用级初始化入口。
 *
 * 低频家庭 App 更关注稳定性：将日志等全局能力放到 Application，
 * 避免 Activity 生命周期变化导致初始化/释放时机不一致。
 */
class SmartStudyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化日志系统
        LogFileWriter.init(this)

        // 初始化Prompt配置
        com.smartstudy.core.PromptConfig.init(this)

        // 验证应用配置
        try {
            com.smartstudy.core.AppConfig.validateConfig()
        } catch (e: IllegalStateException) {
            com.smartstudy.core.Logger.e(
                "SmartStudyApp",
                "配置验证失败，应用可能无法正常工作",
                e,
            )
        } catch (e: IllegalArgumentException) {
            com.smartstudy.core.Logger.e(
                "SmartStudyApp",
                "配置参数错误，应用可能无法正常工作",
                e,
            )
            // 不抛出异常，允许应用继续启动（在开发阶段可能没有配置 API Key）
            if (BuildConfig.DEBUG) {
                // Debug 模式下允许继续，但记录警告
            }
        }

        // 初始化 Koin 依赖注入
        startKoin {
            androidContext(this@SmartStudyApp)
            modules(appModule)
        }

        com.smartstudy.core.Logger.i("SmartStudyApp", "应用初始化完成")
    }
}
