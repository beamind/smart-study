package com.smartstudy.dictation.chinese

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.CheckBox
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartstudy.MainActivity
import com.smartstudy.R
import com.smartstudy.di.appModule
import com.smartstudy.dictation.core.MockImageUnderstandingService
import com.smartstudy.dictation.core.MockTtsService
import com.smartstudy.dictation.core.MockVoiceRecognitionService
import com.smartstudy.dictation.helpers.DictationTestHelper
import com.smartstudy.dictation.helpers.DictationTestHelper.copyTestImageToTarget
import com.smartstudy.dictation.helpers.ImageUnderstandingIdlingResource
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
 * 语文听写功能端到端测试
 *
 * 完整测试流程：
 * MainActivity -> 点击"语文听写" -> ChineseDictationActivity ->
 * 点击"新的听写" -> 拍照 -> 裁剪 -> 图片理解 ->
 * TTS播报"开始听写"提示 -> 监听"开始听写"关键词 ->
 * TTS播报词语 -> 监听"下一个"关键词 ->
 * TTS播报"听写完成" -> 展示答案 -> 选择错题 ->
 * 提交错题 -> 错题展示 -> 返回
 */
@RunWith(AndroidJUnit4::class)
class ChineseDictationE2ETest : KoinTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var mockImageUnderstandingService: MockImageUnderstandingService
    private lateinit var mockTtsService: MockTtsService
    private lateinit var mockVoiceRecognitionService: MockVoiceRecognitionService
    private var testImageUri: Uri? = null

    // IdlingResources
    private lateinit var ttsIdlingResource: TtsIdlingResource
    private lateinit var imageUnderstandingIdlingResource: ImageUnderstandingIdlingResource
    private lateinit var voiceRecognitionIdlingResource: VoiceRecognitionIdlingResource

    // 测试数据
    private val testWords = listOf("苹果", "香蕉", "橙子")

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
            Manifest.permission.CAMERA,
        )
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
        mockImageUnderstandingService =
            MockImageUnderstandingService().apply {
                testWords = this@ChineseDictationE2ETest.testWords
                delayMs = 50L
            }

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
        val testModule =
            module(override = true) {
                single<com.smartstudy.dictation.core.interfaces.ITtsService> { mockTtsService }
                single<com.smartstudy.dictation.core.interfaces.IImageUnderstandingService> { mockImageUnderstandingService }
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
        imageUnderstandingIdlingResource = ImageUnderstandingIdlingResource()
        voiceRecognitionIdlingResource = VoiceRecognitionIdlingResource()

        // 注册IdlingResources
        androidx.test.espresso.Espresso.registerIdlingResources(
            ttsIdlingResource,
            imageUnderstandingIdlingResource,
            voiceRecognitionIdlingResource,
        )

        // 初始化Espresso Intents
        Intents.init()

        // 创建测试图片
        testImageUri = DictationTestHelper.createTestImage("苹果 香蕉 橙子")

        // Mock拍照Intent
        // 注意：ActivityResultContracts.TakePicture()的回调只返回布尔值（success）
        // 实际使用的URI是DictationImageManager在takePhoto()中创建并存储在currentPhotoUri中的
        // 我们需要拦截Intent，获取EXTRA_OUTPUT中的目标URI，然后将测试图片复制到该位置
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWithFunction { intent ->
            val targetUri = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
            val sourceUri = testImageUri
            if (targetUri != null && sourceUri != null) {
                try {
                    copyTestImageToTarget(sourceUri, targetUri)
                } catch (e: Exception) {
                    android.util.Log.e("ChineseDictationE2ETest", "复制测试图片失败", e)
                }
            }
            Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null)
        }

        // Mock图片选择Intent（如果从相册选择）
        val resultData =
            Intent().apply {
                data = testImageUri
            }
        intending(hasAction(Intent.ACTION_PICK))
            .respondWith(
                Instrumentation.ActivityResult(
                    android.app.Activity.RESULT_OK,
                    resultData,
                ),
            )
    }

    @After
    fun tearDown() {
        // 取消注册IdlingResources
        androidx.test.espresso.Espresso.unregisterIdlingResources(
            ttsIdlingResource,
            imageUnderstandingIdlingResource,
            voiceRecognitionIdlingResource,
        )

        // 释放Espresso Intents
        Intents.release()

        // 停止Koin
        try {
            stopKoin()
        } catch (e: Exception) {
            // 忽略
        }

        // 清理测试图片
        DictationTestHelper.cleanupTestImage(testImageUri)
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
    fun testCompleteChineseDictationFlow() {
        // ========== 步骤1: 从MainActivity点击"语文听写" ==========
        waitAndClick(
            allOf(
                isDisplayed(),
                withId(R.id.dictationCard),
                hasDescendant(withText(R.string.dictation_module)),
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

        // ========== 步骤3: 处理图片选择对话框 ==========
        waitAndClick(withText(R.string.take_photo_option), TIMEOUT_LONG)

        // ========== 步骤4: 等待裁剪界面并完成裁剪 ==========
        DictationTestHelper.waitForView(
            allOf(isDisplayed(), withId(R.id.cropContainer)),
            timeoutMs = TIMEOUT_LONG,
        )
        DictationTestHelper.waitForView(
            allOf(isDisplayed(), withId(R.id.cropButtonLayout)),
            timeoutMs = TIMEOUT_LONG,
        )

        // 等待图片加载完成
        Thread.sleep(TIMEOUT_SHORT)

        waitAndClick(
            allOf(
                withId(R.id.btnCropDone),
                withText(R.string.finish),
                isDisplayed(),
                isClickable(),
            ),
            TIMEOUT_LONG,
        )

        // ========== 步骤5: 等待图片理解完成 ==========
        // 注意：图片理解是异步的，通过事件机制触发UI更新
        // 流程：optimizeImage -> recognizeText -> emit事件 -> 事件处理 ->
        //      onImageUnderstandingSuccess -> speakReadyPrompt -> showStartDictationViews
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
                withId(R.id.dictationCard),
                hasDescendant(withText(R.string.dictation_module)),
                isDisplayed(),
                isClickable(),
            ),
            TIMEOUT_LONG,
        )
    }
}
