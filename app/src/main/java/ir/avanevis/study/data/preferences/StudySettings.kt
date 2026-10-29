package ir.avanevis.study.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "study_settings")

enum class RecognitionEngineMode {
    // The app uses Android's installed recognition provider; it does not ship a separate ASR model.
    HYBRID_SMART,
    // Sent as EXTRA_PREFER_OFFLINE. This is only a provider hint, not a guarantee.
    OFFLINE_ONLY,
    // Kept for stored-setting compatibility; the app does not implement a separate cloud engine.
    ONLINE_HIGH_ACCURACY
}

enum class DigitDisplayMode {
    PERSIAN, // ۰۱۲۳۴۵۶۷۸۹
    LATIN    // 0123456789
}

data class StudySettings(
    val silenceThresholdMs: Long = 3500L,      // مدت سکوت برای تشخیص پایان جمله (میلی‌ثانیه)
    val vadSensitivityDb: Float = 3.5f,        // آستانه RMS تشخیص فعالیت صوتی
    val engineMode: RecognitionEngineMode = RecognitionEngineMode.HYBRID_SMART,
    val fontSizeSp: Int = 18,
    val autoNumbering: Boolean = true,
    val autoPunctuation: Boolean = true,
    val fixHalfSpace: Boolean = true,
    val digitMode: DigitDisplayMode = DigitDisplayMode.PERSIAN,
    val voiceCommandsEnabled: Boolean = true,
    val keepScreenOnDuringStudy: Boolean = false,
    val darkTheme: Boolean = false
)

class StudySettingsRepository(private val context: Context) {

    private object Keys {
        val SILENCE_MS = longPreferencesKey("silence_threshold_ms")
        val VAD_DB = floatPreferencesKey("vad_sensitivity_db")
        val ENGINE_MODE = stringPreferencesKey("engine_mode")
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val AUTO_NUMBERING = booleanPreferencesKey("auto_numbering")
        val AUTO_PUNCTUATION = booleanPreferencesKey("auto_punctuation")
        val FIX_HALF_SPACE = booleanPreferencesKey("fix_half_space")
        val DIGIT_MODE = stringPreferencesKey("digit_mode")
        val VOICE_COMMANDS = booleanPreferencesKey("voice_commands_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
    }

    val settingsFlow: Flow<StudySettings> = context.dataStore.data.map { prefs ->
        StudySettings(
            silenceThresholdMs = prefs[Keys.SILENCE_MS] ?: 3500L,
            vadSensitivityDb = prefs[Keys.VAD_DB] ?: 3.5f,
            engineMode = prefs[Keys.ENGINE_MODE]?.let {
                runCatching { RecognitionEngineMode.valueOf(it) }.getOrNull()
            } ?: RecognitionEngineMode.HYBRID_SMART,
            fontSizeSp = prefs[Keys.FONT_SIZE] ?: 18,
            autoNumbering = prefs[Keys.AUTO_NUMBERING] ?: true,
            autoPunctuation = prefs[Keys.AUTO_PUNCTUATION] ?: true,
            fixHalfSpace = prefs[Keys.FIX_HALF_SPACE] ?: true,
            digitMode = prefs[Keys.DIGIT_MODE]?.let {
                runCatching { DigitDisplayMode.valueOf(it) }.getOrNull()
            } ?: DigitDisplayMode.PERSIAN,
            voiceCommandsEnabled = prefs[Keys.VOICE_COMMANDS] ?: true,
            keepScreenOnDuringStudy = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            darkTheme = prefs[Keys.DARK_THEME] ?: false
        )
    }

    suspend fun updateSettings(transform: (StudySettings) -> StudySettings) {
        context.dataStore.edit { prefs ->
            val current = StudySettings(
                silenceThresholdMs = prefs[Keys.SILENCE_MS] ?: 3500L,
                vadSensitivityDb = prefs[Keys.VAD_DB] ?: 3.5f,
                engineMode = prefs[Keys.ENGINE_MODE]?.let {
                    runCatching { RecognitionEngineMode.valueOf(it) }.getOrNull()
                } ?: RecognitionEngineMode.HYBRID_SMART,
                fontSizeSp = prefs[Keys.FONT_SIZE] ?: 18,
                autoNumbering = prefs[Keys.AUTO_NUMBERING] ?: true,
                autoPunctuation = prefs[Keys.AUTO_PUNCTUATION] ?: true,
                fixHalfSpace = prefs[Keys.FIX_HALF_SPACE] ?: true,
                digitMode = prefs[Keys.DIGIT_MODE]?.let {
                    runCatching { DigitDisplayMode.valueOf(it) }.getOrNull()
                } ?: DigitDisplayMode.PERSIAN,
                voiceCommandsEnabled = prefs[Keys.VOICE_COMMANDS] ?: true,
                keepScreenOnDuringStudy = prefs[Keys.KEEP_SCREEN_ON] ?: false,
                darkTheme = prefs[Keys.DARK_THEME] ?: false
            )
            val updated = transform(current)
            prefs[Keys.SILENCE_MS] = updated.silenceThresholdMs
            prefs[Keys.VAD_DB] = updated.vadSensitivityDb
            prefs[Keys.ENGINE_MODE] = updated.engineMode.name
            prefs[Keys.FONT_SIZE] = updated.fontSizeSp
            prefs[Keys.AUTO_NUMBERING] = updated.autoNumbering
            prefs[Keys.AUTO_PUNCTUATION] = updated.autoPunctuation
            prefs[Keys.FIX_HALF_SPACE] = updated.fixHalfSpace
            prefs[Keys.DIGIT_MODE] = updated.digitMode.name
            prefs[Keys.VOICE_COMMANDS] = updated.voiceCommandsEnabled
            prefs[Keys.KEEP_SCREEN_ON] = updated.keepScreenOnDuringStudy
            prefs[Keys.DARK_THEME] = updated.darkTheme
        }
    }
}
