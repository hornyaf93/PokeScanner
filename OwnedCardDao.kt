package com.yourname.pokescanner.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yourname.pokescanner.data.local.entity.OwnedCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OwnedCardDao {
    /**
     * IGNORE makes a retry idempotent if Room committed successfully immediately before a process
     * interruption. The primary key remains the unique scan ID, not the catalogue card ID.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(cards: List<OwnedCardEntity>): List<Long>

    @Query("DELETE FROM draft_cards WHERE draftId IN (:draftIds)")
    abstract suspend fun deleteDraftRowsByIds(draftIds: Set<String>)

    /** Atomically moves one immutable Draft snapshot into the owned collection. */
    @Transaction
    open suspend fun commitDraftCards(
        cards: List<OwnedCardEntity>,
        draftIds: Set<String>,
    ) {
        require(cards.isNotEmpty()) { "At least one card is required" }
        require(cards.mapTo(mutableSetOf(), OwnedCardEntity::ownedCardId) == draftIds) {
            "Owned-card IDs must exactly match the Draft IDs being committed"
        }
        insertAll(cards)
        deleteDraftRowsByIds(draftIds)
    }

    @Query("SELECT * FROM owned_cards ORDER BY addedAtEpochMillis DESC")
    abstract fun observeAll(): Flow<List<OwnedCardEntity>>

    @Query("DELETE FROM owned_cards WHERE ownedCardId = :ownedCardId")
    abstract suspend fun deleteById(ownedCardId: String)
}
