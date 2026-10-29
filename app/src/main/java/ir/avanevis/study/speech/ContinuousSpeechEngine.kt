package ir.avanevis.study.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.StudyVoiceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

/** Online-only microphone capture. Sends 8-second PCM windows as WAV to a user-configured HTTPS backend.
 * No Google voice provider, Vosk, Whisper native libraries or API secret is stored in the APK.
 * Uploads are sequential and bounded: network failures are reported, never silently dropped.
 */
class ContinuousSpeechEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSentenceCommitted: suspend (String, String, Float, Long) -> Unit,
    private val onVoiceCommand: suspend (StudyVoiceCommand) -> Unit
) {
    private val _telemetry = MutableStateFlow(SpeechEngineTelemetry())
    val telemetry: StateFlow<SpeechEngineTelemetry> = _telemetry.asStateFlow()
    private val active = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var worker: Job? = null
    private var recorder: AudioRecord? = null
    @Volatile private var settings = StudySettings()
    private val uploader = OnlineTranscriptionClient()

    fun updateSettings(value: StudySettings) { settings = value }
    fun retryRecognition() { if (!active.get()) startContinuousSession(settings) }
    fun takeWhisperReviewAudio(): java.io.File? = null
    fun startContinuousSession(config: StudySettings) {
        if (!active.compareAndSet(false, true)) return
        settings = config
        paused.set(false)
        _telemetry.value = SpeechEngineTelemetry(state = ListeningState.STARTING, statusMessage = "در حال اتصال به موتور آنلاین فارسی…")
        worker = scope.launch(Dispatchers.IO) { captureLoop() }
    }
    fun stopContinuousSession(): Job? {
        active.set(false)
        runCatching { recorder?.stop() }
        return worker
    }
    fun pauseForAudioInterruption() {
        paused.set(true)
        _telemetry.value = _telemetry.value.copy(state = ListeningState.PAUSED_BY_CALL, statusMessage = "مکث به علت تماس یا اشغال میکروفون")
    }
    fun resumeAfterAudioInterruption() {
        paused.set(false)
        _telemetry.value = _telemetry.value.copy(state = ListeningState.LISTENING_READY, statusMessage = "در حال ضبط فارسی")
    }
    private suspend fun captureLoop() {
        var audio: AudioRecord? = null
        try {
            val endpoint = settings.onlineServerUrl.trim().trimEnd('/')
            require(endpoint.startsWith("https://") && endpoint.length > 12) {
                "ابتدا در تنظیمات، نشانی HTTPS سرور آنلاین را وارد کنید"
            }
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "مجوز میکروفون صادر نشده است"
            }
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "ضبط صوت ۱۶ کیلوهرتز در دسترس نیست" }
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "میکروفون راه‌اندازی نشد" }
            recorder = audio
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "میکروفون شروع به ضبط نکرد" }
            _telemetry.value = _telemetry.value.copy(state = ListeningState.LISTENING_READY, statusMessage = "در حال ضبط؛ ارسال هر ۸ ثانیه")
            // Separate capture from network IO. Never block AudioRecord while a request is in flight.
            // Bounded queue: fail explicitly if internet cannot keep up with real-time audio.
            val queue = Channel<ByteArray>(capacity = 6)
            val uploadError = AtomicReference<String?>(null)
            val sender = scope.launch(Dispatchers.IO) {
                try {
                    for (pcm in queue) sendChunk(endpoint, pcm)
                } catch (e: Exception) {
                    uploadError.set(e.message ?: "خطا در ارتباط با سرور")
                }
            }
            val buffer = ByteArray(4096)
            val chunk = ByteArrayOutputStream(256000)
            var lastLevelAt = 0L
            while (active.get()) {
                val count = audio.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    if (active.get()) throw IllegalStateException("خطا در خواندن میکروفون: $count")
                    break
                }
                if (paused.get()) continue
                chunk.write(buffer, 0, count)
                val now = System.currentTimeMillis()
                if (now - lastLevelAt > 160L) {
                    _telemetry.value = _telemetry.value.copy(currentRmsDb = PcmSignalLevel.rmsDbFs(buffer, count))
                    lastLevelAt = now
                }
                if (chunk.size() >= 16000 * 2 * 8) {
                    val pcm = chunk.toByteArray()
                    chunk.reset()
                    if (queue.trySend(pcm).isFailure) {
                        throw IllegalStateException("اینترنت برای پردازش هم‌زمان کافی نیست؛ صف صوت پر شده است")
                    }
                    if (sender.isCompleted) {
                        throw IllegalStateException(uploadError.get() ?: "اتصال به سرور قطع شد")
                    }
                }
            }
            if (chunk.size() >= 16000 * 2 / 2) queue.send(chunk.toByteArray())
            queue.close()
            sender.join()
            uploadError.get()?.let { throw IllegalStateException(it) }
        } catch (e: Exception) {
            _telemetry.value = _telemetry.value.copy(state = ListeningState.RECOVERING_ERROR,
                statusMessage = e.message ?: "خطا در تشخیص آنلاین", isRecoveryRequired = true)
        } finally {
            active.set(false)
            runCatching { audio?.stop() }
            audio?.release()
            recorder = null
            if (_telemetry.value.state != ListeningState.RECOVERING_ERROR) {
                _telemetry.value = _telemetry.value.copy(state = ListeningState.IDLE, statusMessage = "ضبط پایان یافت")
            }
        }
    }
    private suspend fun sendChunk(endpoint: String, pcm: ByteArray) {
        _telemetry.value = _telemetry.value.copy(statusMessage = "در حال تبدیل صوت در سرور…")
        val start = System.currentTimeMillis()
        // A failed upload is surfaced to the user. It is not silently counted as transcribed.
        val result = withContext(Dispatchers.IO) { uploader.transcribe(endpoint, settings.onlineAccessToken, pcm) }
        if (result.isNotBlank()) {
            onSentenceCommitted(result, result, -1f, System.currentTimeMillis() - start)
            _telemetry.value = _telemetry.value.copy(lastCommittedText = result, livePartialText = "",
                lastLatencyMs = System.currentTimeMillis() - start)
        }
        _telemetry.value = _telemetry.value.copy(statusMessage = "در حال ضبط؛ ارسال هر ۸ ثانیه")
    }
}
