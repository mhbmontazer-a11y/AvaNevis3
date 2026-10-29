package ir.avanevis.study.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ir.avanevis.study.AvaNevisApp
import ir.avanevis.study.R
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.data.preferences.RecognitionEngineMode
import ir.avanevis.study.data.preferences.SpeechQualityProfile
import ir.avanevis.study.data.preferences.DigitDisplayMode
import ir.avanevis.study.domain.StudyVoiceCommand
import ir.avanevis.study.speech.ContinuousSpeechEngine
import ir.avanevis.study.speech.SpeechEngineTelemetry
import ir.avanevis.study.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * سرویس پیش‌زمینه (Foreground Service) با نوع MICROPHONE
 * تلاش می‌کند هنگام خاموش شدن صفحه نمایش یا رفتن کاربر به اپلیکیشن کتاب‌خوان/PDF،
 * شنیدن گفتار فارسی ادامه پیدا کند و از طریق اعلان دائمی قابل کنترل باشد. پیوستگی کامل به سرویس گفتار گوشی وابسته است.
 */
class StudyListeningService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): StudyListeningService = this@StudyListeningService
    }

    private val binder = LocalBinder()
    private lateinit var speechEngine: ContinuousSpeechEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var activeSessionId: Long = -1L
    private var activeNotebookId: Long = -1L
    private var timerJob: Job? = null
    private var stoppingJob: Job? = null
    private var settingsJob: Job? = null

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _recordedCount = MutableStateFlow(0)
    val recordedCount: StateFlow<Int> = _recordedCount.asStateFlow()

    // Lets the UI follow a session created by a voice command without polling.
    private val _activeSessionIdFlow = MutableStateFlow<Long?>(null)
    val activeSessionIdFlow: StateFlow<Long?> = _activeSessionIdFlow.asStateFlow()

    val telemetry: StateFlow<SpeechEngineTelemetry>
        get() = speechEngine.telemetry

    /** Reconnect speech provider without ending or duplicating the study session. */
    fun retrySpeechRecognition() {
        if (activeSessionId > 0L && stoppingJob?.isActive != true) {
            speechEngine.retryRecognition()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as AvaNevisApp
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        speechEngine = ContinuousSpeechEngine(
            context = applicationContext,
            scope = lifecycleScope,
            onSentenceCommitted = { processedText, rawText, confidence, _ ->
                // Capture the session BEFORE database suspension. A pending last sentence
                // must still reach the correct notebook when the user taps "Stop".
                val savingSessionId = activeSessionId
                val savingNotebookId = activeNotebookId
                if (savingSessionId > 0) {
                    app.database.studyDao().appendSentenceAutomatically(
                        sessionId = savingSessionId,
                        notebookId = savingNotebookId,
                        processedText = processedText,
                        rawText = rawText,
                        confidence = confidence
                    )
                    if (activeSessionId == savingSessionId) {
                        _recordedCount.value += 1
                        updateForegroundNotification(
                            status = "در حال گوش دادن • ${_recordedCount.value} نکته ثبت شد"
                        )
                    }
                }
            },
            onVoiceCommand = { command ->
                handleVoiceCommand(command)
            }
        )
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_STUDY -> {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                val notebookId = intent.getLongExtra(EXTRA_NOTEBOOK_ID, -1L)
                if (sessionId > 0) {
                    startForegroundStudySession(sessionId, notebookId, intent)
                }
            }
            ACTION_STOP_STUDY -> {
                stopForegroundStudySession()
            }
        }
        return START_STICKY
    }

    private fun startForegroundStudySession(sessionId: Long, notebookId: Long, startIntent: Intent) {
        if (stoppingJob?.isActive == true) return
        if (activeSessionId > 0L) return // avoid overwriting a live session on repeated intents
        activeSessionId = sessionId
        activeNotebookId = notebookId
        _activeSessionIdFlow.value = sessionId
        _elapsedSeconds.value = 0L
        _recordedCount.value = 0

        val notification = buildNotification("در حال گوش دادن به مطالعه شما...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireSafetyWakeLock()
        registerAudioFocusListener()

        val defaults = StudySettings()
        val initialSettings = defaults.copy(
            silenceThresholdMs = startIntent.getLongExtra(EXTRA_QUIET_MS, defaults.silenceThresholdMs),
            onlineServerUrl = startIntent.getStringExtra(EXTRA_ONLINE_SERVER_URL) ?: "",
            onlineAccessToken = startIntent.getStringExtra(EXTRA_ONLINE_ACCESS_TOKEN) ?: "",
            engineMode = runCatching {
                RecognitionEngineMode.valueOf(
                    startIntent.getStringExtra(EXTRA_ENGINE_MODE) ?: defaults.engineMode.name
                )
            }.getOrDefault(defaults.engineMode),
            voiceCommandsEnabled = startIntent.getBooleanExtra(EXTRA_VOICE_COMMANDS, defaults.voiceCommandsEnabled),
            autoPunctuation = startIntent.getBooleanExtra(EXTRA_AUTO_PUNCTUATION, defaults.autoPunctuation),
            fixHalfSpace = startIntent.getBooleanExtra(EXTRA_FIX_HALF_SPACE, defaults.fixHalfSpace),
            digitMode = runCatching {
                DigitDisplayMode.valueOf(
                    startIntent.getStringExtra(EXTRA_DIGIT_MODE) ?: defaults.digitMode.name
                )
            }.getOrDefault(defaults.digitMode),
            speechQualityProfile = runCatching {
                SpeechQualityProfile.valueOf(
                    startIntent.getStringExtra(EXTRA_SPEECH_PROFILE) ?: defaults.speechQualityProfile.name
                )
            }.getOrDefault(defaults.speechQualityProfile)
        )
        // Start right after foreground/mic-focus setup. Previously we waited for
        // DataStore.first() before even opening the recognizer, losing initial words.
        speechEngine.startContinuousSession(initialSettings)
        val app = application as AvaNevisApp
        settingsJob?.cancel()
        settingsJob = lifecycleScope.launch {
            app.settingsRepository.settingsFlow.collect { updatedSettings ->
                speechEngine.updateSettings(updatedSettings)
            }
        }

        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000L)
                _elapsedSeconds.value += 1L
            }
        }
    }

    fun stopForegroundStudySession() {
        if (stoppingJob?.isActive == true) return
        timerJob?.cancel()
        settingsJob?.cancel()
        stoppingJob = lifecycleScope.launch {
            // Don't kill the service before its final pending sentence is stored.
            speechEngine.stopContinuousSession()?.join()
            releaseSafetyWakeLock()
            abandonAudioFocusListener()

            val finishingSessionId = activeSessionId
            if (finishingSessionId > 0) {
                val dao = (application as AvaNevisApp).database.studyDao()
                val current = dao.getSessionById(finishingSessionId)
                if (current != null) {
                    dao.updateSession(
                        current.copy(
                            endedAt = System.currentTimeMillis(),
                            durationSeconds = current.durationSeconds + _elapsedSeconds.value
                        )
                    )
                }
            }

            activeSessionId = -1L
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun handleVoiceCommand(command: StudyVoiceCommand) {
        val app = application as AvaNevisApp
        val dao = app.database.studyDao()
        when (command) {
            StudyVoiceCommand.DELETE_LAST_SENTENCE -> {
                if (activeSessionId > 0) {
                    val last = dao.getLastSentenceInSession(activeSessionId)
                    if (last != null) {
                        dao.deleteSentenceAndRefreshCount(last)
                        _recordedCount.value = (_recordedCount.value - 1).coerceAtLeast(0)
                    }
                }
            }
            StudyVoiceCommand.STOP_STUDY_SESSION -> {
                stopForegroundStudySession()
            }
            StudyVoiceCommand.NEW_PARAGRAPH_LINE -> {
                // هر جمله به‌طور خودکار در خط جدید ذخیره می‌شود؛ این فرمان یک جداکننده بخش ایجاد می‌کند
                if (activeSessionId > 0) {
                    dao.appendSentenceAutomatically(
                        sessionId = activeSessionId,
                        notebookId = activeNotebookId,
                        processedText = "─── بخش جدید ───",
                        rawText = "خط بعد",
                        confidence = 1.0f
                    )
                }
            }
            StudyVoiceCommand.NEW_NOTEBOOK_SESSION -> {
                if (activeNotebookId > 0) {
                    val previousSessionId = activeSessionId
                    if (previousSessionId > 0) {
                        val previous = dao.getSessionById(previousSessionId)
                        if (previous != null) {
                            dao.updateSession(
                                previous.copy(
                                    endedAt = System.currentTimeMillis(),
                                    durationSeconds = previous.durationSeconds + _elapsedSeconds.value
                                )
                            )
                        }
                    }
                    val newSessionId = dao.insertSession(
                        ir.avanevis.study.data.local.StudySessionEntity(
                            notebookId = activeNotebookId,
                            title = "یادداشت مطالعه جدید"
                        )
                    )
                    activeSessionId = newSessionId
                    _activeSessionIdFlow.value = newSessionId
                    _elapsedSeconds.value = 0L
                    _recordedCount.value = 0
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // تماس تلفنی یا ضبط صوتی در برنامه دیگر: توقف ایمن و ثبت جمله ناتمام
                speechEngine.pauseForAudioInterruption()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                speechEngine.resumeAfterAudioInterruption()
            }
        }
    }

    private fun registerAudioFocusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = req
            audioManager?.requestAudioFocus(req)
        }
    }

    private fun abandonAudioFocusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
        audioFocusRequest = null
    }

    private fun acquireSafetyWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AvaNevisStudy::ContinuousListeningWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // حداکثر ۴ ساعت برای یک جلسه مطالعه پیوسته
        }
    }

    private fun releaseSafetyWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun updateForegroundNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StudyListeningService::class.java).apply {
            action = ACTION_STOP_STUDY
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AvaNevisApp.STUDY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_study),
                stopPending
            )
            .build()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        settingsJob?.cancel()
        releaseSafetyWakeLock()
        abandonAudioFocusListener()
        speechEngine.stopContinuousSession()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_STUDY = "ir.avanevis.study.action.START_STUDY"
        const val ACTION_STOP_STUDY = "ir.avanevis.study.action.STOP_STUDY"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_NOTEBOOK_ID = "extra_notebook_id"
        const val EXTRA_QUIET_MS = "extra_quiet_ms"
        const val EXTRA_ENGINE_MODE = "extra_engine_mode"
        const val EXTRA_ONLINE_SERVER_URL = "extra_online_server_url"
        const val EXTRA_ONLINE_ACCESS_TOKEN = "extra_online_access_token"
        const val EXTRA_VOICE_COMMANDS = "extra_voice_commands"
        const val EXTRA_AUTO_PUNCTUATION = "extra_auto_punctuation"
        const val EXTRA_FIX_HALF_SPACE = "extra_fix_half_space"
        const val EXTRA_SPEECH_PROFILE = "extra_speech_profile"
        const val EXTRA_DIGIT_MODE = "extra_digit_mode"
        private const val NOTIFICATION_ID = 1001
    }
}
