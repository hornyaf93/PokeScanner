package com.yourname.pokescanner.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.yourname.pokescanner.data.local.entity.DraftCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftCardDao {
    @Query("SELECT * FROM draft_cards ORDER BY detectedAtEpochMillis DESC")
    fun observeAll(): Flow<List<DraftCardEntity>>

    @Upsert
    suspend fun upsert(card: DraftCardEntity)

    @Query("DELETE FROM draft_cards WHERE draftId = :draftId")
    suspend fun deleteById(draftId: String)

    @Query("DELETE FROM draft_cards WHERE draftId IN (:draftIds)")
    suspend fun deleteByIds(draftIds: Set<String>)
}
