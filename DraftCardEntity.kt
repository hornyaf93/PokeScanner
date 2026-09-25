package com.yourname.pokescanner.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Flattened temporary scan; currency values are AUD cents to keep Room exact and converter-free. */
@Entity(
    tableName = "draft_cards",
    indices = [
        Index(value = ["candidateId"]),
        Index(value = ["detectedAtEpochMillis"]),
    ],
)
data class DraftCardEntity(
    @PrimaryKey
    val draftId: String,
    val candidateId: String,
    val variantGroupId: String,
    val apiCardId: String,
    val name: String,
    val cardNumber: String,
    val collectorNumber: String,
    val setId: String,
    val setName: String,
    val setSeries: String,
    val rarity: String?,
    val smallImageUrl: String?,
    val largeImageUrl: String?,
    val printingVariant: String,
    val printingLabel: String,
    val catalogMatchScore: Float,
    val requiresUserConfirmation: Boolean,
    val marketValueAudCents: Long?,
    val recentSaleOneAudCents: Long?,
    val recentSaleTwoAudCents: Long?,
    val recentSaleThreeAudCents: Long?,
    val detectedAtEpochMillis: Long,
)
