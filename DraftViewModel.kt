package com.yourname.pokescanner.feature.draft

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.pokescanner.data.local.dao.OwnedCardDao
import com.yourname.pokescanner.data.local.entity.OwnedCardEntity
import com.yourname.pokescanner.domain.model.DraftCard
import com.yourname.pokescanner.domain.repository.DraftCardStore
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DraftUiState(
    val cards: List<DraftCard> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface DraftEvent {
    data class CollectionSaved(val count: Int) : DraftEvent
}

/** Coordinates swipe removal and atomically commits the visible Draft snapshot through Room. */
class DraftViewModel(
    private val draftStore: DraftCardStore,
    private val ownedCardDao: OwnedCardDao,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private data class SaveState(
        val isSaving: Boolean = false,
        val errorMessage: String? = null,
    )

    private val saveState = MutableStateFlow(SaveState())
    private val mutableEvents = MutableSharedFlow<DraftEvent>(extraBufferCapacity = 1)

    val events = mutableEvents.asSharedFlow()

    val uiState: StateFlow<DraftUiState> = combine(
        draftStore.cards,
        saveState,
    ) { cards, saving ->
        DraftUiState(
            cards = cards,
            isSaving = saving.isSaving,
            errorMessage = saving.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = DraftUiState(),
    )

    fun discard(draftId: String) {
        if (saveState.value.isSaving) return
        viewModelScope.launch { draftStore.remove(draftId) }
    }

    fun confirmAndAddToCollection() {
        if (saveState.value.isSaving) return
        val draftSnapshot = draftStore.cards.value
        if (draftSnapshot.isEmpty()) return

        saveState.value = SaveState(isSaving = true)
        viewModelScope.launch {
            try {
                val addedAt = clock.millis()
                val ownedCards = draftSnapshot.map { draft ->
                    draft.toOwnedCardEntity(addedAt)
                }
                val draftIds = draftSnapshot.mapTo(mutableSetOf(), DraftCard::draftId)
                ownedCardDao.commitDraftCards(
                    cards = ownedCards,
                    draftIds = draftIds,
                )
                saveState.value = SaveState()
                mutableEvents.emit(DraftEvent.CollectionSaved(draftSnapshot.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveState.value = SaveState(
                    errorMessage = "Could not save the draft. Nothing was removed—try again.",
                )
            }
        }
    }

    fun clearError() {
        saveState.update { state -> state.copy(errorMessage = null) }
    }

    private fun DraftCard.toOwnedCardEntity(addedAtEpochMillis: Long): OwnedCardEntity {
        val sales = marketPrice?.recentSales.orEmpty()
        return OwnedCardEntity(
            ownedCardId = draftId,
            apiCardId = candidate.apiCardId,
            name = candidate.name,
            collectorNumber = collectorNumber,
            setId = candidate.setId,
            setName = candidate.setName,
            setSeries = candidate.setSeries,
            rarity = candidate.rarity,
            printingVariant = candidate.printingVariant.name,
            smallImageUrl = candidate.smallImageUrl,
            largeImageUrl = candidate.largeImageUrl,
            marketValueAudCents = marketPrice?.market?.toAudCents(),
            recentSaleOneAudCents = sales.getOrNull(0)?.amount?.toAudCents(),
            recentSaleTwoAudCents = sales.getOrNull(1)?.amount?.toAudCents(),
            recentSaleThreeAudCents = sales.getOrNull(2)?.amount?.toAudCents(),
            detectedAtEpochMillis = detectedAtEpochMillis,
            addedAtEpochMillis = addedAtEpochMillis,
        )
    }

    private fun BigDecimal.toAudCents(): Long = movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}
