package com.smartstudy.english.managers

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.smartstudy.core.AppResult
import com.smartstudy.dictation.core.DictationWordStat
import com.smartstudy.english.EnglishErrorBookStorage
import com.smartstudy.english.ErrorBookViewModel
import com.smartstudy.english.QuestionAnswer
import com.smartstudy.english.usecases.ErrorBookUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ErrorBookQuestionManager 单元测试
 * 测试错题本题目管理Manager的各种操作
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorBookQuestionManagerTest {
    @Mock
    private lateinit var mockActivity: AppCompatActivity

    @Mock
    private lateinit var mockErrorBookUseCase: ErrorBookUseCase

    private lateinit var questionManager: ErrorBookQuestionManager
    private lateinit var lifecycle: LifecycleRegistry
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // 设置生命周期和lifecycleScope
        lifecycle = LifecycleRegistry(mockActivity as LifecycleOwner)
        lifecycle.currentState = Lifecycle.State.RESUMED
        whenever(mockActivity.lifecycle).thenReturn(lifecycle)

        // Mock lifecycleScope
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val lifecycleScopeField = AppCompatActivity::class.java.getDeclaredField("lifecycleScope")
            lifecycleScopeField.isAccessible = true
            lifecycleScopeField.set(mockActivity, testScope)
        } catch (e: Exception) {
            // 如果无法设置，使用反射或其他方式
        }

        val mockViewModel = org.mockito.Mockito.mock(ErrorBookViewModel::class.java)
        val mockUICallback = org.mockito.Mockito.mock(QuestionManagerUICallback::class.java)

        questionManager =
            ErrorBookQuestionManager(
                activity = mockActivity,
                errorBookUseCase = mockErrorBookUseCase,
                viewModel = mockViewModel,
                uiCallback = mockUICallback,
            )
    }

    @Test
    fun `loadAllQuestions succeeds with questions`() =
        runTest {
            val testQuestions =
                listOf(
                    QuestionAnswer("Question 1", "Answer 1"),
                    QuestionAnswer("Question 2", "Answer 2"),
                )
            val questionsWithStats =
                testQuestions.map { qa ->
                    qa to
                        EnglishErrorBookStorage.QuestionWithStats(
                            question = qa.question,
                            errorCount = 1,
                            totalCount = 2,
                        )
                }

            whenever(mockErrorBookUseCase.loadAllQuestionsWithAnswers(any())).thenReturn(
                AppResult.Success(questionsWithStats),
            )

            var successCalled = false
            var loadedQuestions: List<QuestionAnswer>? = null
            var loadedStats: Map<String, DictationWordStat>? = null

            questionManager.loadAllQuestions(
                onSuccess = { questions, stats ->
                    successCalled = true
                    loadedQuestions = questions
                    loadedStats = stats
                },
                onError = { },
            )

            advanceUntilIdle()

            assertTrue(successCalled)
            assertNotNull(loadedQuestions)
            assertEquals(2, loadedQuestions?.size)
            assertNotNull(loadedStats)
            assertEquals(2, loadedStats?.size)
        }

    @Test
    fun `loadAllQuestions fails when storage throws exception`() =
        runTest {
            whenever(mockErrorBookUseCase.loadAllQuestionsWithAnswers(any())).thenReturn(
                AppResult.Failure(
                    com.smartstudy.core.AppError(
                        com.smartstudy.core.AppErrorKind.Unknown,
                        "Storage error",
                        RuntimeException("Storage error"),
                    ),
                ),
            )

            var errorCalled = false
            var errorMessage: String? = null

            questionManager.loadAllQuestions(
                onSuccess = { _, _ -> },
                onError = { error ->
                    errorCalled = true
                    errorMessage = error
                },
            )

            advanceUntilIdle()

            assertTrue(errorCalled)
            assertNotNull(errorMessage)
            assertTrue(errorMessage?.contains("加载失败") == true)
        }

    @Test
    fun `loadAllQuestions returns empty list when no questions`() =
        runTest {
            whenever(mockErrorBookUseCase.loadAllQuestionsWithAnswers(any())).thenReturn(
                AppResult.Success(emptyList()),
            )

            var errorCalled = false
            var errorMessage: String? = null

            questionManager.loadAllQuestions(
                onSuccess = { _, _ -> },
                onError = { error ->
                    errorCalled = true
                    errorMessage = error
                },
            )

            advanceUntilIdle()

            assertTrue(errorCalled)
            assertEquals("没有已保存的错题", errorMessage)
        }

    @Test
    fun `saveQuestions succeeds`() =
        runTest {
            val testQuestions =
                listOf(
                    QuestionAnswer("Question 1", "Answer 1"),
                    QuestionAnswer("Question 2", "Answer 2"),
                )

            whenever(mockErrorBookUseCase.saveQuestionsWithAnswers(any(), any())).thenReturn(
                AppResult.Success(Unit),
            )

            var successCalled = false

            questionManager.saveQuestions(
                questions = testQuestions,
                onSuccess = { successCalled = true },
                onError = { },
            )

            advanceUntilIdle()

            assertTrue(successCalled)
            verify(mockErrorBookUseCase).saveQuestionsWithAnswers("test-user", testQuestions)
        }

    @Test
    fun `deleteQuestions succeeds`() =
        runTest {
            val questionsToDelete = listOf("Question 1", "Question 2")

            whenever(mockErrorBookUseCase.deleteQuestions(any(), any())).thenReturn(
                AppResult.Success(Unit),
            )

            var successCalled = false

            questionManager.deleteQuestions(
                questions = questionsToDelete,
                onSuccess = { successCalled = true },
                onError = { },
            )

            advanceUntilIdle()

            assertTrue(successCalled)
            verify(mockErrorBookUseCase).deleteQuestions("test-user", questionsToDelete)
        }

    @Test
    fun `updateQuestion succeeds`() =
        runTest {
            whenever(mockErrorBookUseCase.updateQuestion(any(), any(), any())).thenReturn(
                AppResult.Success(Unit),
            )

            var successCalled = false

            questionManager.updateQuestion(
                oldQuestion = "Old Question",
                newQuestion = "New Question",
                onSuccess = { successCalled = true },
                onError = { },
            )

            advanceUntilIdle()

            assertTrue(successCalled)
            verify(mockErrorBookUseCase).updateQuestion("test-user", "Old Question", "New Question")
        }

    @Test
    fun `updateAnswer succeeds`() =
        runTest {
            whenever(mockErrorBookUseCase.updateAnswer(any(), any(), any())).thenReturn(
                AppResult.Success(Unit),
            )

            var successCalled = false

            questionManager.updateAnswer(
                question = "Question 1",
                answer = "New Answer",
                onSuccess = { successCalled = true },
                onError = { },
            )

            advanceUntilIdle()

            assertTrue(successCalled)
            verify(mockErrorBookUseCase).updateAnswer("test-user", "Question 1", "New Answer")
        }
}
