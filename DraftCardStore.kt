package com.yourname.pokescanner.domain.repository

import com.yourname.pokescanner.domain.model.DraftCard
import kotlinx.coroutines.flow.StateFlow

/** Holding area for scans that have not yet been committed to the owned-card collection. */
interface DraftCardStore {
    val cards: StateFlow<List<DraftCard>>

    suspend fun add(card: DraftCard)

    suspend fun remove(draftId: String)

    suspend fun removeAll(draftIds: Set<String>)
}
