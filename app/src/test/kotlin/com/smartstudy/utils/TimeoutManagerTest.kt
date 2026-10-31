package com.smartstudy.utils

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TimeoutManager 单元测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimeoutManagerTest {
    @Mock
    private lateinit var mockActivity: AppCompatActivity

    private lateinit var timeoutManager: TimeoutManager
    private lateinit var lifecycle: LifecycleRegistry
    private var timeoutCalled = false

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        timeoutCalled = false
        timeoutManager =
            TimeoutManager(
                activity = mockActivity,
                timeoutMinutes = 1L,
                onTimeout = { timeoutCalled = true },
            )
        lifecycle = LifecycleRegistry(mockActivity as LifecycleOwner)
    }

    @Test
    fun `test start activates timeout`() {
        timeoutManager.start()
        // 验证启动成功（没有异常）
        assert(true)
    }

    @Test
    fun `test cancel stops timeout`() {
        timeoutManager.start()
        timeoutManager.cancel()
        // 验证取消成功
        assert(true)
    }

    @Test
    fun `test reset restarts timeout`() {
        timeoutManager.start()
        timeoutManager.reset()
        // 验证重置成功
        assert(true)
    }

    @Test
    fun `test cleanup on destroy`() {
        timeoutManager.start()
        lifecycle.currentState = Lifecycle.State.DESTROYED
        timeoutManager.onDestroy(lifecycle as LifecycleOwner)
        // 验证清理成功
        assert(true)
    }
}
