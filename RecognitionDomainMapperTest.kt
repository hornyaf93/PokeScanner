package com.yourname.pokescanner.presentation.scanner

import com.yourname.pokescanner.core.recognition.CardRecognitionResult
import com.yourname.pokescanner.core.recognition.CardTextModifiers
import com.yourname.pokescanner.core.recognition.CollectorNumber
import com.yourname.pokescanner.core.recognition.CollectorNumberKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecognitionDomainMapperTest {

    @Test
    fun `maps OCR identity evidence to the domain model without edition guessing`() {
        val result = CardRecognitionResult(
            cardName = "Miriam",
            collectorNumber = CollectorNumber(
                raw = "251/198",
                cardNumber = "251",
                totalSetCards = "198",
                kind = CollectorNumberKind.SET_FRACTION,
            ),
            setCode = "SV1EN",
            modifiers = CardTextModifiers(hasFirstEditionText = false),
            evidenceScore = 0.95f,
            rawText = "Miriam SV1EN 251/198",
            recognisedLines = emptyList(),
        )

        val identity = result.toRecognisedCardIdentity()

        assertEquals("Miriam", identity.name)
        assertEquals("251", identity.cardNumber)
        assertEquals("198", identity.totalSetCards)
        assertEquals("SV1EN", identity.setCode)
        assertFalse(identity.firstEditionStampDetected)
    }
}
