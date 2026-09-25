package com.yourname.pokescanner.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.pokescanner.data.local.entity.PriceSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: PriceSnapshotEntity): Long

    @Query(
        """
        SELECT * FROM price_snapshots
        WHERE candidateId = :candidateId
        ORDER BY capturedAtEpochMillis DESC
        LIMIT 1
        """,
    )
    fun observeLatest(candidateId: String): Flow<PriceSnapshotEntity?>

    @Query(
        """
        DELETE FROM price_snapshots
        WHERE capturedAtEpochMillis < :cutoffEpochMillis
        """,
    )
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}

