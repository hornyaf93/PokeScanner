package com.yourname.pokescanner.domain.usecase

import com.yourname.pokescanner.data.remote.AudExchangeRateProvider
import com.yourname.pokescanner.data.remote.PriceApi
import com.yourname.pokescanner.domain.model.MarketPriceSource
import com.yourname.pokescanner.domain.model.PrintingVariant
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RefreshCardValueUseCaseTest {

    @Test
    fun `uses exact query rejects graded results and values newest three in AUD`() = runBlocking {
        val api = FakePriceApi(
            html = resultsHtml(
                listing(
                    id = "999999999999",
                    title = "Charizard 4/102 Base Set PSA10 Grade NM",
                    price = "AU \$1,500.00",
                    soldDate = "Sold 5 Jun 2026",
                ),
                listing(
                    id = "888888888888",
                    title = "Charizard 4/102 Base Set ACE 10 Raw NM",
                    price = "AU \$900.00",
                    soldDate = "Sold 5 Jun 2026",
                ),
                listing(
                    id = "111111111111",
                    title = "Charizard 4/102 Base Set Unlimited Ungraded Near Mint",
                    price = "US \$100.00",
                    soldDate = "Sold 4 Jun 2026",
                ),
                listing(
                    id = "222222222222",
                    title = "Charizard 4/102 Base Set Unlimited Raw NM+",
                    price = "AU \$120.00",
                    soldDate = "Sold 3 Jun 2026",
                ),
                listing(
                    id = "333333333333",
                    title = "Charizard 004/0102 Base Set Unlimited Raw Near-Mint",
                    price = "\$80.00",
                    soldDate = "Sold 2 Jun 2026",
                ),
                listing(
                    id = "444444444444",
                    title = "Charizard 4/102 Base Set Unlimited Raw NM",
                    price = "AU \$50.00",
                    soldDate = "Sold 1 Jan 2026",
                ),
            ),
        )
        val useCase = useCase(api)

        val price = useCase(request())

        assertEquals("Charizard 4/102 raw nm sold", api.lastQuery)
        assertEquals("1", api.lastSoldOnly)
        assertEquals("1", api.lastCompletedOnly)
        assertEquals(PriceApi.RECENTLY_ENDED_FIRST, api.lastSortOrder)
        assertEquals("AUD", price.currency)
        assertEquals(MarketPriceSource.EBAY_AU_SOLD, price.source)
        assertEquals(BigDecimal("80.00"), price.low)
        assertEquals(BigDecimal("120.00"), price.mid)
        assertEquals(BigDecimal("150.00"), price.high)
        assertEquals(BigDecimal("120.00"), price.market)
        assertEquals(
            listOf("111111111111", "222222222222", "333333333333"),
            price.recentSales.map { it.listingId },
        )
        assertEquals(listOf("USD", "AUD", "AUD"), price.recentSales.map { it.originalCurrency })
    }

    @Test
    fun `first edition candidate rejects unlimited sale`() = runBlocking {
        val api = FakePriceApi(
            html = resultsHtml(
                listing(
                    "555555555555",
                    "Charizard 4/102 Base Set Unlimited Raw NM",
                    "AU \$1.00",
                    "Sold 4 Jun 2026",
                ),
                listing(
                    "111111111111",
                    "Charizard 4/102 Base Set 1st Ed. Raw NM",
                    "AU \$100.00",
                    "Sold 3 Jun 2026",
                ),
                listing(
                    "222222222222",
                    "Charizard 4/102 Base Set First Edition Ungraded Near Mint",
                    "AU \$110.00",
                    "Sold 2 Jun 2026",
                ),
                listing(
                    "333333333333",
                    "Charizard 4/102 Base Set 1st-Edition Raw NM",
                    "AU \$120.00",
                    "Sold 1 Jun 2026",
                ),
            ),
        )

        val price = useCase(api)(request(PrintingVariant.FIRST_EDITION))

        assertEquals(
            listOf("111111111111", "222222222222", "333333333333"),
            price.recentSales.map { it.listingId },
        )
    }

    @Test
    fun `snapshot bridge stores the same AUD valuation and source sales`() = runBlocking {
        val api = FakePriceApi(
            html = resultsHtml(
                listing("111111111111", "Charizard 4/102 Raw NM", "AU \$100", "Sold 3 Jun 2026"),
                listing("222222222222", "Charizard 4/102 Raw NM", "AU \$120", "Sold 2 Jun 2026"),
                listing("333333333333", "Charizard 4/102 Raw NM", "AU \$80", "Sold 1 Jun 2026"),
            ),
        )

        val snapshot = useCase(api).refreshSnapshot(request())

        assertEquals("EBAY_AU_SOLD", snapshot.source)
        assertEquals("Charizard 4/102 raw nm sold", snapshot.searchQuery)
        assertEquals(12_000L, snapshot.estimatedValueAudCents)
        assertEquals(3, snapshot.sampleSize)
        assertEquals(1_234L, snapshot.capturedAtEpochMillis)
        assertEquals("111111111111", snapshot.firstSaleItemId)
        assertEquals("222222222222", snapshot.secondSaleItemId)
        assertEquals("333333333333", snapshot.thirdSaleItemId)
    }

    @Test
    fun `refuses a valuation with fewer than three valid comparables`() = runBlocking {
        val api = FakePriceApi(
            html = resultsHtml(
                listing("111111111111", "Charizard 4/102 Raw NM", "AU \$100", "Sold 3 Jun 2026"),
                listing("222222222222", "Charizard 4/102 CGC Grade 9 NM", "AU \$500", "Sold 2 Jun 2026"),
                listing("333333333333", "Charizard 4/102 Raw NM Lot", "AU \$80", "Sold 1 Jun 2026"),
                listing("444444444444", "Charizard 4/102 TAG 10 Raw NM", "AU \$700", "Sold 31 May 2026"),
                listing("555555555555", "Charizard 4/102 Beckett Slabbed Raw NM", "AU \$650", "Sold 30 May 2026"),
                listing("666666666666", "Charizard 4/102 Raw NM", "AU \$90 Best Offer Accepted", "Sold 29 May 2026"),
            ),
        )

        val error = try {
            useCase(api)(request())
            fail("Expected InsufficientComparableSalesException")
            error("unreachable")
        } catch (expected: InsufficientComparableSalesException) {
            expected
        }

        assertEquals(1, error.found)
        assertEquals(3, error.required)
        assertEquals("Charizard 4/102 raw nm sold", error.query)
    }

    @Test
    fun `requires a parseable completed-sale date for every comparable`() = runBlocking {
        val api = FakePriceApi(
            html = resultsHtml(
                listing("111111111111", "Charizard 4/102 Raw NM", "AU \$100", "Sold 3 Jun 2026"),
                listing("222222222222", "Charizard 4/102 Raw NM", "AU \$120", "Sold 2 Jun 2026"),
                listing("333333333333", "Charizard 4/102 Raw NM", "AU \$80", null),
                listing("444444444444", "Charizard 4/102 Raw NM", "AU \$90", "Sold recently"),
            ),
        )

        val error = try {
            useCase(api)(request())
            fail("Expected InsufficientComparableSalesException")
            error("unreachable")
        } catch (expected: InsufficientComparableSalesException) {
            expected
        }

        assertEquals(2, error.found)
    }

    @Test
    fun `does not mistake collector number ten for a graded score`() = runBlocking {
        val api = FakePriceApi(
            html = resultsHtml(
                listing("111111111111", "Mew 10/102 Raw NM", "AU \$100", "Sold 3 Jun 2026"),
                listing("222222222222", "Mew 010/0102 Ungraded Near Mint", "AU \$120", "Sold 2 Jun 2026"),
                listing("333333333333", "Mew 10/102 Raw NM", "AU \$80", "Sold 1 Jun 2026"),
            ),
        )
        val request = request().copy(cardName = "Mew", collectorNumber = "10/102")

        val price = useCase(api)(request)

        assertEquals(3, price.recentSales.size)
    }

    @Test
    fun `matches spaced alphanumeric subset numbers without weakening exact identity`() = runBlocking {
        val api = FakePriceApi(
            html = resultsHtml(
                listing("111111111111", "Pikachu TG01 / TG30 Raw NM", "AU \$100", "Sold 3 Jun 2026"),
                listing("222222222222", "Pikachu TG1/TG030 Ungraded Near Mint", "AU \$120", "Sold 2 Jun 2026"),
                listing("333333333333", "Pikachu TG01/TG30 Raw NM", "AU \$80", "Sold 1 Jun 2026"),
            ),
        )
        val request = request().copy(cardName = "Pikachu", collectorNumber = "TG01/TG30")

        val price = useCase(api)(request)

        assertEquals(3, price.recentSales.size)
    }

    private fun useCase(api: PriceApi) = RefreshCardValueUseCase(
        priceApi = api,
        exchangeRates = AudExchangeRateProvider { currency ->
            if (currency == "USD") BigDecimal("1.50") else BigDecimal.ONE
        },
        clock = Clock.fixed(Instant.ofEpochMilli(1_234L), ZoneOffset.UTC),
    )

    private fun request(
        printingVariant: PrintingVariant = PrintingVariant.UNLIMITED,
    ) = CardValueRequest(
        candidateId = "base1-4:${printingVariant.name.lowercase()}",
        apiCardId = "base1-4",
        cardName = "Charizard",
        collectorNumber = "4/102",
        setName = "Base Set",
        printingVariant = printingVariant,
    )

    private fun resultsHtml(vararg listings: String): String =
        "<html><body><ul class=\"srp-results\">${listings.joinToString("")}</ul></body></html>"

    private fun listing(
        id: String,
        title: String,
        price: String,
        soldDate: String?,
    ): String = """
        <li class="s-item">
          <a class="s-item__link" href="https://www.ebay.com.au/itm/$id">
            <h3 class="s-item__title">$title</h3>
          </a>
          <span class="s-item__price">$price</span>
          ${soldDate?.let { "<span class=\"s-item__ended-date\">$it</span>" }.orEmpty()}
        </li>
    """.trimIndent()

    private class FakePriceApi(
        private val html: String,
    ) : PriceApi {
        var lastQuery: String? = null
        var lastSoldOnly: String? = null
        var lastCompletedOnly: String? = null
        var lastSortOrder: Int? = null

        override suspend fun searchSoldListings(
            query: String,
            soldOnly: String,
            completedOnly: String,
            sortOrder: Int,
            pageSize: Int,
            userAgent: String,
            acceptLanguage: String,
        ): ResponseBody {
            lastQuery = query
            lastSoldOnly = soldOnly
            lastCompletedOnly = completedOnly
            lastSortOrder = sortOrder
            return html.toResponseBody("text/html; charset=utf-8".toMediaType())
        }
    }
}
