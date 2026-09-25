package com.yourname.pokescanner.domain.usecase

import com.yourname.pokescanner.data.local.entity.PriceSnapshotEntity
import com.yourname.pokescanner.data.remote.AudExchangeRateProvider
import com.yourname.pokescanner.data.remote.EbaySoldListingDto
import com.yourname.pokescanner.data.remote.EbaySoldListingsHtmlParser
import com.yourname.pokescanner.data.remote.PriceApi
import com.yourname.pokescanner.domain.model.MarketPrice
import com.yourname.pokescanner.domain.model.MarketPriceSource
import com.yourname.pokescanner.domain.model.MarketSale
import com.yourname.pokescanner.domain.model.PrintingVariant
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CardValueRequest(
    val candidateId: String,
    val apiCardId: String,
    val cardName: String,
    /** Prefer the complete printed number, for example `4/102` or `SWSH001`. */
    val collectorNumber: String,
    val setName: String,
    val printingVariant: PrintingVariant,
)

/**
 * Produces an AUD [MarketPrice] from the three newest trustworthy eBay Australia sold listings.
 * HTML parsing is isolated in [EbaySoldListingsHtmlParser] so markup changes can be repaired without
 * touching valuation, currency or card-identity rules.
 */
class RefreshCardValueUseCase(
    private val priceApi: PriceApi,
    private val exchangeRates: AudExchangeRateProvider,
    private val htmlParser: EbaySoldListingsHtmlParser = EbaySoldListingsHtmlParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) {

    suspend operator fun invoke(request: CardValueRequest): MarketPrice =
        refresh(request).marketPrice

    /** Optional Room bridge retained for collection-history persistence. */
    suspend fun refreshSnapshot(request: CardValueRequest): PriceSnapshotEntity =
        refresh(request).toEntity(request)

    private suspend fun refresh(request: CardValueRequest): RefreshedValue =
        withContext(ioDispatcher) {
            request.validate()
            val query = EbayCardPriceQueryBuilder.build(request)

            val listings = try {
                priceApi.searchSoldListings(query = query).use { body ->
                    htmlParser.parse(body.string())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOGGER.log(Level.WARNING, "eBay AU sold search failed for ${request.apiCardId}", error)
                throw CardValueRefreshException(
                    message = "Could not retrieve eBay Australia sold listings",
                    cause = error,
                )
            }

            val comparableSales = listings
                .asSequence()
                .filter { listing -> SoldListingRules.isComparable(listing, request) }
                .mapNotNull(::parseListing)
                .distinctBy { listing ->
                    listing.listingId
                        ?: "${listing.title}|${listing.originalAmount}|${listing.soldAtEpochMillis}"
                }
                .sortedWith(
                    compareByDescending<ParsedSale>(ParsedSale::soldAtEpochMillis)
                        .thenBy(ParsedSale::resultIndex),
                )
                .take(REQUIRED_COMPARABLES)
                .toList()

            if (comparableSales.size < REQUIRED_COMPARABLES) {
                throw InsufficientComparableSalesException(
                    required = REQUIRED_COMPARABLES,
                    found = comparableSales.size,
                    query = query,
                )
            }

            val audSales = comparableSales.map { sale -> sale.convertToAud() }
            val sortedAmounts = audSales.map(MarketSale::amount).sorted()
            val median = sortedAmounts[REQUIRED_COMPARABLES / 2]
            val marketPrice = MarketPrice(
                currency = AUD,
                low = sortedAmounts.first(),
                mid = median,
                high = sortedAmounts.last(),
                market = median,
                source = MarketPriceSource.EBAY_AU_SOLD,
                recentSales = audSales,
            )

            RefreshedValue(
                query = query,
                capturedAtEpochMillis = clock.millis(),
                marketPrice = marketPrice,
            )
        }

    private fun parseListing(listing: EbaySoldListingDto): ParsedSale? {
        val money = MoneyTextParser.parse(listing.priceText) ?: return null
        val soldDateText = listing.soldDateText ?: return null
        val soldAt = parseSoldDate(soldDateText) ?: return null
        return ParsedSale(
            listingId = listing.listingId,
            title = listing.title,
            sourceUrl = listing.itemUrl,
            soldAtEpochMillis = soldAt,
            resultIndex = listing.resultIndex,
            originalAmount = money.amount,
            originalCurrency = money.currency,
        )
    }

    private suspend fun ParsedSale.convertToAud(): MarketSale {
        val rate = exchangeRates.audPerUnit(originalCurrency)
        require(rate > BigDecimal.ZERO) { "Exchange rate must be positive" }
        val audAmount = originalAmount
            .multiply(rate)
            .setScale(2, RoundingMode.HALF_UP)

        return MarketSale(
            listingId = listingId,
            title = title,
            soldAtEpochMillis = soldAtEpochMillis,
            amount = audAmount,
            currency = AUD,
            sourceUrl = sourceUrl,
            originalAmount = originalAmount,
            originalCurrency = originalCurrency,
            fxRateToAud = rate,
        )
    }

    private fun parseSoldDate(raw: String): Long? {
        val cleaned = raw
            .replace(SOLD_PREFIX, "")
            .replace(MULTI_SPACE, " ")
            .trim(' ', ',', ':', '-')
        if (cleaned.isBlank()) return null

        DATE_FORMATTERS.forEach { formatter ->
            try {
                return LocalDate.parse(cleaned, formatter)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next known eBay date shape.
            }
        }

        MONTH_DAY_FORMATTERS.forEach { formatter ->
            try {
                val monthDay = MonthDay.parse(cleaned, formatter)
                val now = LocalDate.now(clock)
                var date = monthDay.atYear(now.year)
                if (date.isAfter(now.plusDays(1))) date = date.minusYears(1)
                return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next known eBay date shape.
            }
        }
        return null
    }

    private fun RefreshedValue.toEntity(request: CardValueRequest): PriceSnapshotEntity {
        val first = marketPrice.recentSales[0]
        val second = marketPrice.recentSales[1]
        val third = marketPrice.recentSales[2]
        return PriceSnapshotEntity(
            candidateId = request.candidateId,
            apiCardId = request.apiCardId,
            source = MarketPriceSource.EBAY_AU_SOLD.name,
            searchQuery = query,
            condition = PRICE_CONDITION,
            currency = AUD,
            estimatedValueAudCents = requireNotNull(marketPrice.market).toAudCents(),
            sampleSize = marketPrice.recentSales.size,
            capturedAtEpochMillis = capturedAtEpochMillis,
            firstSaleItemId = first.listingId.orEmpty(),
            firstSaleTitle = first.title,
            firstSaleUrl = first.sourceUrl,
            firstSaleSoldAtEpochMillis = first.soldAtEpochMillis ?: 0L,
            firstSaleOriginalValue = first.originalAmount.stripTrailingZeros().toPlainString(),
            firstSaleOriginalCurrency = first.originalCurrency,
            firstSaleFxRateToAud = first.fxRateToAud.stripTrailingZeros().toPlainString(),
            firstSaleAudCents = first.amount.toAudCents(),
            secondSaleItemId = second.listingId.orEmpty(),
            secondSaleTitle = second.title,
            secondSaleUrl = second.sourceUrl,
            secondSaleSoldAtEpochMillis = second.soldAtEpochMillis ?: 0L,
            secondSaleOriginalValue = second.originalAmount.stripTrailingZeros().toPlainString(),
            secondSaleOriginalCurrency = second.originalCurrency,
            secondSaleFxRateToAud = second.fxRateToAud.stripTrailingZeros().toPlainString(),
            secondSaleAudCents = second.amount.toAudCents(),
            thirdSaleItemId = third.listingId.orEmpty(),
            thirdSaleTitle = third.title,
            thirdSaleUrl = third.sourceUrl,
            thirdSaleSoldAtEpochMillis = third.soldAtEpochMillis ?: 0L,
            thirdSaleOriginalValue = third.originalAmount.stripTrailingZeros().toPlainString(),
            thirdSaleOriginalCurrency = third.originalCurrency,
            thirdSaleFxRateToAud = third.fxRateToAud.stripTrailingZeros().toPlainString(),
            thirdSaleAudCents = third.amount.toAudCents(),
        )
    }

    private fun BigDecimal.toAudCents(): Long = movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()

    private fun CardValueRequest.validate() {
        require(candidateId.isNotBlank()) { "candidateId is required" }
        require(apiCardId.isNotBlank()) { "apiCardId is required" }
        require(cardName.isNotBlank()) { "cardName is required" }
        require(collectorNumber.isNotBlank()) { "collectorNumber is required" }
    }

    private data class ParsedSale(
        val listingId: String?,
        val title: String,
        val sourceUrl: String?,
        val soldAtEpochMillis: Long,
        val resultIndex: Int,
        val originalAmount: BigDecimal,
        val originalCurrency: String,
    )

    private data class RefreshedValue(
        val query: String,
        val capturedAtEpochMillis: Long,
        val marketPrice: MarketPrice,
    )

    private companion object {
        const val REQUIRED_COMPARABLES = 3
        const val AUD = "AUD"
        const val PRICE_CONDITION = "RAW_UNGRADED_NEAR_MINT"
        val LOGGER: Logger = Logger.getLogger(RefreshCardValueUseCase::class.java.name)
        val SOLD_PREFIX = Regex("""(?i)^.*?\b(?:sold|ended)\b\s*(?:on\s+)?""")
        val MULTI_SPACE = Regex("""\s+""")

        fun formatter(pattern: String): DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .parseDefaulting(ChronoField.ERA, 1)
            .toFormatter(Locale.ENGLISH)

        val DATE_FORMATTERS = listOf(
            formatter("d MMM uuuu"),
            formatter("d MMM, uuuu"),
            formatter("MMM d, uuuu"),
            formatter("MMM d uuuu"),
            formatter("d/M/uuuu"),
        )
        val MONTH_DAY_FORMATTERS = listOf(
            formatter("d MMM"),
            formatter("MMM d"),
        )
    }
}

object EbayCardPriceQueryBuilder {
    fun build(request: CardValueRequest): String = listOf(
        clean(request.cardName),
        clean(request.collectorNumber),
        "raw",
        "nm",
        "sold",
    ).filter(String::isNotBlank).joinToString(" ")

    private fun clean(value: String): String = value
        .replace(CONTROL_CHARACTERS, " ")
        .replace(WHITESPACE, " ")
        .trim()

    private val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")
    private val WHITESPACE = Regex("\\s+")
}

private object SoldListingRules {
    private val gradingService = Regex(
        """(?i)\b(?:PSA|BGS|BVG|CGC|SGC|HGA|GMA|KSA|PCA|BCCG|CSG|AGS|MGC|PCG|TGA|RCG|BECKETT)(?:\s*[-:]?\s*\d+(?:\.\d+)?)?\b""",
    )
    private val ambiguousGradingService = Regex(
        """(?i)\b(?:ACE|TAG)\s*[-:]?\s*\d+(?:\.\d+)?\b""",
    )
    private val gradedDescriptor = Regex(
        """(?i)\b(?:GRADE(?:D)?|GRADING|SLAB(?:BED)?|GEM[\s-]*MINT)\b""",
    )
    private val standaloneGradeTen = Regex("""(?i)(?<![A-Z0-9/])10(?![A-Z0-9/])""")
    private val excludedProducts = Regex(
        """(?i)\b(?:LOT|BUNDLE|PROXY|REPLICA|CUSTOM|ORICA|DIGITAL|METAL[\s-]*CARD)\b""",
    )
    private val nearMint = Regex(
        """(?i)(?:\bNEAR[\s-]*MINT\b|\bNM(?:\+|-)?(?=\s|$|[),/]))""",
    )
    private val rawCard = Regex("""(?i)\b(?:RAW|UNGRADED)\b""")
    private val firstEdition = Regex(
        """(?i)\b(?:1ST|FIRST)[\s-]*(?:EDITION\b|ED\b\.?)""",
    )
    private val shadowless = Regex("""(?i)\bSHADOWLESS\b""")
    private val undisclosedOffer = Regex("""(?i)\bBEST\s+OFFER\s+ACCEPTED\b""")

    fun isComparable(listing: EbaySoldListingDto, request: CardValueRequest): Boolean {
        val title = listing.title
        if (gradingService.containsMatchIn(title)) return false
        if (ambiguousGradingService.containsMatchIn(title)) return false
        if (gradedDescriptor.containsMatchIn(title)) return false
        if (standaloneGradeTen.containsMatchIn(title)) return false
        if (excludedProducts.containsMatchIn(title)) return false
        if (undisclosedOffer.containsMatchIn("$title ${listing.priceText}")) return false
        if (!nearMint.containsMatchIn(title)) return false
        if (!rawCard.containsMatchIn(title)) return false
        if (!containsCardName(title, request.cardName)) return false
        if (!containsCollectorNumber(title, request.collectorNumber)) return false

        return when (request.printingVariant) {
            PrintingVariant.FIRST_EDITION -> firstEdition.containsMatchIn(title)
            PrintingVariant.SHADOWLESS -> shadowless.containsMatchIn(title)
            PrintingVariant.UNLIMITED,
            PrintingVariant.STANDARD,
            -> !firstEdition.containsMatchIn(title) && !shadowless.containsMatchIn(title)
        }
    }

    private fun containsCardName(title: String, cardName: String): Boolean {
        val titleTokens = normaliseWords(title).toSet()
        val required = normaliseWords(cardName).filterNot { it in NAME_STOP_WORDS }
        return required.isNotEmpty() && required.all(titleTokens::contains)
    }

    private fun containsCollectorNumber(title: String, collectorNumber: String): Boolean {
        val cleaned = collectorNumber.trim().uppercase(Locale.ROOT)
        val parts = cleaned.split('/', limit = 2)
        val regex = if (parts.size == 2) {
            val number = collectorPartPattern(parts[0]) ?: return false
            val total = collectorPartPattern(parts[1]) ?: return false
            Regex(
                "(?i)(?<![A-Z0-9])$number\\s*/\\s*$total(?![A-Z0-9])",
            )
        } else {
            val number = collectorPartPattern(cleaned) ?: return false
            Regex("(?i)(?<![A-Z0-9])$number(?![A-Z0-9])")
        }
        return regex.containsMatchIn(title)
    }

    private fun collectorPartPattern(value: String): String? {
        val compact = value.filterNot { character -> character.isWhitespace() || character == '-' }
        val match = COLLECTOR_PART.matchEntire(compact) ?: return null
        val prefix = match.groupValues[1].asSequence()
            .joinToString(ALPHA_SEPARATOR) { character -> Regex.escape(character.toString()) }
        val digits = match.groupValues[2].trimStart('0').ifEmpty { "0" }
        val suffix = match.groupValues[3].asSequence()
            .joinToString(ALPHA_SEPARATOR) { character -> Regex.escape(character.toString()) }
        return buildString {
            if (prefix.isNotEmpty()) {
                append(prefix)
                append(ALPHA_SEPARATOR)
            }
            append("0*")
            append(Regex.escape(digits))
            if (suffix.isNotEmpty()) {
                append(ALPHA_SEPARATOR)
                append(suffix)
            }
        }
    }

    private fun normaliseWords(value: String): List<String> = Normalizer
        .normalize(
            value.replace("♀", " female ").replace("♂", " male "),
            Normalizer.Form.NFKD,
        )
        .replace(Regex("\\p{M}+"), "")
        .uppercase(Locale.ROOT)
        .split(Regex("[^A-Z0-9]+"))
        .filter(String::isNotBlank)

    private val NAME_STOP_WORDS = setOf("THE", "OF", "AND")
    private const val ALPHA_SEPARATOR = "[\\s-]*"
    private val COLLECTOR_PART = Regex("^([A-Z]*)(\\d+)([A-Z]*)$")
}

private object MoneyTextParser {
    private data class CurrencyPattern(val code: String, val regex: Regex)

    data class ParsedMoney(val amount: BigDecimal, val currency: String)

    fun parse(raw: String): ParsedMoney? {
        val text = raw.replace('\u00A0', ' ').trim()
        if (RANGE_PRICE.containsMatchIn(text)) return null

        CURRENCY_PATTERNS.forEach { currencyPattern ->
            val match = currencyPattern.regex.find(text) ?: return@forEach
            val amount = match.groupValues[1]
                .replace(",", "")
                .toBigDecimalOrNull()
                ?.takeIf { it > BigDecimal.ZERO }
                ?: return@forEach
            return ParsedMoney(amount, currencyPattern.code)
        }
        return null
    }

    private val RANGE_PRICE = Regex("""(?i)\bto\b""")
    private val CURRENCY_PATTERNS = listOf(
        CurrencyPattern("AUD", Regex("""(?i)(?:AU\s*\$|A\$|AUD\s*)\s*([\d,]+(?:\.\d{1,2})?)""")),
        CurrencyPattern("USD", Regex("""(?i)(?:US\s*\$|USD\s*)\s*([\d,]+(?:\.\d{1,2})?)""")),
        CurrencyPattern("NZD", Regex("""(?i)(?:NZ\s*\$|NZD\s*)\s*([\d,]+(?:\.\d{1,2})?)""")),
        CurrencyPattern("CAD", Regex("""(?i)(?:C\s*\$|CAD\s*)\s*([\d,]+(?:\.\d{1,2})?)""")),
        CurrencyPattern("GBP", Regex("""(?i)(?:£|GBP\s*)\s*([\d,]+(?:\.\d{1,2})?)""")),
        CurrencyPattern("EUR", Regex("""(?i)(?:€|EUR\s*)\s*([\d,]+(?:\.\d{1,2})?)""")),
        CurrencyPattern("JPY", Regex("""(?i)(?:¥|JPY\s*)\s*([\d,]+(?:\.\d{1,2})?)""")),
        // An unqualified dollar sign on ebay.com.au is Australian dollars.
        CurrencyPattern("AUD", Regex("""(?<![A-Z])\$\s*([\d,]+(?:\.\d{1,2})?)""")),
    )
}

class InsufficientComparableSalesException(
    val required: Int,
    val found: Int,
    val query: String,
) : IllegalStateException(
    "Only $found of $required valid raw near-mint sold listings were found for: $query",
)

class CardValueRefreshException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)
