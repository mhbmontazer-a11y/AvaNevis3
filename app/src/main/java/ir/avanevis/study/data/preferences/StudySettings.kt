package ir.avanevis.study.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "study_settings")

enum class RecognitionEngineMode {
    // Legacy setting; now routed to built-in Vosk Persian for safe upgrades.
    HYBRID_SMART,
    // Built-in Vosk Persian model; does not need Android speech services.
    OFFLINE_ONLY,
    // Android provider fallback, only when user explicitly disables Vosk.
    ONLINE_HIGH_ACCURACY
}

/** Different latency/recognition-provider request profiles; none guarantees transcription accuracy. */
enum class SpeechQualityProfile { QUICK, BALANCED, CAREFUL }

enum class AccentPalette { OCEAN, VIOLET, MINT, SUNSET, ROSE, AMBER }

enum class DigitDisplayMode {
    PERSIAN, // ۰۱۲۳۴۵۶۷۸۹
    LATIN    // 0123456789
}

data class StudySettings(
    val silenceThresholdMs: Long = 3500L,      // مدت سکوت برای تشخیص پایان جمله (میلی‌ثانیه)
    val vadSensitivityDb: Float = 3.5f,        // آستانه RMS تشخیص فعالیت صوتی
    val engineMode: RecognitionEngineMode = RecognitionEngineMode.ONLINE_HIGH_ACCURACY,
    val onlineServerUrl: String = "",
    val onlineAccessToken: String = "",
    val fontSizeSp: Int = 18,
    val autoNumbering: Boolean = true,
    val autoPunctuation: Boolean = true,
    val fixHalfSpace: Boolean = true,
    val digitMode: DigitDisplayMode = DigitDisplayMode.PERSIAN,
    val voiceCommandsEnabled: Boolean = true,
    val keepScreenOnDuringStudy: Boolean = false,
    val darkTheme: Boolean = false,
    val accentPalette: AccentPalette = AccentPalette.OCEAN,
    val maxStartupRetries: Int = 3,
    val showRecognitionConfidence: Boolean = false,
    val speechQualityProfile: SpeechQualityProfile = SpeechQualityProfile.BALANCED,
    val whisperReviewEnabled: Boolean = false
)

class StudySettingsRepository(private val context: Context) {

    private object Keys {
        val SILENCE_MS = longPreferencesKey("silence_threshold_ms")
        val VAD_DB = floatPreferencesKey("vad_sensitivity_db")
        val ENGINE_MODE = stringPreferencesKey("engine_mode")
        val ONLINE_SERVER_URL = stringPreferencesKey("online_server_url")
        val ONLINE_ACCESS_TOKEN = stringPreferencesKey("online_access_token")
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val AUTO_NUMBERING = booleanPreferencesKey("auto_numbering")
        val AUTO_PUNCTUATION = booleanPreferencesKey("auto_punctuation")
        val FIX_HALF_SPACE = booleanPreferencesKey("fix_half_space")
        val DIGIT_MODE = stringPreferencesKey("digit_mode")
        val VOICE_COMMANDS = booleanPreferencesKey("voice_commands_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ACCENT_PALETTE = stringPreferencesKey("accent_palette")
        val MAX_STARTUP_RETRIES = intPreferencesKey("max_startup_retries")
        val SHOW_CONFIDENCE = booleanPreferencesKey("show_recognition_confidence")
        val SPEECH_PROFILE = stringPreferencesKey("speech_quality_profile")
        val WHISPER_REVIEW = booleanPreferencesKey("whisper_review_enabled")
    }

    val settingsFlow: Flow<StudySettings> = context.dataStore.data.map { prefs ->
        StudySettings(
            silenceThresholdMs = prefs[Keys.SILENCE_MS] ?: 3500L,
            vadSensitivityDb = prefs[Keys.VAD_DB] ?: 3.5f,
            engineMode = RecognitionEngineMode.ONLINE_HIGH_ACCURACY,
            onlineServerUrl = prefs[Keys.ONLINE_SERVER_URL] ?: "",
            onlineAccessToken = prefs[Keys.ONLINE_ACCESS_TOKEN] ?: "",
            fontSizeSp = prefs[Keys.FONT_SIZE] ?: 18,
            autoNumbering = prefs[Keys.AUTO_NUMBERING] ?: true,
            autoPunctuation = prefs[Keys.AUTO_PUNCTUATION] ?: true,
            fixHalfSpace = prefs[Keys.FIX_HALF_SPACE] ?: true,
            digitMode = prefs[Keys.DIGIT_MODE]?.let {
                runCatching { DigitDisplayMode.valueOf(it) }.getOrNull()
            } ?: DigitDisplayMode.PERSIAN,
            voiceCommandsEnabled = prefs[Keys.VOICE_COMMANDS] ?: true,
            keepScreenOnDuringStudy = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            darkTheme = prefs[Keys.DARK_THEME] ?: false,
            accentPalette = prefs[Keys.ACCENT_PALETTE]?.let {
                runCatching { AccentPalette.valueOf(it) }.getOrNull()
            } ?: AccentPalette.OCEAN,
            maxStartupRetries = (prefs[Keys.MAX_STARTUP_RETRIES] ?: 3).coerceIn(1, 6),
            showRecognitionConfidence = prefs[Keys.SHOW_CONFIDENCE] ?: false,
            speechQualityProfile = prefs[Keys.SPEECH_PROFILE]?.let { runCatching { SpeechQualityProfile.valueOf(it) }.getOrNull() } ?: SpeechQualityProfile.BALANCED,
            whisperReviewEnabled = prefs[Keys.WHISPER_REVIEW] ?: false
        )
    }

    suspend fun updateSettings(transform: (StudySettings) -> StudySettings) {
        context.dataStore.edit { prefs ->
            val current = StudySettings(
                silenceThresholdMs = prefs[Keys.SILENCE_MS] ?: 3500L,
                vadSensitivityDb = prefs[Keys.VAD_DB] ?: 3.5f,
                engineMode = RecognitionEngineMode.ONLINE_HIGH_ACCURACY,
                onlineServerUrl = prefs[Keys.ONLINE_SERVER_URL] ?: "",
            onlineAccessToken = prefs[Keys.ONLINE_ACCESS_TOKEN] ?: "",
                fontSizeSp = prefs[Keys.FONT_SIZE] ?: 18,
                autoNumbering = prefs[Keys.AUTO_NUMBERING] ?: true,
                autoPunctuation = prefs[Keys.AUTO_PUNCTUATION] ?: true,
                fixHalfSpace = prefs[Keys.FIX_HALF_SPACE] ?: true,
                digitMode = prefs[Keys.DIGIT_MODE]?.let {
                    runCatching { DigitDisplayMode.valueOf(it) }.getOrNull()
                } ?: DigitDisplayMode.PERSIAN,
                voiceCommandsEnabled = prefs[Keys.VOICE_COMMANDS] ?: true,
                keepScreenOnDuringStudy = prefs[Keys.KEEP_SCREEN_ON] ?: false,
                darkTheme = prefs[Keys.DARK_THEME] ?: false,
                accentPalette = prefs[Keys.ACCENT_PALETTE]?.let {
                    runCatching { AccentPalette.valueOf(it) }.getOrNull()
                } ?: AccentPalette.OCEAN,
                maxStartupRetries = (prefs[Keys.MAX_STARTUP_RETRIES] ?: 3).coerceIn(1, 6),
                showRecognitionConfidence = prefs[Keys.SHOW_CONFIDENCE] ?: false,
            speechQualityProfile = prefs[Keys.SPEECH_PROFILE]?.let { runCatching { SpeechQualityProfile.valueOf(it) }.getOrNull() } ?: SpeechQualityProfile.BALANCED,
            whisperReviewEnabled = prefs[Keys.WHISPER_REVIEW] ?: false
            )
            val updated = transform(current)
            prefs[Keys.SILENCE_MS] = updated.silenceThresholdMs
            prefs[Keys.VAD_DB] = updated.vadSensitivityDb
            prefs[Keys.ENGINE_MODE] = RecognitionEngineMode.ONLINE_HIGH_ACCURACY.name
            prefs[Keys.ONLINE_SERVER_URL] = updated.onlineServerUrl
            prefs[Keys.ONLINE_ACCESS_TOKEN] = updated.onlineAccessToken
            prefs[Keys.FONT_SIZE] = updated.fontSizeSp
            prefs[Keys.AUTO_NUMBERING] = updated.autoNumbering
            prefs[Keys.AUTO_PUNCTUATION] = updated.autoPunctuation
            prefs[Keys.FIX_HALF_SPACE] = updated.fixHalfSpace
            prefs[Keys.DIGIT_MODE] = updated.digitMode.name
            prefs[Keys.VOICE_COMMANDS] = updated.voiceCommandsEnabled
            prefs[Keys.KEEP_SCREEN_ON] = updated.keepScreenOnDuringStudy
            prefs[Keys.DARK_THEME] = updated.darkTheme
            prefs[Keys.ACCENT_PALETTE] = updated.accentPalette.name
            prefs[Keys.MAX_STARTUP_RETRIES] = updated.maxStartupRetries.coerceIn(1, 6)
            prefs[Keys.SHOW_CONFIDENCE] = updated.showRecognitionConfidence
            prefs[Keys.SPEECH_PROFILE] = updated.speechQualityProfile.name
            prefs[Keys.WHISPER_REVIEW] = updated.whisperReviewEnabled
        }
    }
}
