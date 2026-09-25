package com.yourname.pokescanner.core.recognition

/** Structured OCR evidence used to search the card catalogue. */
data class CardRecognitionResult(
    val cardName: String?,
    val collectorNumber: CollectorNumber?,
    /** Printed set/language code such as `SV1EN`, kept separate from the collector number. */
    val setCode: String?,
    val modifiers: CardTextModifiers,
    val evidenceScore: Float,
    val rawText: String,
    val recognisedLines: List<RecognisedTextLine>,
) {
    val hasSearchableIdentity: Boolean
        get() = cardName != null && collectorNumber != null
}

data class CollectorNumber(
    /** Exact text detected by OCR, useful for review and diagnostics. */
    val raw: String,
    /** Normalised card number, for example `023`, `TG01`, or `SWSH001`. */
    val cardNumber: String,
    /** Normalised set total, or null for a standalone promo code. */
    val totalSetCards: String?,
    val kind: CollectorNumberKind,
)

enum class CollectorNumberKind {
    SET_FRACTION,
    PROMO_CODE,
}

data class CardTextModifiers(
    val isPromo: Boolean = false,
    val hasLevelMarker: Boolean = false,
    val hasFirstEditionText: Boolean = false,
    val yearStamps: Set<Int> = emptySet(),
)

/**
 * OCR line plus its position in the upright input image.
 * Coordinates are -1 when ML Kit could not supply a bounding rectangle.
 */
data class RecognisedTextLine(
    val text: String,
    val left: Int = -1,
    val top: Int = -1,
    val right: Int = -1,
    val bottom: Int = -1,
) {
    val hasBounds: Boolean
        get() = left >= 0 && top >= 0 && right >= left && bottom >= top

    val height: Int
        get() = if (hasBounds) bottom - top else 0

    val centreY: Float
        get() = if (hasBounds) (top + bottom) / 2f else -1f
}

class CardRecognitionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
