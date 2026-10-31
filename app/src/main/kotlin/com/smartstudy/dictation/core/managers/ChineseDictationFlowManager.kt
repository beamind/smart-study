package com.smartstudy.dictation.core.managers

import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.smartstudy.dictation.chinese.AnswerAdapter
import com.smartstudy.dictation.core.DictationRecordStorage
import com.smartstudy.dictation.core.QuestionRecord
import com.smartstudy.dictation.core.handlers.DictationViewManager
import com.smartstudy.dictation.core.handlers.DictationVoiceManager
import com.smartstudy.dictation.core.interfaces.ITtsService
import com.smartstudy.dictation.ui.DictationViewModel

/**
 * 中文听写流程管理器
 */
class ChineseDictationFlowManager(
    activity: AppCompatActivity,
    viewModel: DictationViewModel,
    viewManager: DictationViewManager,
    voiceHandler: DictationVoiceManager,
    ttsService: ITtsService,
    errorWordStorage: DictationRecordStorage,
    ttsIntervalMillis: Long,
    onDictationComplete: () -> Unit,
    onError: (String) -> Unit,
) : DictationFlowManager(
        activity = activity,
        viewModel = viewModel,
        viewManager = viewManager,
        voiceHandler = voiceHandler,
        ttsService = ttsService,
        errorWordStorage = errorWordStorage,
        dictationType = "chinese",
        ttsIntervalMillis = ttsIntervalMillis,
        onDictationComplete = onDictationComplete,
        onError = onError,
    ) {
    override fun getWords(): List<String> {
        return viewModel.uiState.value.recognitionWords
    }

    override fun getDictatedWords(): List<*> {
        return viewModel.uiState.value.dictatedWordsChinese
    }

    override fun recordCurrentWord(word: String) {
        viewModel.addChineseDictatedWord(word)
    }

    override fun createAnswerAdapter(): RecyclerView.Adapter<*> {
        val words = getDictatedWords() as List<String>
        return AnswerAdapter(words)
    }

    override fun buildQuestionRecords(selectedWords: Set<String>): List<QuestionRecord> {
        val allWords = getDictatedWords() as List<String>
        return allWords.map { word ->
            QuestionRecord(
                word = word,
                isCorrect = !selectedWords.contains(word),
                sourceType = sourceType,
            )
        }
    }
}
