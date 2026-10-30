package ir.avanevis.study.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Immutable durable acknowledgment for one audio chunk. */
@Entity(tableName = "whisper_chunk_receipts")
data class WhisperChunkReceipt(
    @PrimaryKey val chunkId: String,
    val sessionId: Long,
    val recordedAt: Long = System.currentTimeMillis()
)
