package ir.avanevis.study.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ir.avanevis.study.data.preferences.RecognitionEngineMode
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.PersianTextProcessor
import ir.avanevis.study.domain.StudyVoiceCommand
import ir.avanevis.study.domain.VoiceCommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.joinAll

enum class ListeningState {
    IDLE,
    STARTING,            // startListening was called, but mic is NOT ready yet
    LISTENING_READY,     // onReadyForSpeech: safe to start speaking
    SPEECH_DETECTED,
    PAUSED_BY_CALL,
    RECOVERING_ERROR
}

data class SpeechEngineTelemetry(
    val state: ListeningState = ListeningState.IDLE,
    val currentRmsDb: Float = 0f,
    val livePartialText: String = "",
    val lastCommittedText: String = "",
    val restartCount: Int = 0,
    val lastLatencyMs: Long = 0L,
    val statusMessage: String = "آماده شروع مطالعه"
)

/**
 * Android's SpeechRecognizer is session-based, not a gapless PCM streamer.
 * We only persist a sentence once, after a *logical* quiet period. Interim hypotheses
 * replace the preview; premature final results are joined with the next ASR session.
 * Never stopListening() just because the partial text hasn't changed for a moment.
 */
class ContinuousSpeechEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSentenceCommitted: suspend (processedText: String, rawText: String, confidence: Float, latencyMs: Long) -> Unit,
    private val onVoiceCommand: suspend (StudyVoiceCommand) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private val utterance = UtteranceAccumulator()
    private val outstandingWrites = mutableSetOf<Job>()

    private val _telemetry = MutableStateFlow(SpeechEngineTelemetry())
    val telemetry: StateFlow<SpeechEngineTelemetry> = _telemetry.asStateFlow()

    @Volatile private var isSessionActive = false
    @Volatile private var currentSettings = StudySettings()
    private var pausedForInterruption = false
    private var recognizerInFlight = false
    private var speechInProgress = false
    private var restartJob: Job? = null
    private var readinessTimeoutJob: Job? = null
    private var pendingCommitJob: Job? = null
    private var gracefulStopJob: Job? = null
    private var stopResultSignal: CompletableDeferred<Unit>? = null
    @Volatile private var stopRequested = false
    private var lastSpeechActivityMs = 0L
    private var currentConfidence = 0.90f
    private var consecutiveErrors = 0

    fun updateSettings(settings: StudySettings) {
        currentSettings = settings
    }

    fun startContinuousSession(initialSettings: StudySettings) {
        currentSettings = initialSettings
        if (isSessionActive) return
        isSessionActive = true
        stopRequested = false
        gracefulStopJob = null
        stopResultSignal = null
        pausedForInterruption = false
        utterance.clear()
        lastSpeechActivityMs = 0L
        consecutiveErrors = 0
        _telemetry.value = SpeechEngineTelemetry(
            state = ListeningState.STARTING,
            statusMessage = "در حال آماده‌سازی میکروفون؛ پس از نمایش «آماده» صحبت کنید"
        )
        mainHandler.post { startRecognizerIfPossible() }
    }

    /**
     * Gracefully stops recognition. Ask the provider for its final result first, then
     * commit any remaining hypothesis and wait for all database writes before returning.
     */
    fun stopContinuousSession(): Job? {
        val activeStop = gracefulStopJob
        if (activeStop != null && activeStop.isActive) return activeStop

        if (!isSessionActive) {
            commitPendingUtterance()
            val pendingWrites = outstandingWrites.toList()
            return if (pendingWrites.isEmpty()) null else scope.launch { pendingWrites.joinAll() }
        }

        stopRequested = true
        restartJob?.cancel()
        readinessTimeoutJob?.cancel()
        pendingCommitJob?.cancel()
        stopResultSignal = CompletableDeferred()
        val waitForFinalResult = recognizerInFlight

        val job = scope.launch(Dispatchers.Main.immediate) {
            if (waitForFinalResult) {
                runCatching { speechRecognizer?.stopListening() }
                // Most providers return quickly. The timeout is a fallback only; it never
                // forces a partial note while the recognizer is still being used normally.
                withTimeoutOrNull(1800L) { stopResultSignal?.await() }
            }

            isSessionActive = false
            stopRequested = false
            stopResultSignal = null
            recognizerInFlight = false
            speechInProgress = false
            readinessTimeoutJob?.cancel()
            pendingCommitJob?.cancel()

            commitPendingUtterance()
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            }
            speechRecognizer = null

            // commitPendingUtterance starts writes undispatched, so they are present here.
            val pendingWrites = outstandingWrites.toList()
            pendingWrites.joinAll()
            _telemetry.value = SpeechEngineTelemetry(
                state = ListeningState.IDLE,
                statusMessage = "جلسه مطالعه پایان یافت"
            )
        }
        gracefulStopJob = job
        return job
    }

    fun pauseForAudioInterruption() {
        if (!isSessionActive || pausedForInterruption) return
        pausedForInterruption = true
        readinessTimeoutJob?.cancel()
        pendingCommitJob?.cancel()
        utterance.onRecognizerFailure()
        speechInProgress = false
        recognizerInFlight = false
        restartJob?.cancel()
        mainHandler.post { runCatching { speechRecognizer?.cancel() } }
        updatePreview("توقف موقت (تماس یا اشغال میکروفون)")
        _telemetry.value = _telemetry.value.copy(state = ListeningState.PAUSED_BY_CALL)
    }

    fun resumeAfterAudioInterruption() {
        if (!isSessionActive || !pausedForInterruption) return
        pausedForInterruption = false
        if (utterance.hasText) scheduleCommitAfterQuietPeriod()
        scheduleRestart(delayMs = 120L, recreateRecognizer = true)
    }

    private fun startRecognizerIfPossible() {
        if (!isSessionActive || pausedForInterruption || recognizerInFlight) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _telemetry.value = _telemetry.value.copy(
                state = ListeningState.RECOVERING_ERROR,
                statusMessage = "سرویس تشخیص گفتار روی دستگاه موجود نیست"
            )
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        _telemetry.value = _telemetry.value.copy(
            state = ListeningState.STARTING,
            statusMessage = "در حال اتصال میکروفون؛ لطفاً منتظر وضعیت «آماده» باشید"
        )
        runCatching {
            recognizerInFlight = true
            speechRecognizer?.startListening(buildRecognizerIntent(currentSettings))
            readinessTimeoutJob?.cancel()
            readinessTimeoutJob = scope.launch(Dispatchers.Main.immediate) {
                delay(10_000L)
                // Only recover a recognizer stuck before readiness. Never use this timer
                // to commit or discard speech while a user is speaking.
                if (isSessionActive && !pausedForInterruption && recognizerInFlight &&
                    _telemetry.value.state == ListeningState.STARTING && !speechInProgress
                ) {
                    recognizerInFlight = false
                    runCatching {
                        speechRecognizer?.cancel()
                        speechRecognizer?.destroy()
                    }
                    speechRecognizer = null
                    _telemetry.value = _telemetry.value.copy(
                        state = ListeningState.RECOVERING_ERROR,
                        statusMessage = "سرویس گفتار پاسخ نداد؛ تلاش مجدد..."
                    )
                    scheduleRestart(delayMs = 400L, recreateRecognizer = true)
                }
            }
        }.onFailure {
            readinessTimeoutJob?.cancel()
            readinessTimeoutJob = null
            recognizerInFlight = false
            scheduleRestart(delayMs = 350L, recreateRecognizer = true)
        }
    }

    private fun buildRecognizerIntent(settings: StudySettings): Intent {
        val quietMs = settings.silenceThresholdMs.coerceIn(1500L, 8000L)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Hints only: OEM speech services may ignore silence settings.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, quietMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, quietMs)
            // Avoid the old 15-second minimum, which could keep a completed result waiting.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    settings.engineMode == RecognitionEngineMode.OFFLINE_ONLY
                )
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isSessionActive || pausedForInterruption) return
            readinessTimeoutJob?.cancel()
            readinessTimeoutJob = null
            consecutiveErrors = 0
            _telemetry.value = _telemetry.value.copy(
                state = ListeningState.LISTENING_READY,
                statusMessage = "آماده شنیدن • جمله مهم را بخوانید"
            )
        }

        override fun onBeginningOfSpeech() {
            if (!isSessionActive || pausedForInterruption) return
            speechInProgress = true
            lastSpeechActivityMs = SystemClock.elapsedRealtime()
            pendingCommitJob?.cancel()
            _telemetry.value = _telemetry.value.copy(
                state = ListeningState.SPEECH_DETECTED,
                statusMessage = "در حال نوشتن؛ مکث کوتاه جمله را قطع نمی‌کند"
            )
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!isSessionActive || pausedForInterruption) return
            _telemetry.value = _telemetry.value.copy(currentRmsDb = rmsdB.coerceIn(0f, 12f))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!isSessionActive || pausedForInterruption) return
            speechInProgress = false
            lastSpeechActivityMs = SystemClock.elapsedRealtime()
            _telemetry.value = _telemetry.value.copy(
                currentRmsDb = 0f,
                statusMessage = "در حال دریافت نتیجه گفتار..."
            )
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isSessionActive || pausedForInterruption) return
            val hypothesis = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (hypothesis.isBlank()) return
            pendingCommitJob?.cancel()
            utterance.onPartial(hypothesis)
            lastSpeechActivityMs = SystemClock.elapsedRealtime()
            updatePreview("در حال نوشتن زنده؛ هنوز ذخیره نشده")
            _telemetry.value = _telemetry.value.copy(state = ListeningState.SPEECH_DETECTED)
        }

        override fun onResults(results: Bundle?) {
            if (!isSessionActive || pausedForInterruption) return
            recognizerInFlight = false
            readinessTimeoutJob?.cancel()
            readinessTimeoutJob = null
            speechInProgress = false
            consecutiveErrors = 0
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            currentConfidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                ?.firstOrNull() ?: currentConfidence
            // Final is authoritative for this ASR session, partial only a fallback.
            utterance.onFinal(best)
            updatePreview(if (stopRequested) "در حال ذخیره آخرین جمله..." else "مکث کوتاه پذیرفته می‌شود؛ در انتظار ادامه جمله...")
            if (stopRequested) {
                stopResultSignal?.complete(Unit)
                return
            }
            if (utterance.hasText) scheduleCommitAfterQuietPeriod()
            scheduleRestart(delayMs = 100L)
        }

        override fun onError(error: Int) {
            if (!isSessionActive || pausedForInterruption) return
            recognizerInFlight = false
            readinessTimeoutJob?.cancel()
            readinessTimeoutJob = null
            speechInProgress = false
            // Preserve partial after errors, but do NOT save it as a new sentence now.
            utterance.onRecognizerFailure()
            updatePreview(if (stopRequested) "در حال نهایی‌سازی آخرین جمله..." else "تلاش مجدد برای گوش دادن...")
            if (stopRequested) {
                stopResultSignal?.complete(Unit)
                return
            }
            if (utterance.hasText) scheduleCommitAfterQuietPeriod()
            consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(6)
            val backoff = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 120L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 350L
                else -> (250L * consecutiveErrors).coerceAtMost(1500L)
            }
            scheduleRestart(
                delayMs = backoff,
                recreateRecognizer = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_SERVER
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun updatePreview(message: String) {
        // Do not add a terminal period/question mark to every incomplete preview.
        val preview = PersianTextProcessor.processUtterance(
            utterance.preview,
            currentSettings.copy(autoPunctuation = false)
        )
        _telemetry.value = _telemetry.value.copy(
            livePartialText = preview,
            statusMessage = message
        )
    }

    /** Debounce commits across recognizer sessions, rather than on each partial/final. */
    private fun scheduleCommitAfterQuietPeriod() {
        pendingCommitJob?.cancel()
        if (!utterance.hasText) return
        pendingCommitJob = scope.launch(Dispatchers.Main.immediate) {
            val quietMs = currentSettings.silenceThresholdMs.coerceIn(1500L, 8000L)
            val elapsed = if (lastSpeechActivityMs > 0L) {
                SystemClock.elapsedRealtime() - lastSpeechActivityMs
            } else 0L
            // Repeated idle recognizer errors must not continually extend the deadline.
            delay((quietMs - elapsed).coerceIn(0L, quietMs))
            if (isSessionActive && !pausedForInterruption && !speechInProgress) {
                commitPendingUtterance()
            }
        }
    }

    private fun commitPendingUtterance(): Job? {
        val raw = utterance.finish().trim()
        if (raw.isBlank()) return null
        val now = SystemClock.elapsedRealtime()
        val command = VoiceCommandParser.parseCommandIfApplicable(
            rawTranscript = raw,
            commandsEnabled = currentSettings.voiceCommandsEnabled
        )
        if (command != null) {
            _telemetry.value = _telemetry.value.copy(livePartialText = "")
            return launchTracked { onVoiceCommand(command) }
        }

        val processed = PersianTextProcessor.processUtterance(raw, currentSettings)
        if (processed.isBlank()) return null
        val latency = if (lastSpeechActivityMs > 0L) {
            (now - lastSpeechActivityMs).coerceAtLeast(0L)
        } else 0L
        _telemetry.value = _telemetry.value.copy(
            livePartialText = "",
            lastCommittedText = processed,
            lastLatencyMs = latency,
            statusMessage = "یک جمله کامل ثبت شد • آماده جمله بعدی"
        )
        // Run immediately up to the first DB suspension so service captures its session id.
        val confidence = currentConfidence
        return launchTracked { onSentenceCommitted(processed, raw, confidence, latency) }
    }

    private fun launchTracked(block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
        outstandingWrites.add(job)
        job.invokeOnCompletion { mainHandler.post { outstandingWrites.remove(job) } }
        return job
    }

    private fun scheduleRestart(delayMs: Long, recreateRecognizer: Boolean = false) {
        restartJob?.cancel()
        restartJob = scope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            if (!isSessionActive || pausedForInterruption) return@launch
            if (recreateRecognizer) {
                runCatching {
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                }
                speechRecognizer = null
            }
            _telemetry.value = _telemetry.value.copy(
                restartCount = _telemetry.value.restartCount + 1
            )
            startRecognizerIfPossible()
        }
    }
}
