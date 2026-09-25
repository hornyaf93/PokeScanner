package com.yourname.pokescanner.data.remote

import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * eBay Australia completed-listing HTML endpoint.
 *
 * Build this Retrofit service with `https://www.ebay.com.au/` as its base URL. The response is
 * intentionally parsed outside Retrofit because eBay serves HTML rather than a public sold-items
 * JSON API. Keep this adapter isolated: eBay may change its markup without notice.
 */
interface PriceApi {

    @GET("sch/i.html")
    suspend fun searchSoldListings(
        @Query("_nkw") query: String,
        @Query("LH_Sold") soldOnly: String = ENABLED,
        @Query("LH_Complete") completedOnly: String = ENABLED,
        @Query("_sop") sortOrder: Int = RECENTLY_ENDED_FIRST,
        @Query("_ipg") pageSize: Int = DEFAULT_PAGE_SIZE,
        @Header("User-Agent") userAgent: String = DEFAULT_USER_AGENT,
        @Header("Accept-Language") acceptLanguage: String = "en-AU,en;q=0.9",
    ): ResponseBody

    companion object {
        const val ENABLED = "1"
        const val RECENTLY_ENDED_FIRST = 13
        const val DEFAULT_PAGE_SIZE = 120
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}

data class EbaySoldListingDto(
    val listingId: String?,
    val title: String,
    val priceText: String,
    val soldDateText: String?,
    val itemUrl: String?,
    /** Original server-result position, used as a stable tie-breaker for sales on the same date. */
    val resultIndex: Int,
)

/** Selector-tolerant parser for both the legacy `s-item` and newer `s-card` result markup. */
class EbaySoldListingsHtmlParser {

    fun parse(html: String): List<EbaySoldListingDto> {
        if (html.isBlank()) return emptyList()

        val document = Jsoup.parse(html, EBAY_AU_BASE_URL)
        val resultElements = document.select(RESULT_CONTAINER_SELECTOR)

        return resultElements.mapIndexedNotNull { index, item ->
            val title = item.firstText(TITLE_SELECTOR)
                ?.replace(NEW_LISTING_PREFIX, "")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@mapIndexedNotNull null
            val price = item.firstText(PRICE_SELECTOR)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@mapIndexedNotNull null
            val link = item.selectFirst(LINK_SELECTOR)
                ?.attr("abs:href")
                ?.takeIf(String::isNotBlank)
            val soldText = item.select(SOLD_DATE_SELECTOR)
                .asSequence()
                .map(Element::text)
                .map(String::trim)
                .firstOrNull { text -> SOLD_MARKER.containsMatchIn(text) }

            EbaySoldListingDto(
                listingId = link?.let { url -> ITEM_ID_REGEX.find(url)?.groupValues?.get(1) },
                title = title,
                priceText = price,
                soldDateText = soldText,
                itemUrl = link,
                resultIndex = index,
            )
        }.distinctBy { item ->
            item.listingId ?: "${item.title}|${item.priceText}|${item.soldDateText}"
        }
    }

    private fun Element.firstText(selector: String): String? =
        selectFirst(selector)?.text()

    private companion object {
        const val EBAY_AU_BASE_URL = "https://www.ebay.com.au/"
        const val RESULT_CONTAINER_SELECTOR =
            "li.s-item, div.s-item, li.s-card, div.s-card"
        const val TITLE_SELECTOR =
            ".s-item__title, .s-card__title, [data-testid=item-title], h3"
        const val PRICE_SELECTOR =
            ".s-item__price, .s-card__price, [data-testid=item-price]"
        const val LINK_SELECTOR =
            "a.s-item__link, a.s-card__link, a[href*=/itm/]"
        const val SOLD_DATE_SELECTOR =
            ".s-item__title--tagblock, .s-item__ended-date, .s-item__caption--signal, " +
                ".s-card__caption, .SECONDARY_INFO, .s-item__subtitle"

        val NEW_LISTING_PREFIX = Regex("""^New Listing\s*""", RegexOption.IGNORE_CASE)
        val SOLD_MARKER = Regex("""\b(?:sold|ended)\b""", RegexOption.IGNORE_CASE)
        val ITEM_ID_REGEX = Regex("""/itm/(?:[^/?]+/)?(\d{9,15})(?:[/?]|$)""")
    }
}
