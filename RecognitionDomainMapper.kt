package com.yourname.pokescanner.presentation.scanner

import com.yourname.pokescanner.core.recognition.CardRecognitionResult
import com.yourname.pokescanner.domain.usecase.RecognisedCardIdentity

/** The presentation-layer boundary between device OCR and provider-independent domain input. */
fun CardRecognitionResult.toRecognisedCardIdentity(): RecognisedCardIdentity =
    RecognisedCardIdentity(
        name = cardName,
        cardNumber = collectorNumber?.cardNumber,
        totalSetCards = collectorNumber?.totalSetCards,
        firstEditionStampDetected = modifiers.hasFirstEditionText,
        setCode = setCode,
    )

