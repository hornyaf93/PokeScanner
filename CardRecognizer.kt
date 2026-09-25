package com.yourname.pokescanner.core.recognition

import com.google.mlkit.vision.common.InputImage
import java.io.Closeable

/**
 * Converts a camera image into structured card-identification evidence.
 *
 * Implementations must not return until they have finished reading [CardRecognitionInput.image].
 * This is important when the image is backed by a CameraX ImageProxy: the analyser keeps that proxy open until this
 * function completes.
 */
interface CardRecognizer : Closeable {
    suspend fun recognize(input: CardRecognitionInput): CardRecognitionResult
}

/**
 * [InputImage] intentionally exposes no public dimension getters, so the analyser supplies the
 * upright dimensions alongside it for geometry-aware parsing.
 */
data class CardRecognitionInput(
    val image: InputImage,
    val uprightWidth: Int,
    val uprightHeight: Int,
)
