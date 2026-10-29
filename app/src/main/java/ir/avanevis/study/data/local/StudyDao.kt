package ir.avanevis.study.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyDao {

    // --- مدیریت دفاتر مطالعه (Notebooks) ---
    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun observeNotebooks(): Flow<List<NotebookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotebook(notebook: NotebookEntity): Long

    @Update
    suspend fun updateNotebook(notebook: NotebookEntity)

    @Delete
    suspend fun deleteNotebook(notebook: NotebookEntity)

    // --- مدیریت جلسات مطالعه (Sessions) ---
    @Query("SELECT * FROM study_sessions ORDER BY startedAt DESC")
    fun observeAllSessions(): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_sessions WHERE notebookId = :notebookId ORDER BY startedAt DESC")
    fun observeSessionsByNotebook(notebookId: Long): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): StudySessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: StudySessionEntity): Long

    @Update
    suspend fun updateSession(session: StudySessionEntity)

    @Delete
    suspend fun deleteSession(session: StudySessionEntity)

    // --- مدیریت جملات یادداشت‌شده (Sentences) ---
    @Query("SELECT * FROM note_sentences WHERE sessionId = :sessionId ORDER BY orderIndex ASC, id ASC")
    fun observeSentencesForSession(sessionId: Long): Flow<List<NoteSentenceEntity>>

    @Query("SELECT * FROM note_sentences WHERE sessionId = :sessionId ORDER BY orderIndex ASC, id ASC")
    suspend fun getSentencesForSessionOnce(sessionId: Long): List<NoteSentenceEntity>

    @Query("SELECT COALESCE(MAX(orderIndex), 0) FROM note_sentences WHERE sessionId = :sessionId")
    suspend fun getMaxOrderIndex(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM note_sentences WHERE sessionId = :sessionId")
    suspend fun countSentencesInSession(sessionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSentence(sentence: NoteSentenceEntity): Long

    @Update
    suspend fun updateSentence(sentence: NoteSentenceEntity)

    @Delete
    suspend fun deleteSentence(sentence: NoteSentenceEntity)

    @Transaction
    suspend fun deleteSentenceAndRefreshCount(sentence: NoteSentenceEntity) {
        deleteSentence(sentence)
        val currentSession = getSessionById(sentence.sessionId) ?: return
        updateSession(currentSession.copy(sentenceCount = countSentencesInSession(sentence.sessionId)))
    }

    @Query("SELECT * FROM note_sentences WHERE sessionId = :sessionId ORDER BY orderIndex DESC, id DESC LIMIT 1")
    suspend fun getLastSentenceInSession(sessionId: Long): NoteSentenceEntity?

    @Query("SELECT * FROM note_sentences WHERE content LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchSentences(query: String): Flow<List<NoteSentenceEntity>>

    @Transaction
    suspend fun appendSentenceAutomatically(
        sessionId: Long,
        notebookId: Long,
        processedText: String,
        rawText: String,
        confidence: Float,
        isManual: Boolean = false
    ): NoteSentenceEntity {
        val nextIndex = getMaxOrderIndex(sessionId) + 1
        val entity = NoteSentenceEntity(
            sessionId = sessionId,
            notebookId = notebookId,
            orderIndex = nextIndex,
            content = processedText,
            rawTranscript = rawText,
            createdAt = System.currentTimeMillis(),
            confidence = confidence,
            isManuallyEdited = isManual
        )
        val newId = insertSentence(entity)
        val currentSession = getSessionById(sessionId)
        if (currentSession != null) {
            updateSession(
                // A note being added does not mean the study session has ended.
                // Count actual rows so deletes/reordering cannot leave a stale total.
                currentSession.copy(sentenceCount = countSentencesInSession(sessionId))
            )
        }
        return entity.copy(id = newId)
    }
}
