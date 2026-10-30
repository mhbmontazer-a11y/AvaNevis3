package ir.avanevis.study.speech

import android.content.Context
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.StudyVoiceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class ListeningState { IDLE, STARTING, LISTENING_READY, SPEECH_DETECTED, PAUSED_BY_CALL, RECOVERING_ERROR }

data class SpeechEngineTelemetry(
    val state: ListeningState = ListeningState.IDLE,
    val currentRmsDb: Float = 0f,
    val livePartialText: String = "",
    val lastCommittedText: String = "",
    val restartCount: Int = 0,
    val lastLatencyMs: Long = 0L,
    val statusMessage: String = "آماده شروع مطالعه",
    val lastErrorCode: Int? = null,
    val isRecoveryRequired: Boolean = false
)

/** Keeps the existing service/UI API, with no Android SpeechRecognizer or Vosk. */
class ContinuousSpeechEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSentenceCommitted: suspend (String, String, Float, Long) -> Unit,
    private val onDurableChunk: suspend (String, String, String, Long) -> Unit,
    private val onVoiceCommand: suspend (StudyVoiceCommand) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onWhisperCorrection: suspend (String, String) -> Unit
) {
    private val state = MutableStateFlow(SpeechEngineTelemetry())
    val telemetry: StateFlow<SpeechEngineTelemetry> = state.asStateFlow()
    private var engine: WhisperStreamingEngine? = null
    fun updateSettings(settings: StudySettings) { engine?.updateSettings(settings) }
    fun startContinuousSession(settings: StudySettings, sessionId: Long, notebookId: Long) {
        if (engine != null) return
        engine = WhisperStreamingEngine(context, scope, state, sessionId, notebookId, onDurableChunk).also { it.start(settings) }
    }
    fun retryRecognition() { engine?.retry() }
    fun stopContinuousSession(): Job? = engine?.stop().also { engine = null }
    fun takeWhisperReviewAudio(): File? = null
    fun pauseForAudioInterruption() { engine?.pause() }
    fun resumeAfterAudioInterruption() { engine?.resume() }
}
