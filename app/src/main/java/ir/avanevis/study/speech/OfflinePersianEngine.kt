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
import ir.avanevis.study.domain.StudyVoiceCommand
import ir.avanevis.study.domain.VoiceCommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Independent 16 kHz PCM -> Vosk Persian streaming path.
 * No Google SpeechRecognizer, network or provider language pack is required.
 * The model is bundled into the APK by GitHub Actions and extracted once on device.
 */
internal class OfflinePersianEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val telemetry: MutableStateFlow<SpeechEngineTelemetry>,
    private val onSentence: suspend (String, String, Float, Long) -> Unit,
    private val onCommand: suspend (StudyVoiceCommand) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var worker: Job? = null
    private var recorder: AudioRecord? = null
    private var settings = StudySettings()
    private var lastPartial = ""
    private var lastRmsAt = 0L
    private var sessionToken = 0L
    private var silenceSinceMs = 0L
    private var samplesRead = 0L

    fun start(config: StudySettings) {
        if (!running.compareAndSet(false, true)) return
        settings = config
        paused.set(false)
        lastPartial = ""
        lastRmsAt = 0L
        silenceSinceMs = 0L
        samplesRead = 0L
        sessionToken++
        telemetry.value = SpeechEngineTelemetry(state = ListeningState.STARTING, statusMessage = "در حال بارگذاری موتور مستقل فارسی…")
        val token = sessionToken
        worker = scope.launch(Dispatchers.IO) { recognizeLoop(token) }
    }

    fun retry() {
        if (running.get()) return
        start(settings)
    }

    fun pause() {
        paused.set(true)
        telemetry.value = telemetry.value.copy(state = ListeningState.PAUSED_BY_CALL, statusMessage = "میکروفون موقتاً متوقف شد")
    }

    fun resume() {
        paused.set(false)
        telemetry.value = telemetry.value.copy(state = ListeningState.LISTENING_READY, statusMessage = "موتور فارسی آماده است")
    }

    fun stop(): Job? {
        running.set(false)
        // AudioRecord.read may be blocking; stopping it releases the worker promptly.
        runCatching { recorder?.stop() }
        return worker
    }

    private suspend fun recognizeLoop(token: Long) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("مجوز میکروفون صادر نشده است")
            }
            val modelDir = installModelIfNeeded()
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, 16000f)
            val minSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minSize > 0) { "ضبط صوت ۱۶ کیلوهرتز روی گوشی در دسترس نیست" }
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minSize * 2, 8192)
            )
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "میکروفون راه‌اندازی نشد" }
            recorder = audio
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "ضبط صدا شروع نشد" }
            telemetry.value = telemetry.value.copy(state = ListeningState.LISTENING_READY, statusMessage = "در حال شنیدن فارسی (آفلاین)")
            val buffer = ByteArray(4096)
            while (running.get() && token == sessionToken) {
                val bytes = audio.read(buffer, 0, buffer.size)
                if (bytes <= 0) {
                    if (running.get() && token == sessionToken) throw IllegalStateException("خطا در دریافت صدا: $bytes")
                    break
                }
                if (paused.get()) {
                    // Feed silence rather than stale speech into Vosk after interruptions.
                    lastPartial = ""
                    continue
                }
                samplesRead += bytes / 2
                val now = System.currentTimeMillis()
                if (now - lastRmsAt >= 130L) {
                    val rms = PcmSignalLevel.rmsDbFs(buffer, bytes)
                    telemetry.value = telemetry.value.copy(currentRmsDb = rms)
                    lastRmsAt = now
                    if (rms < -58f) {
                        if (silenceSinceMs == 0L) silenceSinceMs = now
                        if (now - silenceSinceMs > 12_000L && samplesRead > 16000L * 12) {
                            telemetry.value = telemetry.value.copy(statusMessage = "صدای ورودی بسیار ضعیف است؛ میکروفون و مسیر صدا را بررسی کنید")
                        }
                    } else silenceSinceMs = 0L
                }
                if (recognizer.acceptWaveForm(buffer, bytes)) {
                    val sentence = JSONObject(recognizer.result).optString("text").trim()
                    commit(sentence)
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                    if (partial != lastPartial) {
                        lastPartial = partial
                        telemetry.value = telemetry.value.copy(
                            state = if (partial.isBlank()) ListeningState.LISTENING_READY else ListeningState.SPEECH_DETECTED,
                            livePartialText = partial,
                            statusMessage = if (partial.isBlank()) "در حال شنیدن فارسی (آفلاین)" else "در حال تبدیل گفتار فارسی…"
                        )
                    }
                }
                // Vosk's endpoint detection normally finalizes utterances on silence.
                // Never discard a partial solely because a UI timer elapsed.
            }
            commit(JSONObject(recognizer.finalResult).optString("text").trim())
            if (token == sessionToken) telemetry.value = SpeechEngineTelemetry(state = ListeningState.IDLE, statusMessage = "ضبط آفلاین پایان یافت")
        } catch (t: Exception) {
            if (running.get() && token == sessionToken) telemetry.value = telemetry.value.copy(
                state = ListeningState.RECOVERING_ERROR,
                statusMessage = "موتور فارسی: ${t.message ?: "خطای نامشخص"}",
                isRecoveryRequired = true
            )
        } finally {
            if (token == sessionToken) running.set(false)
            if (recorder === audio) recorder = null
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }
    }

    private suspend fun commit(raw: String) {
        if (raw.isBlank()) return
        lastPartial = ""
        val started = System.currentTimeMillis()
        val command = if (settings.voiceCommandsEnabled) VoiceCommandParser.parseCommandIfApplicable(raw, settings.voiceCommandsEnabled) else null
        if (command != null) {
            onCommand(command)
        } else {
            val processed = PersianTextProcessor.processUtterance(raw, settings)
            onSentence(processed, raw, -1f, System.currentTimeMillis() - started)
        }
        telemetry.value = telemetry.value.copy(
            livePartialText = "", lastCommittedText = raw,
            state = ListeningState.LISTENING_READY, statusMessage = "جمله ثبت شد؛ در حال شنیدن فارسی…"
        )
    }

    private fun installModelIfNeeded(): File {
        val folder = "vosk-model-small-fa-0.42"
        val target = File(context.filesDir, folder)
        if (File(target, "am/final.mdl").isFile && File(target, "conf/model.conf").isFile) return target
        val temporary = File(context.filesDir, "$folder-installing")
        temporary.deleteRecursively()
        temporary.mkdirs()
        try {
            copyAssets(folder, temporary)
            check(File(temporary, "am/final.mdl").isFile && File(temporary, "conf/model.conf").isFile) {
                "مدل فارسی داخل APK نیست؛ باید نسخه ساخته‌شده با GitHub Actions را نصب کنید"
            }
            target.deleteRecursively()
            check(temporary.renameTo(target)) { "ذخیره مدل فارسی ممکن نشد؛ فضای خالی را بررسی کنید" }
        } catch (e: Exception) {
            temporary.deleteRecursively()
            throw e
        }
        return target
    }

    private fun copyAssets(assetPath: String, output: File) {
        val entries = context.assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            output.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(output).use { dest -> input.copyTo(dest, 65536) }
            }
        } else {
            output.mkdirs()
            for (entry in entries) copyAssets("$assetPath/$entry", File(output, entry))
        }
    }
}
