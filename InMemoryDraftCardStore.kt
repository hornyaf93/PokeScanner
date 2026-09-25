package com.yourname.pokescanner.data.repository

import com.yourname.pokescanner.domain.model.DraftCard
import com.yourname.pokescanner.domain.repository.DraftCardStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe temporary draft store for tests and previews. Production should bind one
 * application-scoped [RoomDraftCardStore] so drafts survive process recreation.
 */
class InMemoryDraftCardStore : DraftCardStore {
    private val mutationMutex = Mutex()
    private val mutableCards = MutableStateFlow<List<DraftCard>>(emptyList())

    override val cards: StateFlow<List<DraftCard>> = mutableCards.asStateFlow()

    override suspend fun add(card: DraftCard) {
        mutationMutex.withLock {
            if (mutableCards.value.none { existing -> existing.draftId == card.draftId }) {
                mutableCards.value = mutableCards.value + card
            }
        }
    }

    override suspend fun remove(draftId: String) {
        mutationMutex.withLock {
            mutableCards.value = mutableCards.value.filterNot { card -> card.draftId == draftId }
        }
    }

    override suspend fun removeAll(draftIds: Set<String>) {
        if (draftIds.isEmpty()) return
        mutationMutex.withLock {
            mutableCards.value = mutableCards.value.filterNot { card -> card.draftId in draftIds }
        }
    }
}
