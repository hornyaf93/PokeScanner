package com.yourname.pokescanner.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "owned_cards",
    indices = [
        Index(value = ["apiCardId"]),
        Index(value = ["setId", "collectorNumber"]),
        Index(value = ["addedAtEpochMillis"]),
    ],
)
data class OwnedCardEntity(
    /** A scan UUID, allowing the collection to contain multiple copies of the same printing. */
    @PrimaryKey
    val ownedCardId: String,
    val apiCardId: String,
    val name: String,
    val collectorNumber: String,
    val setId: String,
    val setName: String,
    val setSeries: String,
    val rarity: String?,
    val printingVariant: String,
    val smallImageUrl: String?,
    val largeImageUrl: String?,
    val marketValueAudCents: Long?,
    val recentSaleOneAudCents: Long?,
    val recentSaleTwoAudCents: Long?,
    val recentSaleThreeAudCents: Long?,
    val detectedAtEpochMillis: Long,
    val addedAtEpochMillis: Long,
)
