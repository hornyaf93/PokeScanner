package com.yourname.pokescanner.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable valuation observation. Money is stored as AUD cents to avoid floating-point drift.
 * Original values and FX factors remain available for troubleshooting and later recalculation.
 */
@Entity(
    tableName = "price_snapshots",
    indices = [
        Index(value = ["candidateId", "capturedAtEpochMillis"]),
        Index(value = ["apiCardId"]),
    ],
)
data class PriceSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val candidateId: String,
    val apiCardId: String,
    val source: String,
    val searchQuery: String,
    val condition: String,
    val currency: String,
    val estimatedValueAudCents: Long,
    val sampleSize: Int,
    val capturedAtEpochMillis: Long,
    val firstSaleItemId: String,
    val firstSaleTitle: String,
    val firstSaleUrl: String?,
    val firstSaleSoldAtEpochMillis: Long,
    val firstSaleOriginalValue: String,
    val firstSaleOriginalCurrency: String,
    val firstSaleFxRateToAud: String,
    val firstSaleAudCents: Long,
    val secondSaleItemId: String,
    val secondSaleTitle: String,
    val secondSaleUrl: String?,
    val secondSaleSoldAtEpochMillis: Long,
    val secondSaleOriginalValue: String,
    val secondSaleOriginalCurrency: String,
    val secondSaleFxRateToAud: String,
    val secondSaleAudCents: Long,
    val thirdSaleItemId: String,
    val thirdSaleTitle: String,
    val thirdSaleUrl: String?,
    val thirdSaleSoldAtEpochMillis: Long,
    val thirdSaleOriginalValue: String,
    val thirdSaleOriginalCurrency: String,
    val thirdSaleFxRateToAud: String,
    val thirdSaleAudCents: Long,
)

