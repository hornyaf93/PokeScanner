package com.yourname.pokescanner.domain.usecase

import com.yourname.pokescanner.domain.model.CandidateWarning
import com.yourname.pokescanner.domain.model.CardIdentityQuery
import com.yourname.pokescanner.domain.model.CatalogCard
import com.yourname.pokescanner.domain.model.EditionEvidence
import com.yourname.pokescanner.domain.model.MarketPrice
import com.yourname.pokescanner.domain.model.MarketPrinting
import com.yourname.pokescanner.domain.model.PrintingVariant
import com.yourname.pokescanner.domain.model.RecognitionCandidate
import com.yourname.pokescanner.domain.repository.CardRepository
import java.util.Locale

data class RecognisedCardIdentity(
    val name: String?,
    val cardNumber: String?,
    val totalSetCards: String?,
    val firstEditionStampDetected: Boolean,
    val setCode: String? = null,
)

/**
 * Turns OCR identity evidence into API-backed card candidates and explicit physical printings.
 * It never treats an API card ID as proof of edition because pokemontcg.io groups identical-artwork
 * 1st Edition and Unlimited printings under the same card identity.
 */
class IdentifyCardUseCase(
    private val cardRepository: CardRepository,
    private val printingVariantPolicy: PrintingVariantPolicy = KnownEnglishPrintingVariantPolicy,
) {

    suspend operator fun invoke(identity: RecognisedCardIdentity): List<RecognitionCandidate> {
        val name = identity.name?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
        val number = identity.cardNumber?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return emptyList()

        val cards = cardRepository.searchByIdentity(
            CardIdentityQuery(
                name = name,
                cardNumber = number,
                totalSetCards = identity.totalSetCards?.trim()?.takeIf(String::isNotEmpty),
                setCode = identity.setCode?.trim()?.takeIf(String::isNotEmpty),
            ),
        )

        return cards
            .flatMap { card -> card.toRecognitionCandidates(identity.firstEditionStampDetected) }
            .sortedWith(
                compareByDescending<RecognitionCandidate> { it.catalogMatchScore }
                    .thenByDescending { it.isSuggested }
                    .thenBy { it.setId }
                    .thenBy { variantOrder(it.printingVariant) },
            )
    }

    private fun CatalogCard.toRecognitionCandidates(
        firstEditionStampDetected: Boolean,
    ): List<RecognitionCandidate> {
        val variants = printingVariantPolicy.variantsFor(this)
        val editionIsAmbiguous = variants.size > 1
        val supportsFirstEdition = PrintingVariant.FIRST_EDITION in variants
        val apiHasFirstEditionPricing = prices.keys.any { printing ->
            printing == MarketPrinting.FIRST_EDITION_NORMAL ||
                printing == MarketPrinting.FIRST_EDITION_HOLOFOIL
        }

        return variants.map { variant ->
            val isFirstEdition = variant == PrintingVariant.FIRST_EDITION
            val editionEvidence = when {
                isFirstEdition && firstEditionStampDetected ->
                    EditionEvidence.OCR_FIRST_EDITION_STAMP

                isFirstEdition && apiHasFirstEditionPricing ->
                    EditionEvidence.API_FIRST_EDITION_PRICE_TYPE

                editionIsAmbiguous ->
                    EditionEvidence.KNOWN_SET_REQUIRES_VISUAL_CONFIRMATION

                else -> EditionEvidence.NOT_APPLICABLE
            }

            val warnings = buildSet {
                if (editionIsAmbiguous) add(CandidateWarning.EDITION_SELECTION_REQUIRED)
                if (variant == PrintingVariant.SHADOWLESS) {
                    add(CandidateWarning.SHADOWLESS_REQUIRES_VISUAL_CONFIRMATION)
                }
                if (firstEditionStampDetected && !supportsFirstEdition) {
                    add(CandidateWarning.OCR_FIRST_EDITION_CONFLICTS_WITH_SET)
                }
            }

            RecognitionCandidate(
                candidateId = "$id:${variant.name.lowercase(Locale.ROOT)}",
                variantGroupId = id,
                apiCardId = id,
                name = name,
                cardNumber = number,
                setId = set.id,
                setName = set.name,
                setSeries = set.series,
                rarity = rarity,
                smallImageUrl = smallImageUrl,
                largeImageUrl = largeImageUrl,
                printingVariant = variant,
                printingLabel = variant.displayName,
                catalogMatchScore = catalogMatchScore,
                matchEvidence = matchEvidence,
                editionEvidence = editionEvidence,
                isSuggested = when {
                    firstEditionStampDetected && supportsFirstEdition -> isFirstEdition
                    firstEditionStampDetected && !supportsFirstEdition -> false
                    variants.size == 1 -> true
                    else -> false
                },
                requiresUserConfirmation = editionIsAmbiguous ||
                    (firstEditionStampDetected && !supportsFirstEdition),
                warnings = warnings,
                marketPrice = priceFor(variant),
            )
        }
    }

    private fun CatalogCard.priceFor(variant: PrintingVariant): MarketPrice? = when (variant) {
        PrintingVariant.FIRST_EDITION ->
            prices[MarketPrinting.FIRST_EDITION_HOLOFOIL]
                ?: prices[MarketPrinting.FIRST_EDITION_NORMAL]

        PrintingVariant.UNLIMITED,
        PrintingVariant.STANDARD,
        -> prices[MarketPrinting.HOLOFOIL]
            ?: prices[MarketPrinting.NORMAL]
            ?: prices[MarketPrinting.REVERSE_HOLOFOIL]

        // pokemontcg.io does not expose a distinct Shadowless price type.
        PrintingVariant.SHADOWLESS -> null
    }

    private fun variantOrder(variant: PrintingVariant): Int = when (variant) {
        PrintingVariant.FIRST_EDITION -> 0
        PrintingVariant.SHADOWLESS -> 1
        PrintingVariant.UNLIMITED -> 2
        PrintingVariant.STANDARD -> 3
    }
}

fun interface PrintingVariantPolicy {
    fun variantsFor(card: CatalogCard): List<PrintingVariant>
}

/**
 * English Wizards-era printing policy. API price keys are used as direct supporting evidence;
 * known set IDs provide a fallback when historical TCGPlayer prices are absent.
 */
object KnownEnglishPrintingVariantPolicy : PrintingVariantPolicy {
    override fun variantsFor(card: CatalogCard): List<PrintingVariant> {
        if (card.set.id == BASE_SET_ID) {
            return listOf(
                PrintingVariant.FIRST_EDITION,
                PrintingVariant.SHADOWLESS,
                PrintingVariant.UNLIMITED,
            )
        }

        val hasFirstEditionPriceType = card.prices.keys.any { printing ->
            printing == MarketPrinting.FIRST_EDITION_NORMAL ||
                printing == MarketPrinting.FIRST_EDITION_HOLOFOIL
        }
        if (card.set.id in FIRST_EDITION_AND_UNLIMITED_SET_IDS || hasFirstEditionPriceType) {
            return listOf(PrintingVariant.FIRST_EDITION, PrintingVariant.UNLIMITED)
        }

        return listOf(PrintingVariant.STANDARD)
    }

    private const val BASE_SET_ID = "base1"

    private val FIRST_EDITION_AND_UNLIMITED_SET_IDS = setOf(
        "base2", // Jungle
        "base3", // Fossil
        "base5", // Team Rocket
        "gym1",  // Gym Heroes
        "gym2",  // Gym Challenge
        "neo1",  // Neo Genesis
        "neo2",  // Neo Discovery
        "neo3",  // Neo Revelation
        "neo4",  // Neo Destiny
    )
}
