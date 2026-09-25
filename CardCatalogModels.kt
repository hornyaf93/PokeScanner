package com.yourname.pokescanner.domain.model

import java.math.BigDecimal

data class CardIdentityQuery(
    val name: String,
    val cardNumber: String,
    val totalSetCards: String?,
    /** Optional printed code such as SV1EN; retained as supporting identity evidence. */
    val setCode: String? = null,
)

data class CatalogCard(
    val id: String,
    val name: String,
    val number: String,
    val set: CatalogSet,
    val rarity: String?,
    val smallImageUrl: String?,
    val largeImageUrl: String?,
    val prices: Map<MarketPrinting, MarketPrice>,
    /** Confidence that the API card—not its physical printing—matches the OCR identity. */
    val catalogMatchScore: Float,
    val matchEvidence: Set<CatalogMatchEvidence>,
)

data class CatalogSet(
    val id: String,
    val name: String,
    val series: String,
    val printedTotal: Int,
    val total: Int,
)

enum class CatalogMatchEvidence {
    EXACT_NAME,
    EXACT_COLLECTOR_NUMBER,
    EXACT_PRINTED_SET_TOTAL,
}

enum class MarketPrinting {
    NORMAL,
    HOLOFOIL,
    REVERSE_HOLOFOIL,
    FIRST_EDITION_NORMAL,
    FIRST_EDITION_HOLOFOIL,
}

data class MarketPrice(
    val currency: String,
    val low: BigDecimal?,
    val mid: BigDecimal?,
    val high: BigDecimal?,
    val market: BigDecimal?,
    val directLow: BigDecimal? = null,
    val source: MarketPriceSource,
    /** Populated for comparable-sale valuations; empty for aggregate catalogue prices. */
    val recentSales: List<MarketSale> = emptyList(),
)

enum class MarketPriceSource {
    TCGPLAYER,
    EBAY_AU_SOLD,
}

data class MarketSale(
    val listingId: String?,
    val title: String,
    val soldAtEpochMillis: Long?,
    /** Converted amount in [currency], which is AUD for the eBay pricing engine. */
    val amount: BigDecimal,
    val currency: String,
    val sourceUrl: String?,
    val originalAmount: BigDecimal,
    val originalCurrency: String,
    /** Australian dollars per one unit of [originalCurrency]. */
    val fxRateToAud: BigDecimal,
)

enum class PrintingVariant(val displayName: String) {
    FIRST_EDITION("1st Edition"),
    SHADOWLESS("Shadowless"),
    UNLIMITED("Unlimited"),
    STANDARD("Standard printing"),
}

enum class EditionEvidence {
    NOT_APPLICABLE,
    OCR_FIRST_EDITION_STAMP,
    API_FIRST_EDITION_PRICE_TYPE,
    KNOWN_SET_REQUIRES_VISUAL_CONFIRMATION,
}

enum class CandidateWarning {
    EDITION_SELECTION_REQUIRED,
    OCR_FIRST_EDITION_CONFLICTS_WITH_SET,
    SHADOWLESS_REQUIRES_VISUAL_CONFIRMATION,
}

data class RecognitionCandidate(
    /** Unique row/toggle ID. The API card ID alone is not unique across physical printings. */
    val candidateId: String,
    /** Groups printing choices that share the same Pokémon TCG API card identity and artwork. */
    val variantGroupId: String,
    val apiCardId: String,
    val name: String,
    val cardNumber: String,
    val setId: String,
    val setName: String,
    val setSeries: String,
    val rarity: String?,
    val smallImageUrl: String?,
    val largeImageUrl: String?,
    val printingVariant: PrintingVariant,
    val printingLabel: String,
    val catalogMatchScore: Float,
    val matchEvidence: Set<CatalogMatchEvidence>,
    val editionEvidence: EditionEvidence,
    val isSuggested: Boolean,
    val requiresUserConfirmation: Boolean,
    val warnings: Set<CandidateWarning>,
    val marketPrice: MarketPrice?,
)
