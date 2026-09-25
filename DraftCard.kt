package com.yourname.pokescanner.domain.model

/**
 * A scan awaiting user confirmation. The catalogue candidate and selected physical printing are
 * immutable; changing edition creates a new preview before this object enters the draft store.
 */
data class DraftCard(
    val draftId: String,
    val candidate: RecognitionCandidate,
    /** Complete printed identity, for example 4/102 or SWSH001. */
    val collectorNumber: String,
    val marketPrice: MarketPrice?,
    val detectedAtEpochMillis: Long,
)
