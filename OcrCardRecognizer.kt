package com.yourname.pokescanner.core.recognition

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.Year
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max

/** ML Kit-backed implementation of [CardRecognizer]. */
class OcrCardRecognizer(
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS,
    ),
) : CardRecognizer {

    private val closed = AtomicBoolean(false)
    private val parser = OcrTextParser()

    override suspend fun recognize(input: CardRecognitionInput): CardRecognitionResult {
        check(!closed.get()) { "OcrCardRecognizer has already been closed" }

        // Deliberately non-cancellable until ML Kit finishes. InputImage can directly reference the
        // CameraX media image, so releasing its ImageProxy during OCR would invalidate the buffer.
        return suspendCoroutine { continuation ->
            val task = try {
                textRecognizer.process(input.image)
            } catch (error: Exception) {
                Log.e(TAG, "ML Kit rejected the input image", error)
                continuation.resumeWithException(
                    CardRecognitionException("Unable to start card OCR", error),
                )
                return@suspendCoroutine
            }

            task.addOnCompleteListener(DIRECT_EXECUTOR) { completed ->
                completeRecognition(
                    task = completed,
                    imageWidth = input.uprightWidth,
                    imageHeight = input.uprightHeight,
                    onSuccess = continuation::resume,
                    onFailure = continuation::resumeWithException,
                )
            }
        }
    }

    private fun completeRecognition(
        task: Task<Text>,
        imageWidth: Int,
        imageHeight: Int,
        onSuccess: (CardRecognitionResult) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (!task.isSuccessful) {
            val cause = task.exception ?: IllegalStateException("ML Kit OCR task was cancelled")
            Log.e(TAG, "Card OCR failed", cause)
            onFailure(CardRecognitionException("Card OCR failed", cause))
            return
        }

        try {
            val text = requireNotNull(task.result) { "ML Kit returned no OCR result" }
            val lines = text.toRecognisedLines()
            val result = parser.parse(
                rawText = text.text,
                lines = lines,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )

            Log.d(
                TAG,
                "OCR complete: name=${result.cardName != null}, " +
                    "collectorNumber=${result.collectorNumber != null}, " +
                    "setCode=${result.setCode != null}, " +
                    "evidenceScore=${result.evidenceScore}",
            )
            onSuccess(result)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to parse ML Kit OCR output", error)
            onFailure(CardRecognitionException("Unable to parse card OCR output", error))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            textRecognizer.close()
        }
    }

    private fun Text.toRecognisedLines(): List<RecognisedTextLine> =
        textBlocks.flatMap { block ->
            block.lines.map { line ->
                val bounds = line.boundingBox ?: block.boundingBox
                RecognisedTextLine(
                    text = line.text.trim(),
                    left = bounds?.left ?: -1,
                    top = bounds?.top ?: -1,
                    right = bounds?.right ?: -1,
                    bottom = bounds?.bottom ?: -1,
                )
            }
        }.filter { it.text.isNotBlank() }

    private companion object {
        const val TAG = "OcrCardRecognizer"
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}

/**
 * Geometry-aware Pokémon card OCR parser. Kept separate from ML Kit so its rules can be unit tested
 * with ordinary strings.
 */
internal class OcrTextParser(
    private val maximumPlausibleYear: Int = Year.now().value + 1,
) {

    fun parse(
        rawText: String,
        lines: List<RecognisedTextLine>,
        imageWidth: Int,
        imageHeight: Int,
    ): CardRecognitionResult {
        val cleanRawText = rawText.trim()
        val effectiveHeight = max(
            imageHeight,
            lines.maxOfOrNull { if (it.hasBounds) it.bottom else 0 } ?: 0,
        ).coerceAtLeast(1)

        val collectorNumber = extractCollectorNumber(lines, cleanRawText, effectiveHeight)
        val setCode = extractSetCode(lines, cleanRawText, effectiveHeight)
        val cardName = extractCardName(lines, imageWidth, effectiveHeight)
        val yearStamps = YEAR_REGEX.findAll(cleanRawText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in MINIMUM_PLAUSIBLE_YEAR..maximumPlausibleYear }
            .toSortedSet()

        val modifiers = CardTextModifiers(
            isPromo = PROMO_MARKER_REGEX.containsMatchIn(cleanRawText) ||
                collectorNumber?.kind == CollectorNumberKind.PROMO_CODE,
            hasLevelMarker = LEVEL_MARKER_REGEX.containsMatchIn(cleanRawText),
            hasFirstEditionText = FIRST_EDITION_REGEX.containsMatchIn(cleanRawText),
            yearStamps = yearStamps,
        )

        return CardRecognitionResult(
            cardName = cardName,
            collectorNumber = collectorNumber,
            setCode = setCode,
            modifiers = modifiers,
            evidenceScore = evidenceScore(cardName, collectorNumber, setCode),
            rawText = cleanRawText,
            recognisedLines = lines,
        )
    }

    private fun extractCollectorNumber(
        lines: List<RecognisedTextLine>,
        rawText: String,
        imageHeight: Int,
    ): CollectorNumber? {
        val candidates = buildList {
            lines.forEach { line ->
                val verticalPosition = if (line.hasBounds) {
                    (line.centreY / imageHeight).coerceIn(0f, 1f)
                } else {
                    0f
                }

                SET_FRACTION_REGEX.findAll(line.text).forEach { match ->
                    add(
                        CollectorCandidate(
                            value = match.toSetFraction(),
                            score = 100f + verticalPosition * 25f,
                        ),
                    )
                }

                PROMO_CODE_REGEX.findAll(line.text).forEach { match ->
                    add(
                        CollectorCandidate(
                            value = match.toPromoCode(),
                            score = 80f + verticalPosition * 25f,
                        ),
                    )
                }
            }

            // OCR occasionally splits the number across adjacent elements. Searching the flattened
            // text is a safe fallback, but line-local matches win because they retain card geometry.
            SET_FRACTION_REGEX.find(rawText)?.let { match ->
                add(CollectorCandidate(match.toSetFraction(), score = 90f))
            }
            PROMO_CODE_REGEX.find(rawText)?.let { match ->
                add(CollectorCandidate(match.toPromoCode(), score = 70f))
            }
        }

        return candidates.maxByOrNull { it.score }?.value
    }

    private fun extractCardName(
        lines: List<RecognisedTextLine>,
        imageWidth: Int,
        imageHeight: Int,
    ): String? {
        val topRegionLimit = imageHeight * NAME_REGION_BOTTOM_RATIO
        val boundedCandidates = lines.mapNotNull { line ->
            if (!line.hasBounds || line.centreY > topRegionLimit) return@mapNotNull null

            val cleaned = cleanNameCandidate(line.text)
            if (!isPlausibleName(cleaned)) return@mapNotNull null

            val topness = 1f - (line.centreY / imageHeight).coerceIn(0f, 1f)
            val relativeHeight = (line.height.toFloat() / imageHeight).coerceIn(0f, 1f)
            val widthBonus = if (imageWidth > 0 && line.right > line.left) {
                ((line.right - line.left).toFloat() / imageWidth).coerceIn(0f, 1f)
            } else {
                0f
            }
            val usefulLengthBonus = if (cleaned.length in 3..28) 1f else 0f

            NameCandidate(
                value = cleaned,
                score = topness * 5f + relativeHeight * 12f + widthBonus + usefulLengthBonus,
            )
        }

        if (boundedCandidates.isNotEmpty()) {
            return boundedCandidates.maxBy { it.score }.value
        }

        // Some devices/ML Kit versions can return text without bounds. Preserve OCR order and use
        // only the first plausible short line instead of guessing from the entire rules text.
        return lines.asSequence()
            .filterNot { it.hasBounds }
            .map { cleanNameCandidate(it.text) }
            .firstOrNull(::isPlausibleName)
    }

    private fun extractSetCode(
        lines: List<RecognisedTextLine>,
        rawText: String,
        imageHeight: Int,
    ): String? {
        val positioned = lines.mapNotNull { line ->
            val match = SET_CODE_REGEX.find(line.text) ?: return@mapNotNull null
            val verticalPosition = if (line.hasBounds) {
                (line.centreY / imageHeight).coerceIn(0f, 1f)
            } else {
                0f
            }
            SetCodeCandidate(
                value = normaliseSetCode(match.groupValues[1]),
                score = 50f + verticalPosition * 20f,
            )
        }
        return positioned.maxByOrNull(SetCodeCandidate::score)?.value
            ?: SET_CODE_REGEX.find(rawText)?.groupValues?.get(1)?.let(::normaliseSetCode)
    }

    private fun cleanNameCandidate(value: String): String = value
        .replace(HEADER_PREFIX_REGEX, "")
        .replace(HP_SUFFIX_REGEX, "")
        .replace(LEVEL_SUFFIX_REGEX, "")
        .replace(MULTI_SPACE_REGEX, " ")
        .trim(' ', '-', '•', '|')

    private fun isPlausibleName(value: String): Boolean {
        if (value.length !in 2..40) return false
        if (value.uppercase(Locale.ROOT) in NAME_STOP_WORDS) return false
        if (SET_FRACTION_REGEX.containsMatchIn(value)) return false
        if (PROMO_CODE_REGEX.matches(value)) return false

        val letterCount = value.count(Char::isLetter)
        val digitCount = value.count(Char::isDigit)
        if (letterCount < 2) return false
        if (digitCount > letterCount) return false

        return value.count { character ->
            character.isLetterOrDigit() || character in NAME_PUNCTUATION
        }.toFloat() / value.length >= MINIMUM_NAME_CHARACTER_RATIO
    }

    private fun MatchResult.toSetFraction(): CollectorNumber = CollectorNumber(
        raw = value.trim(),
        cardNumber = normaliseNumberPart(groupValues[1]),
        totalSetCards = normaliseNumberPart(groupValues[2]),
        kind = CollectorNumberKind.SET_FRACTION,
    )

    private fun MatchResult.toPromoCode(): CollectorNumber = CollectorNumber(
        raw = value.trim(),
        cardNumber = normaliseNumberPart(groupValues[1]),
        totalSetCards = null,
        kind = CollectorNumberKind.PROMO_CODE,
    )

    private fun normaliseNumberPart(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace(WHITESPACE_OR_HYPHEN_REGEX, "")

    private fun normaliseSetCode(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace(WHITESPACE_OR_HYPHEN_REGEX, "")

    private fun evidenceScore(
        cardName: String?,
        collectorNumber: CollectorNumber?,
        setCode: String?,
    ): Float = when {
        cardName != null && collectorNumber != null -> 0.95f
        cardName != null && setCode != null -> 0.80f
        collectorNumber != null -> 0.72f
        cardName != null -> 0.52f
        else -> 0f
    }

    private data class CollectorCandidate(
        val value: CollectorNumber,
        val score: Float,
    )

    private data class NameCandidate(
        val value: String,
        val score: Float,
    )

    private data class SetCodeCandidate(
        val value: String,
        val score: Float,
    )

    private companion object {
        const val MINIMUM_PLAUSIBLE_YEAR = 1996
        const val NAME_REGION_BOTTOM_RATIO = 0.45f
        const val MINIMUM_NAME_CHARACTER_RATIO = 0.75f

        val NAME_PUNCTUATION = setOf(' ', '\'', '’', '-', '.', ':', '&', '♀', '♂')

        /**
         * Handles ordinary set numbering and common subsets such as TG01/TG30, GG01/GG70,
         * RC1/RC32, SV001/SV122 and 123a/202.
         */
        val SET_FRACTION_REGEX = Regex(
            pattern = """(?<![A-Z0-9])([A-Z]{0,4}\d{1,4}[A-Z]{0,2})\s*[/⁄]\s*([A-Z]{0,4}\d{1,4}[A-Z]{0,2})(?![A-Z0-9])""",
            option = RegexOption.IGNORE_CASE,
        )

        /** Standalone Black Star promo numbering, including `SWSH001` and `SVP EN 001`. */
        val PROMO_CODE_REGEX = Regex(
            pattern = """(?<![A-Z0-9])((?:SWSH|HGSS|SVP|MEP|SM|XY|BW|DP)\s*(?:EN\s*)?[- ]?\s*\d{1,4}[A-Z]?)(?![A-Z0-9])""",
            option = RegexOption.IGNORE_CASE,
        )

        /** Printed English set/language codes, for example `SV1EN` or `SV 1 EN`. */
        val SET_CODE_REGEX = Regex(
            pattern = """(?<![A-Z0-9])((?:SV|ME)\s*\d{1,2}[A-Z]?\s*EN)(?![A-Z0-9])""",
            option = RegexOption.IGNORE_CASE,
        )

        val PROMO_MARKER_REGEX = Regex(
            """\b(?:BLACK\s+STAR\s+)?PROMO\b""",
            RegexOption.IGNORE_CASE,
        )
        val LEVEL_MARKER_REGEX = Regex(
            """(?<![A-Z0-9])LV\s*\.?\s*(?:X|\d{1,3})?(?![A-Z0-9])""",
            RegexOption.IGNORE_CASE,
        )
        val FIRST_EDITION_REGEX = Regex(
            """(?:\b(?:1ST|FIRST)\s*(?:EDITION\b|ED\b\.?)|\bEDITION\s*1\b)""",
            RegexOption.IGNORE_CASE,
        )
        val YEAR_REGEX = Regex("""(?<!\d)(?:©\s*)?(19\d{2}|20\d{2})(?!\d)""")

        val HEADER_PREFIX_REGEX = Regex(
            """^(?:BASIC|STAGE\s*[12]|TRAINER|SUPPORTER|ITEM)\s*[:\-]?\s*""",
            RegexOption.IGNORE_CASE,
        )
        val HP_SUFFIX_REGEX = Regex(
            """\s+(?:HP\s*)?\d{2,3}\s*(?:HP)?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val LEVEL_SUFFIX_REGEX = Regex(
            """\s+LV\s*\.?\s*(?:X|\d{1,3})?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val MULTI_SPACE_REGEX = Regex("""\s{2,}""")
        val WHITESPACE_OR_HYPHEN_REGEX = Regex("""[\s-]+""")

        val NAME_STOP_WORDS = setOf(
            "ABILITY",
            "BASIC",
            "ENERGY",
            "EVOLVES FROM",
            "ILLUS.",
            "ITEM",
            "POKEMON",
            "POKÉMON",
            "POKÉMON POWER",
            "PROMO",
            "RESISTANCE",
            "RETREAT",
            "RULE",
            "STAGE 1",
            "STAGE 2",
            "SUPPORTER",
            "TRAINER",
            "WEAKNESS",
        )
    }
}
