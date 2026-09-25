package com.yourname.pokescanner.data.repository

import com.yourname.pokescanner.data.local.dao.DraftCardDao
import com.yourname.pokescanner.data.local.entity.DraftCardEntity
import com.yourname.pokescanner.domain.model.CatalogMatchEvidence
import com.yourname.pokescanner.domain.model.DraftCard
import com.yourname.pokescanner.domain.model.EditionEvidence
import com.yourname.pokescanner.domain.model.MarketPrice
import com.yourname.pokescanner.domain.model.MarketPriceSource
import com.yourname.pokescanner.domain.model.MarketSale
import com.yourname.pokescanner.domain.model.PrintingVariant
import com.yourname.pokescanner.domain.model.RecognitionCandidate
import com.yourname.pokescanner.domain.repository.DraftCardStore
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Room-backed production draft queue. Supply an application-scoped [CoroutineScope]. */
class RoomDraftCardStore(
    private val dao: DraftCardDao,
    applicationScope: CoroutineScope,
) : DraftCardStore {

    override val cards: StateFlow<List<DraftCard>> = dao.observeAll()
        .map { entities -> entities.map(DraftCardEntity::toDomain) }
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    override suspend fun add(card: DraftCard) {
        dao.upsert(card.toEntity())
    }

    override suspend fun remove(draftId: String) {
        dao.deleteById(draftId)
    }

    override suspend fun removeAll(draftIds: Set<String>) {
        if (draftIds.isNotEmpty()) dao.deleteByIds(draftIds)
    }

    private fun DraftCard.toEntity(): DraftCardEntity {
        val sales = marketPrice?.recentSales.orEmpty()
        return DraftCardEntity(
            draftId = draftId,
            candidateId = candidate.candidateId,
            variantGroupId = candidate.variantGroupId,
            apiCardId = candidate.apiCardId,
            name = candidate.name,
            cardNumber = candidate.cardNumber,
            collectorNumber = collectorNumber,
            setId = candidate.setId,
            setName = candidate.setName,
            setSeries = candidate.setSeries,
            rarity = candidate.rarity,
            smallImageUrl = candidate.smallImageUrl,
            largeImageUrl = candidate.largeImageUrl,
            printingVariant = candidate.printingVariant.name,
            printingLabel = candidate.printingLabel,
            catalogMatchScore = candidate.catalogMatchScore,
            requiresUserConfirmation = candidate.requiresUserConfirmation,
            marketValueAudCents = marketPrice?.market?.toAudCents(),
            recentSaleOneAudCents = sales.getOrNull(0)?.amount?.toAudCents(),
            recentSaleTwoAudCents = sales.getOrNull(1)?.amount?.toAudCents(),
            recentSaleThreeAudCents = sales.getOrNull(2)?.amount?.toAudCents(),
            detectedAtEpochMillis = detectedAtEpochMillis,
        )
    }

    private fun DraftCardEntity.toDomain(): DraftCard {
        val variant = PrintingVariant.entries.firstOrNull { entry ->
            entry.name == printingVariant
        } ?: PrintingVariant.STANDARD
        val market = marketValueAudCents?.toAud()
        val sales = listOfNotNull(
            recentSaleOneAudCents?.toMarketSale(1),
            recentSaleTwoAudCents?.toMarketSale(2),
            recentSaleThreeAudCents?.toMarketSale(3),
        )
        val price = market?.let {
            val amounts = sales.map(MarketSale::amount).sorted()
            MarketPrice(
                currency = AUD,
                low = amounts.firstOrNull() ?: it,
                mid = it,
                high = amounts.lastOrNull() ?: it,
                market = it,
                source = MarketPriceSource.EBAY_AU_SOLD,
                recentSales = sales,
            )
        }

        return DraftCard(
            draftId = draftId,
            candidate = RecognitionCandidate(
                candidateId = candidateId,
                variantGroupId = variantGroupId,
                apiCardId = apiCardId,
                name = name,
                cardNumber = cardNumber,
                setId = setId,
                setName = setName,
                setSeries = setSeries,
                rarity = rarity,
                smallImageUrl = smallImageUrl,
                largeImageUrl = largeImageUrl,
                printingVariant = variant,
                printingLabel = printingLabel,
                catalogMatchScore = catalogMatchScore,
                matchEvidence = setOf(
                    CatalogMatchEvidence.EXACT_NAME,
                    CatalogMatchEvidence.EXACT_COLLECTOR_NUMBER,
                ),
                editionEvidence = if (requiresUserConfirmation) {
                    EditionEvidence.KNOWN_SET_REQUIRES_VISUAL_CONFIRMATION
                } else {
                    EditionEvidence.NOT_APPLICABLE
                },
                isSuggested = !requiresUserConfirmation,
                requiresUserConfirmation = requiresUserConfirmation,
                warnings = emptySet(),
                marketPrice = null,
            ),
            collectorNumber = collectorNumber,
            marketPrice = price,
            detectedAtEpochMillis = detectedAtEpochMillis,
        )
    }

    private fun Long.toMarketSale(position: Int): MarketSale {
        val amount = toAud()
        return MarketSale(
            listingId = null,
            title = "Recent eBay Australia sale $position",
            soldAtEpochMillis = null,
            amount = amount,
            currency = AUD,
            sourceUrl = null,
            originalAmount = amount,
            originalCurrency = AUD,
            fxRateToAud = BigDecimal.ONE,
        )
    }

    private fun BigDecimal.toAudCents(): Long = movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()

    private fun Long.toAud(): BigDecimal = BigDecimal.valueOf(this, 2)

    private companion object {
        const val AUD = "AUD"
    }
}
