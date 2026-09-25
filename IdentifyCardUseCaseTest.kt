package com.yourname.pokescanner.domain.usecase

import com.yourname.pokescanner.domain.model.CardIdentityQuery
import com.yourname.pokescanner.domain.model.CatalogCard
import com.yourname.pokescanner.domain.model.CatalogMatchEvidence
import com.yourname.pokescanner.domain.model.CatalogSet
import com.yourname.pokescanner.domain.model.MarketPrice
import com.yourname.pokescanner.domain.model.MarketPriceSource
import com.yourname.pokescanner.domain.model.MarketPrinting
import com.yourname.pokescanner.domain.model.PrintingVariant
import com.yourname.pokescanner.domain.model.RecognitionCandidate
import com.yourname.pokescanner.domain.repository.CardRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyCardUseCaseTest {

    @Test
    fun `base set returns first edition shadowless and unlimited`() = runBlocking {
        val useCase = IdentifyCardUseCase(FakeRepository(listOf(baseCard(setId = "base1"))))

        val candidates = useCase(identity(firstEditionStampDetected = false))

        assertEquals(
            listOf(
                PrintingVariant.FIRST_EDITION,
                PrintingVariant.SHADOWLESS,
                PrintingVariant.UNLIMITED,
            ),
            candidates.map { it.printingVariant },
        )
        assertTrue(candidates.all { it.requiresUserConfirmation })
        assertTrue(candidates.none { it.isSuggested })
    }

    @Test
    fun `explicit OCR stamp suggests first edition but preserves alternatives`() = runBlocking {
        val useCase = IdentifyCardUseCase(FakeRepository(listOf(baseCard(setId = "base1"))))

        val candidates = useCase(identity(firstEditionStampDetected = true))

        assertEquals(3, candidates.size)
        assertTrue(
            candidates.single { it.printingVariant == PrintingVariant.FIRST_EDITION }.isSuggested,
        )
        assertFalse(
            candidates.single { it.printingVariant == PrintingVariant.UNLIMITED }.isSuggested,
        )
    }

    @Test
    fun `known Wizards set returns first edition and unlimited without pricing`() = runBlocking {
        val useCase = IdentifyCardUseCase(
            FakeRepository(listOf(baseCard(setId = "neo1", prices = emptyMap()))),
        )

        val candidates = useCase(identity(firstEditionStampDetected = false))

        assertEquals(
            listOf(PrintingVariant.FIRST_EDITION, PrintingVariant.UNLIMITED),
            candidates.map { it.printingVariant },
        )
    }

    @Test
    fun `every known dual-print Wizards set returns both variants`() = runBlocking {
        val setIds = listOf(
            "base2",
            "base3",
            "base5",
            "gym1",
            "gym2",
            "neo1",
            "neo2",
            "neo3",
            "neo4",
        )

        setIds.forEach { setId ->
            val useCase = IdentifyCardUseCase(
                FakeRepository(listOf(baseCard(setId = setId, prices = emptyMap()))),
            )

            val variants = useCase(identity(firstEditionStampDetected = false))
                .map(RecognitionCandidate::printingVariant)

            assertEquals(
                "$setId must preserve both physical printings",
                listOf(PrintingVariant.FIRST_EDITION, PrintingVariant.UNLIMITED),
                variants,
            )
        }
    }

    @Test
    fun `modern set produces one standard printing`() = runBlocking {
        val useCase = IdentifyCardUseCase(FakeRepository(listOf(baseCard(setId = "sv1"))))

        val candidates = useCase(identity(firstEditionStampDetected = false))

        assertEquals(listOf(PrintingVariant.STANDARD), candidates.map { it.printingVariant })
        assertTrue(candidates.single().isSuggested)
        assertFalse(candidates.single().requiresUserConfirmation)
    }

    private fun identity(firstEditionStampDetected: Boolean) = RecognisedCardIdentity(
        name = "Charizard",
        cardNumber = "4",
        totalSetCards = "102",
        firstEditionStampDetected = firstEditionStampDetected,
    )

    private fun baseCard(
        setId: String,
        prices: Map<MarketPrinting, MarketPrice> = mapOf(
            MarketPrinting.FIRST_EDITION_HOLOFOIL to MarketPrice(
                currency = "USD",
                low = java.math.BigDecimal("1.0"),
                mid = java.math.BigDecimal("2.0"),
                high = java.math.BigDecimal("3.0"),
                market = java.math.BigDecimal("2.5"),
                source = MarketPriceSource.TCGPLAYER,
            ),
        ),
    ) = CatalogCard(
        id = "$setId-4",
        name = "Charizard",
        number = "4",
        set = CatalogSet(setId, "Set", "Series", 102, 102),
        rarity = "Rare Holo",
        smallImageUrl = null,
        largeImageUrl = null,
        prices = prices,
        catalogMatchScore = 0.99f,
        matchEvidence = setOf(
            CatalogMatchEvidence.EXACT_NAME,
            CatalogMatchEvidence.EXACT_COLLECTOR_NUMBER,
            CatalogMatchEvidence.EXACT_PRINTED_SET_TOTAL,
        ),
    )

    private class FakeRepository(
        private val cards: List<CatalogCard>,
    ) : CardRepository {
        override suspend fun searchByIdentity(query: CardIdentityQuery): List<CatalogCard> = cards
    }
}
