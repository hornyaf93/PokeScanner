package com.yourname.pokescanner.data.repository

import com.yourname.pokescanner.data.remote.PokemonTcgApi
import com.yourname.pokescanner.data.remote.dto.PokemonTcgCardDto
import com.yourname.pokescanner.data.remote.dto.PokemonTcgPriceDto
import com.yourname.pokescanner.domain.model.CardIdentityQuery
import com.yourname.pokescanner.domain.model.CatalogCard
import com.yourname.pokescanner.domain.model.CatalogMatchEvidence
import com.yourname.pokescanner.domain.model.CatalogSet
import com.yourname.pokescanner.domain.model.MarketPrice
import com.yourname.pokescanner.domain.model.MarketPriceSource
import com.yourname.pokescanner.domain.model.MarketPrinting
import com.yourname.pokescanner.domain.repository.CardRepository
import com.yourname.pokescanner.domain.repository.CardRepositoryException
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Pokémon TCG API implementation with strict local identity validation. */
class DefaultCardRepository(
    private val api: PokemonTcgApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CardRepository {

    override suspend fun searchByIdentity(query: CardIdentityQuery): List<CatalogCard> =
        withContext(ioDispatcher) {
            require(query.name.isNotBlank()) { "Card name must not be blank" }
            require(query.cardNumber.isNotBlank()) { "Collector number must not be blank" }

            try {
                val apiCardsById = linkedMapOf<String, PokemonTcgCardDto>()

                searchNumberVariants(query.cardNumber).forEach { numberVariant ->
                    val response = api.searchCards(
                        query = buildApiQuery(query.name, numberVariant),
                    )
                    response.data.forEach { card -> apiCardsById[card.id] = card }
                }

                val exactIdentityMatches = apiCardsById.values.filter { card ->
                    normaliseName(card.name) == normaliseName(query.name) &&
                        normaliseCardNumber(card.number) == normaliseCardNumber(query.cardNumber)
                }

                val numericSetTotal = query.totalSetCards?.toIntOrNull()
                val exactTotalMatches = if (numericSetTotal != null) {
                    exactIdentityMatches.filter { card ->
                        card.set.printedTotal == numericSetTotal || card.set.total == numericSetTotal
                    }
                } else {
                    emptyList()
                }

                // A printed denominator is strong set evidence. Use it to remove same-name/same-number
                // cards from other sets, but do not return zero results solely because OCR read it badly.
                val selectedCards = if (exactTotalMatches.isNotEmpty()) {
                    exactTotalMatches
                } else {
                    exactIdentityMatches
                }

                selectedCards
                    .map { card -> card.toDomain(numericSetTotal) }
                    .sortedWith(
                        compareByDescending<CatalogCard> { it.catalogMatchScore }
                            .thenBy { it.set.id }
                            .thenBy { it.id },
                    )
                    .also { matches ->
                        LOGGER.fine("Pokémon TCG API identity matches: ${matches.size}")
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpException) {
                LOGGER.log(Level.SEVERE, "Pokémon TCG API HTTP ${error.code()}", error)
                throw CardRepositoryException(
                    message = "Pokémon TCG API request failed with HTTP ${error.code()}",
                    cause = error,
                )
            } catch (error: IOException) {
                LOGGER.log(Level.SEVERE, "Pokémon TCG API network failure", error)
                throw CardRepositoryException(
                    message = "Unable to reach the Pokémon TCG API",
                    cause = error,
                )
            } catch (error: CardRepositoryException) {
                throw error
            } catch (error: Exception) {
                LOGGER.log(Level.SEVERE, "Unable to search the Pokémon TCG API", error)
                throw CardRepositoryException(
                    message = "Unable to identify the card from the API response",
                    cause = error,
                )
            }
        }

    private fun PokemonTcgCardDto.toDomain(ocrSetTotal: Int?): CatalogCard {
        val setTotalMatches = ocrSetTotal != null &&
            (set.printedTotal == ocrSetTotal || set.total == ocrSetTotal)
        val evidence = buildSet {
            add(CatalogMatchEvidence.EXACT_NAME)
            add(CatalogMatchEvidence.EXACT_COLLECTOR_NUMBER)
            if (setTotalMatches) add(CatalogMatchEvidence.EXACT_PRINTED_SET_TOTAL)
        }

        return CatalogCard(
            id = id,
            name = name,
            number = number,
            set = CatalogSet(
                id = set.id,
                name = set.name,
                series = set.series,
                printedTotal = set.printedTotal,
                total = set.total,
            ),
            rarity = rarity,
            smallImageUrl = images?.small,
            largeImageUrl = images?.large,
            prices = buildMap {
                tcgplayer?.prices?.normal?.let {
                    put(MarketPrinting.NORMAL, it.toDomain())
                }
                tcgplayer?.prices?.holofoil?.let {
                    put(MarketPrinting.HOLOFOIL, it.toDomain())
                }
                tcgplayer?.prices?.reverseHolofoil?.let {
                    put(MarketPrinting.REVERSE_HOLOFOIL, it.toDomain())
                }
                tcgplayer?.prices?.firstEditionNormal?.let {
                    put(MarketPrinting.FIRST_EDITION_NORMAL, it.toDomain())
                }
                tcgplayer?.prices?.firstEditionHolofoil?.let {
                    put(MarketPrinting.FIRST_EDITION_HOLOFOIL, it.toDomain())
                }
            },
            catalogMatchScore = if (setTotalMatches) 0.99f else 0.92f,
            matchEvidence = evidence,
        )
    }

    private fun PokemonTcgPriceDto.toDomain() = MarketPrice(
        currency = "USD",
        low = low?.let(java.math.BigDecimal::valueOf),
        mid = mid?.let(java.math.BigDecimal::valueOf),
        high = high?.let(java.math.BigDecimal::valueOf),
        market = market?.let(java.math.BigDecimal::valueOf),
        directLow = directLow?.let(java.math.BigDecimal::valueOf),
        source = MarketPriceSource.TCGPLAYER,
    )

    private fun buildApiQuery(name: String, number: String): String =
        "name:${quoteForLucene(name)} number:${quoteForLucene(number)}"

    private fun quoteForLucene(value: String): String = buildString {
        append('"')
        value.trim().forEach { character ->
            if (character == '\\' || character == '"') append('\\')
            append(character)
        }
        append('"')
    }

    private fun searchNumberVariants(cardNumber: String): Set<String> = buildSet {
        val trimmed = cardNumber.trim()
        add(trimmed)
        add(normaliseCardNumber(trimmed))
    }

    private fun normaliseName(value: String): String {
        val genderExpanded = value
            .replace("♀", " female ")
            .replace("♂", " male ")
        return Normalizer.normalize(genderExpanded, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }

    /** Makes `023` equal `23` and `TG01` equal `TG1` without losing letter prefixes/suffixes. */
    private fun normaliseCardNumber(value: String): String {
        val compact = value.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        val match = CARD_NUMBER_PARTS_REGEX.matchEntire(compact) ?: return compact
        val digits = match.groupValues[2].trimStart('0').ifEmpty { "0" }
        return match.groupValues[1] + digits + match.groupValues[3]
    }

    private companion object {
        val LOGGER: Logger = Logger.getLogger(DefaultCardRepository::class.java.name)
        val CARD_NUMBER_PARTS_REGEX = Regex("""^([A-Z]*)(\d+)([A-Z]*)$""")
    }
}
