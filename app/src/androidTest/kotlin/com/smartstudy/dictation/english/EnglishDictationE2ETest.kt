package com.smartstudy.dictation.english

import android.Manifest
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartstudy.MainActivity
import com.smartstudy.R
import com.smartstudy.di.appModule
import com.smartstudy.dictation.core.MockTtsService
import com.smartstudy.dictation.core.MockVoiceRecognitionService
import com.smartstudy.dictation.helpers.DictationTestHelper
import com.smartstudy.dictation.helpers.TtsIdlingResource
import com.smartstudy.dictation.helpers.VoiceRecognitionIdlingResource
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

/**
 * 英语听写功能端到端测试
 *
 * 完整测试流程：
 * MainActivity -> 点击"英语听写" -> EnglishDictationActivity ->
 * 点击"新的听写" -> 选择"手动输入" -> 输入英文单词 -> 确认 ->
 * TTS播报"开始听写"提示 -> 监听"开始听写"关键词 ->
 * TTS播报词语 -> 监听"下一个"关键词 ->
 * TTS播报"听写完成" -> 展示答案 -> 选择错题 ->
 * 提交错题 -> 错题展示 -> 返回
 */
@RunWith(AndroidJUnit4::class)
class EnglishDictationE2ETest : KoinTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var mockTtsService: MockTtsService
    private lateinit var mockVoiceRecognitionService: MockVoiceRecognitionService

    // IdlingResources
    private lateinit var ttsIdlingResource: TtsIdlingResource
    private lateinit var voiceRecognitionIdlingResource: VoiceRecognitionIdlingResource

    // 测试数据
    private val testWords = listOf("apple", "banana", "orange")

    // 测试超时时间常量（毫秒）
    private companion object {
        const val TIMEOUT_SHORT = 500L
        const val TIMEOUT_MEDIUM = 1000L
        const val TIMEOUT_LONG = 2000L
        const val TIMEOUT_TTS = 3000L
    }

    @Before
    fun setUp() {
        // 授予所有必需的权限，避免权限对话框遮挡视图
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

        // 停止现有的Koin实例（如果存在）
        try {
            stopKoin()
        } catch (e: Exception) {
            // 忽略，可能Koin未启动
        }

        // 创建Mock服务实例
        mockTtsService =
            MockTtsService().apply {
                playDelayMs = 30L
            }

        // 创建Mock语音识别服务实例
        // 注意：由于VoiceRecognitionService需要Context参数，使用factory模式返回同一个Mock实例
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        mockVoiceRecognitionService = MockVoiceRecognitionService(testContext)

        // 创建测试专用的Koin模块（包含Mock服务实例）
        // 注意：使用override=true来覆盖appModule中的定义
        // 注意：不需要MockImageUnderstandingService，因为使用手动输入
        val testModule =
            module(override = true) {
                single<com.smartstudy.dictation.core.interfaces.ITtsService> { mockTtsService }
                factory<com.smartstudy.dictation.core.VoiceRecognitionService> { (context: android.content.Context) ->
                    mockVoiceRecognitionService as com.smartstudy.dictation.core.VoiceRecognitionService
                }
            }

        // 启动Koin并加载应用模块和测试模块
        // 测试模块会覆盖Mock服务，其他服务使用appModule中的真实实现
        startKoin {
            androidContext(InstrumentationRegistry.getInstrumentation().targetContext)
            modules(appModule, testModule)
        }

        // 初始化IdlingResources
        ttsIdlingResource = TtsIdlingResource(mockTtsService)
        voiceRecognitionIdlingResource = VoiceRecognitionIdlingResource()

        // 注册IdlingResources
        androidx.test.espresso.Espresso.registerIdlingResources(
            ttsIdlingResource,
            voiceRecognitionIdlingResource,
        )
    }

    @After
    fun tearDown() {
        // 取消注册IdlingResources
        androidx.test.espresso.Espresso.unregisterIdlingResources(
            ttsIdlingResource,
            voiceRecognitionIdlingResource,
        )

        // 停止Koin
        try {
            stopKoin()
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 等待视图并点击，避免重复的check(isDisplayed())
     */
    private fun waitAndClick(
        matcher: org.hamcrest.Matcher<View>,
        timeoutMs: Long = TIMEOUT_LONG,
    ) {
        DictationTestHelper.waitForView(matcher, timeoutMs = timeoutMs)
        onView(matcher).perform(click())
    }

    /**
     * 模拟TTS播放操作
     */
    private fun simulateTtsPlay() {
        ttsIdlingResource.startOperation()
        Thread.sleep(TIMEOUT_TTS)
        ttsIdlingResource.finishOperation()
    }

    /**
     * 检查复选框是否被禁用
     */
    private fun checkCheckboxDisabled(): ViewAction {
        return object : ViewAction {
            override fun getDescription(): String {
                return "检查复选框是否被禁用"
            }

            override fun getConstraints(): org.hamcrest.Matcher<View> {
                return isAssignableFrom(View::class.java)
            }

            override fun perform(
                uiController: UiController,
                view: View,
            ) {
                val checkbox = view.findViewById<CheckBox>(R.id.cbSelect)
                MatcherAssert.assertThat(
                    "复选框应该被禁用",
                    checkbox?.isEnabled ?: false,
                    Matchers.`is`(false),
                )
            }
        }
    }

    /**
     * 点击RecyclerView中指定位置的CheckBox
     */
    private fun clickCheckboxAtPosition(position: Int): ViewAction {
        return object : ViewAction {
            override fun getDescription(): String {
                return "点击位置 $position 的复选框"
            }

            override fun getConstraints(): org.hamcrest.Matcher<View> {
                return isAssignableFrom(View::class.java)
            }

            override fun perform(
                uiController: UiController,
                view: View,
            ) {
                val checkbox = view.findViewById<CheckBox>(R.id.cbSelect)
                if (checkbox != null && checkbox.isEnabled) {
                    checkbox.performClick()
                } else {
                    throw AssertionError("复选框不存在或已被禁用")
                }
            }
        }
    }

    @Test
    fun testCompleteEnglishDictationFlow() {
        // ========== 步骤1: 从MainActivity点击"英语听写" ==========
        waitAndClick(
            allOf(
                isDisplayed(),
                withId(R.id.englishDictationCard),
                hasDescendant(withText(R.string.english_dictation_module)),
            ),
            TIMEOUT_LONG,
        )

        DictationTestHelper.waitForActivity(R.id.btnTakePhoto, timeoutMs = TIMEOUT_LONG)

        // ========== 步骤2: 点击"新的听写"按钮 ==========
        waitAndClick(
            allOf(
                withId(R.id.btnTakePhoto),
                hasDescendant(withText(R.string.take_photo)),
                isDisplayed(),
                isClickable(),
            ),
            TIMEOUT_LONG,
        )

        // ========== 步骤3: 选择"手动输入"选项 ==========
        waitAndClick(withText(R.string.manual_input_option), TIMEOUT_LONG)

        // ========== 步骤4: 等待手动输入对话框出现并输入英文单词 ==========
        // 等待对话框标题出现
        DictationTestHelper.waitForView(
            allOf(isDisplayed(), withText(R.string.manual_input_title)),
            timeoutMs = TIMEOUT_LONG,
        )

        // 等待EditText可见并输入英文单词（每行一个）
        val inputText = testWords.joinToString("\n")
        DictationTestHelper.waitForView(
            allOf(isDisplayed(), isAssignableFrom(EditText::class.java)),
            timeoutMs = TIMEOUT_MEDIUM,
        )
        onView(allOf(isDisplayed(), isAssignableFrom(EditText::class.java)))
            .perform(clearText(), typeText(inputText))

        // 点击确认按钮
        waitAndClick(
            allOf(
                withText(R.string.confirm),
                isDisplayed(),
                isClickable(),
            ),
            TIMEOUT_MEDIUM,
        )

        // ========== 步骤5: 等待手动输入处理完成 ==========
        // 手动输入会通过ViewModel事件机制处理，最终调用onImageUnderstandingSuccess
        // 流程：setManualInputWords -> emit事件 -> 事件处理 -> onImageUnderstandingSuccess -> speakReadyPrompt -> showStartDictationViews
        Thread.sleep(TIMEOUT_MEDIUM)

        // ========== 步骤6: 模拟TTS播放"开始听写"提示 ==========
        simulateTtsPlay()

        waitAndClick(
            allOf(
                withId(R.id.btnStartDictation),
                withText(R.string.start_dictation),
                isDisplayed(),
                isClickable(),
            ),
            TIMEOUT_LONG,
        )

        // ========== 步骤7: 模拟检测到"开始听写"关键词 ==========
        Thread.sleep(TIMEOUT_SHORT)

        mockVoiceRecognitionService.triggerStartKeyword()

        DictationTestHelper.waitForView(
            withId(R.id.cardProgress),
            timeoutMs = TIMEOUT_MEDIUM,
        )

        // ========== 步骤8-10: 模拟听写过程 ==========
        for (i in testWords.indices) {
            simulateTtsPlay()
            Thread.sleep(TIMEOUT_SHORT)
            mockVoiceRecognitionService.triggerNextKeyword()
            Thread.sleep(TIMEOUT_SHORT)
        }

        // ========== 步骤11: 等待听写完成，显示答案 ==========
        DictationTestHelper.waitForView(
            allOf(isDisplayed(), withId(R.id.cardAnswers)),
            timeoutMs = TIMEOUT_LONG,
        )

        // ========== 步骤12: 选择错题 ==========
        // 等待RecyclerView可见
        DictationTestHelper.waitForView(
            allOf(isDisplayed(), withId(R.id.rvAnswers)),
            timeoutMs = TIMEOUT_MEDIUM,
        )

        // 点击第一个item的CheckBox
        onView(withId(R.id.rvAnswers))
            .perform(
                RecyclerViewActions.actionOnItemAtPosition<androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                    0,
                    clickCheckboxAtPosition(0),
                ),
            )

        // ========== 步骤13: 提交错题 ==========
        waitAndClick(
            allOf(
                withId(R.id.btnSubmit),
                withText(R.string.submit_error_words),
                isDisplayed(),
                isClickable(),
            ),
            TIMEOUT_MEDIUM,
        )
        Thread.sleep(TIMEOUT_MEDIUM)

        // ========== 步骤14: 验证提交的错题页面可见，但不可被编辑 ==========
        // cardAnswers已经在步骤11中等待过了，无需重复检查
        onView(withId(R.id.btnSubmit)).check(
            matches(allOf(isDisplayed(), withText(R.string.btn_return))),
        )

        onView(withId(R.id.rvAnswers)).perform(
            RecyclerViewActions.actionOnItemAtPosition<androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                0,
                checkCheckboxDisabled(),
            ),
        )

        // ========== 步骤15: 返回 ==========
        waitAndClick(
            allOf(withId(R.id.btnSubmit), withText(R.string.btn_return)),
            TIMEOUT_MEDIUM,
        )
        Thread.sleep(TIMEOUT_MEDIUM)

        waitAndClick(
            allOf(
                withId(R.id.englishDictationCard),
                hasDescendant(withText(R.string.english_dictation_module)),
                isDisplayed(),
                isClickable(),
            ),
            TIMEOUT_LONG,
        )
    }
}
