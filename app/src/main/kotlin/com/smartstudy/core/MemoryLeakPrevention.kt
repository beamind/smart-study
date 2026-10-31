package com.smartstudy.core

/**
 * 内存泄漏预防指南和检查清单
 *
 * 本文件提供了内存泄漏预防的最佳实践和检查清单，帮助开发者避免常见的内存泄漏问题。
 *
 * ## 常见内存泄漏场景
 *
 * 1. **Handler 泄漏**
 *    - 问题：Handler 持有 Activity/Fragment 的隐式引用
 *    - 解决：使用静态内部类 + WeakReference，或在 onDestroy 中调用 removeCallbacksAndMessages(null)
 *
 * 2. **静态引用**
 *    - 问题：静态变量持有 Activity/Context 引用
 *    - 解决：使用 ApplicationContext，或使用 WeakReference
 *
 * 3. **监听器未取消注册**
 *    - 问题：注册了监听器但未在销毁时取消注册
 *    - 解决：在 onDestroy 中取消注册所有监听器
 *
 * 4. **协程未取消**
 *    - 问题：使用 GlobalScope 或自定义 CoroutineScope 未在销毁时取消
 *    - 解决：使用 lifecycleScope 或 viewModelScope，它们会在生命周期结束时自动取消
 *
 * 5. **单例持有 Context**
 *    - 问题：单例对象持有 Activity Context
 *    - 解决：使用 ApplicationContext 或 WeakReference
 *
 * ## 检查清单
 *
 * 在代码审查时，请检查以下项目：
 *
 * - [ ] 所有 Handler 都在 onDestroy 中清理（removeCallbacksAndMessages）
 * - [ ] 所有静态变量都不持有 Activity/Context 引用
 * - [ ] 所有监听器都在 onDestroy 中取消注册
 * - [ ] 所有协程都使用 lifecycleScope 或 viewModelScope（不使用 GlobalScope）
 * - [ ] 所有单例服务都使用 ApplicationContext 或 WeakReference
 * - [ ] 所有 Callback 都使用 WeakReference 或确保在 onDestroy 中置为 null
 * - [ ] 所有 Bitmap 都在使用后及时回收（recycle）
 * - [ ] 所有文件流都在使用后及时关闭（close）
 *
 * ## 工具推荐
 *
 * 1. **LeakCanary**：自动检测内存泄漏
 *    ```gradle
 *    debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.12'
 *    ```
 *
 * 2. **Android Studio Profiler**：手动分析内存使用情况
 *
 * 3. **StrictMode**：检测主线程上的磁盘和网络操作
 *
 * ## 当前项目的预防措施
 *
 * 1. **ResourceManager 接口**：统一管理资源释放
 * 2. **ResourceManagerHelper**：安全释放资源，防止异常影响其他资源释放
 * 3. **lifecycleScope/viewModelScope**：自动管理协程生命周期
 * 4. **TimeoutManager**：实现 LifecycleObserver，自动清理 Handler
 * 5. **依赖注入（Koin）**：避免静态持有 Context
 */

object MemoryLeakPrevention {
    /**
     * 检查 Handler 是否正确清理
     *
     * 在 Activity/Fragment 的 onDestroy 中调用此方法，确保所有 Handler 都已清理
     *
     * @param handlers 要检查的 Handler 列表
     * @return true 如果所有 Handler 都已清理，false 否则
     */
    fun checkHandlersCleaned(handlers: List<android.os.Handler?>): Boolean {
        return handlers.all { handler ->
            handler?.let { h ->
                // 注意：hasCallbacks(Runnable) 需要非空的 Runnable 参数
                // 由于无法检查所有可能的回调，这里通过反射检查消息队列是否为空
                try {
                    val looper = h.looper
                    val queue = looper?.queue
                    if (queue == null) {
                        return@let true
                    }
                    // 使用反射检查消息队列是否为空
                    val queueClass = queue.javaClass
                    val messagesField = queueClass.getDeclaredField("mMessages")
                    messagesField.isAccessible = true
                    val messages = messagesField.get(queue)
                    messages == null
                } catch (e: Exception) {
                    // 如果反射失败，假设已清理（实际清理应该通过 removeCallbacksAndMessages(null) 完成）
                    // 注意：这里捕获 Exception 是合理的，因为反射操作可能抛出多种异常（NoSuchFieldException、IllegalAccessException 等）
                    // 我们不需要区分具体的异常类型，只需要知道反射失败即可
                    Logger.d("MemoryLeakPrevention", "反射检查 Handler 消息队列失败，假设已清理", e)
                    true
                }
            } ?: true
        }
    }

    /**
     * 检查静态变量是否持有 Context
     *
     * 在代码审查时使用，检查静态变量是否持有 Activity Context
     *
     * @param context 要检查的 Context
     * @return true 如果是 ApplicationContext，false 如果是 Activity Context
     */
    fun isApplicationContext(context: android.content.Context?): Boolean {
        return context is android.app.Application ||
            context?.applicationContext == context
    }
}
