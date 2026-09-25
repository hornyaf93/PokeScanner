package com.yourname.pokescanner.data.repository

import com.yourname.pokescanner.domain.model.CatalogMatchEvidence
import com.yourname.pokescanner.domain.model.DraftCard
import com.yourname.pokescanner.domain.model.EditionEvidence
import com.yourname.pokescanner.domain.model.PrintingVariant
import com.yourname.pokescanner.domain.model.RecognitionCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryDraftCardStoreTest {

    @Test
    fun `add is idempotent and removeAll preserves newer scans`() = runBlocking {
        val store = InMemoryDraftCardStore()
        val first = draft("first")
        val second = draft("second")
        val newer = draft("newer")

        store.add(first)
        store.add(first)
        store.add(second)
        store.add(newer)
        store.removeAll(setOf(first.draftId, second.draftId))

        assertEquals(listOf("newer"), store.cards.value.map { it.draftId })
    }

    private fun draft(id: String) = DraftCard(
        draftId = id,
        candidate = RecognitionCandidate(
            candidateId = "base1-4:unlimited",
            variantGroupId = "base1-4",
            apiCardId = "base1-4",
            name = "Charizard",
            cardNumber = "4",
            setId = "base1",
            setName = "Base Set",
            setSeries = "Base",
            rarity = "Rare Holo",
            smallImageUrl = null,
            largeImageUrl = null,
            printingVariant = PrintingVariant.UNLIMITED,
            printingLabel = "Unlimited",
            catalogMatchScore = 0.99f,
            matchEvidence = setOf(
                CatalogMatchEvidence.EXACT_NAME,
                CatalogMatchEvidence.EXACT_COLLECTOR_NUMBER,
            ),
            editionEvidence = EditionEvidence.KNOWN_SET_REQUIRES_VISUAL_CONFIRMATION,
            isSuggested = true,
            requiresUserConfirmation = true,
            warnings = emptySet(),
            marketPrice = null,
        ),
        collectorNumber = "4/102",
        marketPrice = null,
        detectedAtEpochMillis = 1L,
    )
}
