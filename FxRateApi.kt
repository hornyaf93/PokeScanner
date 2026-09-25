package com.yourname.pokescanner.data.remote

import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** Build this Retrofit service with https://api.frankfurter.dev/ as its base URL. */
interface FxRateApi {
    @GET("v1/latest")
    suspend fun latestRates(
        @Query("base") baseCurrency: String,
        @Query("symbols") symbols: String = AUD,
    ): FxRatesResponseDto

    companion object {
        const val AUD = "AUD"
    }
}

@Serializable
data class FxRatesResponseDto(
    val base: String,
    val date: String,
    val rates: Map<String, Double>,
)

fun interface AudExchangeRateProvider {
    /** Returns how many Australian dollars one unit of [sourceCurrency] buys. */
    suspend fun audPerUnit(sourceCurrency: String): BigDecimal
}

/**
 * Network-backed FX provider with a small in-memory cache. The conversion is only used for display
 * valuation; the original sale amount, currency and applied rate are also persisted for auditability.
 */
class FrankfurterAudExchangeRateProvider(
    private val api: FxRateApi,
    private val cacheTtlMillis: Long = 6L * 60L * 60L * 1_000L,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AudExchangeRateProvider {

    init {
        require(cacheTtlMillis > 0L) { "cacheTtlMillis must be positive" }
    }

    private data class CachedRate(val rate: BigDecimal, val fetchedAtEpochMillis: Long)

    private val cache = ConcurrentHashMap<String, CachedRate>()

    override suspend fun audPerUnit(sourceCurrency: String): BigDecimal {
        val currency = sourceCurrency.trim().uppercase(Locale.ROOT)
        require(ISO_CURRENCY_REGEX.matches(currency)) {
            "Invalid ISO 4217 currency code: $sourceCurrency"
        }
        if (currency == FxRateApi.AUD) return BigDecimal.ONE

        val now = nowEpochMillis()
        cache[currency]
            ?.takeIf { now - it.fetchedAtEpochMillis in 0 until cacheTtlMillis }
            ?.let { return it.rate }

        val response = try {
            api.latestRates(baseCurrency = currency)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw CurrencyConversionException(currency, error)
        }

        val numericRate = response.rates[FxRateApi.AUD]
            ?: throw CurrencyConversionException(currency, null)
        require(numericRate.isFinite() && numericRate > 0.0) {
            "Invalid $currency-to-AUD exchange rate: $numericRate"
        }

        val rate = BigDecimal.valueOf(numericRate)
        cache[currency] = CachedRate(rate, now)
        return rate
    }

    private companion object {
        val ISO_CURRENCY_REGEX = Regex("^[A-Z]{3}$")
    }
}

class CurrencyConversionException(
    sourceCurrency: String,
    cause: Throwable?,
) : IllegalStateException("No current $sourceCurrency-to-AUD exchange rate is available", cause)
