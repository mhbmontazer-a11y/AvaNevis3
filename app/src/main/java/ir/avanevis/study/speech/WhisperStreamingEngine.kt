package ir.avanevis.study.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.PersianTextProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One PCM producer + one ordered, crash-recoverable Whisper consumer.
 * No ASR fallback, no bounded drop queue, no unbounded in-memory audio buffer.
 * A chunk is removed ONLY after its text and receipt are committed in Room.
 */
internal class WhisperStreamingEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val telemetry: kotlinx.coroutines.flow.MutableStateFlow<SpeechEngineTelemetry>,
    private val sessionId: Long,
    private val notebookId: Long,
    private val onChunk: suspend (chunkId: String, processed: String, raw: String, latencyMs: Long) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val processingFailed = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var job: Job? = null
    @Volatile private var settings = StudySettings()
    private val store = WhisperChunkStore(context)

    fun updateSettings(value: StudySettings) { settings = value }
    fun start(config: StudySettings) {
        if (!running.compareAndSet(false, true)) return
        settings = config
        paused.set(false)
        processingFailed.set(false)
        telemetry.value = SpeechEngineTelemetry(state = ListeningState.STARTING, statusMessage = "در حال بازیابی و راه‌اندازی Whisper…")
        job = scope.launch(Dispatchers.IO) { recordAndRecognize() }
    }
    fun retry() { if (!running.get()) start(settings) }
    fun pause() { paused.set(true); telemetry.value = telemetry.value.copy(state = ListeningState.PAUSED_BY_CALL) }
    fun resume() { paused.set(false); telemetry.value = telemetry.value.copy(state = ListeningState.LISTENING_READY) }
    fun stop(): Job? {
        running.set(false)
        runCatching { recorder?.stop() }
        return job
    }

    private suspend fun recordAndRecognize() {
        val queue = Channel<File>(Channel.UNLIMITED) // only filenames; PCM stays on disk
        var audio: AudioRecord? = null
        var capture: WhisperChunkStore.Capture? = null
        val decoder = scope.launch(Dispatchers.IO) {
            val whisper = WhisperReviewEngine(context)
            var lastRaw = ""
            try {
            for (chunk in queue) {
                try {
                    // Persisted receipt makes reprocessing idempotent even after a crash
                    // between database commit and file deletion.
                    val id = chunk.name.removeSuffix(".pcm")
                    val started = System.currentTimeMillis()
                    val raw = whisper.transcribeFile(chunk).trim()
                    // Deduplicate ONLY where capture explicitly inserted PCM overlap.
                    val deduplicated = if (id.contains("_ov1_"))
                        WhisperTextJoiner.removeRepeatedPrefix(lastRaw, raw, 8) else raw
                    val processed = PersianTextProcessor.processUtterance(deduplicated, settings)
                    // Even empty / silent chunks must be acknowledged transactionally.
                    onChunk(id, processed, deduplicated, System.currentTimeMillis() - started)
                    if (raw.isNotBlank()) lastRaw = raw
                    check(chunk.delete() || !chunk.exists()) { "فایل پردازش‌شده پاک نشد" }
                    telemetry.value = telemetry.value.copy(lastCommittedText = processed, livePartialText = "",
                        statusMessage = "قطعهٔ صوتی پردازش و ذخیره شد")
                } catch (e: Exception) {
                    telemetry.value = telemetry.value.copy(state = ListeningState.RECOVERING_ERROR,
                        statusMessage = "قطعهٔ صوتی برای بازیابی حفظ شد: ${e.message}", isRecoveryRequired = true)
                    // Stop capture as well; otherwise unlimited pending audio can exhaust disk.
                    processingFailed.set(true)
                    running.set(false)
                    runCatching { recorder?.stop() }
                    break
                }
            }
            } finally { whisper.release() }
        }
        try {
            // Recover BEFORE opening microphone, and do not re-enqueue files twice.
            check(WhisperModelManager.verifyInstalled(context)) { "مدل Whisper خراب یا تأییدنشده است" }
            store.recover().forEach { check(queue.trySend(it).isSuccess) }
            check(WhisperModelManager.isInstalled(context)) { "ابتدا مدل Whisper Turbo را دریافت کنید" }
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "مجوز میکروفون صادر نشده" }
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "ضبط ۱۶ کیلوهرتز پشتیبانی نمی‌شود" }
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "میکروفون راه‌اندازی نشد" }
            recorder = audio
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "میکروفون شروع به ضبط نکرد" }
            telemetry.value = SpeechEngineTelemetry(state = ListeningState.LISTENING_READY, statusMessage = "در حال ضبط آفلاین Whisper")
            val buffer = ByteArray(4096)
            var silentSamples = 0L
            var voiced = false
            var lastSyncAt = System.currentTimeMillis()
            var lastLevelAt = 0L
            var tail = ByteArray(0)
            var overlapNext = false
            val overlapBytes = 16000 * 2 * 400 / 1000
            var recentAudio = ByteArray(0)
            fun finishChunk() {
                val current = capture ?: return
                capture = null
                val size = current.sizeBytes
                val file = current.finish()
                silentSamples = 0L
                voiced = false
                if (file != null && size > 0) {
                    check(queue.trySend(file).isSuccess) { "صف صوت بسته شده است" }
                    // No PCM deletion for short/silent files: all captured data is retained.
                    telemetry.value = telemetry.value.copy(statusMessage = "قطعه در صف Whisper")
                }
            }
            while (running.get()) {
                val n = audio.read(buffer, 0, buffer.size)
                if (n <= 0) { if (running.get()) error("خطای میکروفون: $n") else break }
                if (paused.get()) { finishChunk(); tail = ByteArray(0); overlapNext = false; recentAudio = ByteArray(0); continue }
                if (capture == null) {
                    capture = store.openCapture(sessionId, notebookId, overlapNext)
                    overlapNext = false
                    if (tail.isNotEmpty()) capture!!.append(tail, tail.size)
                }
                capture!!.append(buffer, n)
                // Maintain the last 400 ms of *all* captured PCM, not just one read.
                recentAudio = (recentAudio + buffer.copyOf(n)).let { data ->
                    if (data.size > overlapBytes) data.copyOfRange(data.size - overlapBytes, data.size) else data
                }
                val rms = PcmSignalLevel.rmsDbFs(buffer, n)
                val active = rms > -43f
                if (active) { voiced = true; silentSamples = 0L } else silentSamples += n / 2
                val now = System.currentTimeMillis()
                if (now - lastSyncAt >= 1000L) { capture!!.sync(); lastSyncAt = now }
                if (now - lastLevelAt >= 160L) {
                    telemetry.value = telemetry.value.copy(currentRmsDb = rms,
                        state = if (active) ListeningState.SPEECH_DETECTED else ListeningState.LISTENING_READY,
                        livePartialText = if (active) "در حال دریافت گفتار…" else "")
                    lastLevelAt = now
                }
                // Keep a 400ms audio overlap only on hard cuts (long continuous speech).
                // Text overlap is removed conservatively by WhisperTextJoiner.
                val hardCut = capture!!.sizeBytes >= 18L * 16000 * 2
                val quietCut = voiced && silentSamples >= 16000L && capture!!.sizeBytes >= 32000L
                if (hardCut || quietCut) {
                    tail = if (hardCut) {
                        recentAudio.copyOf()
                    } else ByteArray(0)
                    finishChunk()
                    overlapNext = hardCut && tail.isNotEmpty()
                    recentAudio = ByteArray(0)
                }
                // Preserve low-level audio as chunks rather than dropping silence buffers.
                if (!voiced && capture != null && capture!!.sizeBytes >= 4L * 16000 * 2) {
                    tail = ByteArray(0); finishChunk(); recentAudio = ByteArray(0); overlapNext = false
                }
            }
            finishChunk()
            if (processingFailed.get()) error("پردازش Whisper متوقف شد؛ فایل‌های صوتی برای بازیابی نگهداری شدند")
        } catch (e: Exception) {
            capture?.abortWithoutDeleting() // recovered from .recording on next launch
            telemetry.value = telemetry.value.copy(state = ListeningState.RECOVERING_ERROR,
                statusMessage = e.message ?: "خطای ضبط Whisper", isRecoveryRequired = true)
        } finally {
            queue.close()
            decoder.join() // Never end the service before accepted chunks have been handled.
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            if (recorder === audio) recorder = null
            running.set(false)
            if (!telemetry.value.isRecoveryRequired) telemetry.value = telemetry.value.copy(state = ListeningState.IDLE, statusMessage = "ضبط پایان یافت")
        }
    }
}
