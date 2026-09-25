package com.yourname.pokescanner.core.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.yourname.pokescanner.core.recognition.CardRecognitionInput
import com.yourname.pokescanner.core.recognition.CardRecognitionResult
import com.yourname.pokescanner.core.recognition.CardRecognizer
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * CameraX analyser that admits at most one frame per [frameIntervalMillis] and never runs more than
 * one OCR request concurrently.
 *
 * Call [close] when unbinding the CameraX use case. This class does not own [recognizer], so the
 * component that created the recognizer must close it separately after camera analysis has stopped.
 */
class CardFrameAnalyser(
    private val recognizer: CardRecognizer,
    private val onRecognition: (CardRecognitionResult) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val frameIntervalMillis: Long = DEFAULT_FRAME_INTERVAL_MILLIS,
    processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer, Closeable {

    private val analyserJob = SupervisorJob()
    private val analyserScope = CoroutineScope(analyserJob + processingDispatcher)
    private val processingFrame = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lastAcceptedFrameAt = AtomicLong(NO_FRAME_ACCEPTED)

    init {
        require(frameIntervalMillis >= 0L) {
            "frameIntervalMillis must be zero or greater"
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        var frameGateAcquired = false
        var ownershipTransferredToOcr = false

        try {
            if (closed.get()) return

            val now = elapsedRealtimeMillis()
            if (!isThrottleWindowOpen(now)) return
            if (!processingFrame.compareAndSet(false, true)) return
            frameGateAcquired = true

            // Re-check after acquiring the single-frame gate. This keeps throttling correct even if
            // an unusual camera implementation invokes analyze concurrently.
            if (!claimThrottleWindow(now)) return

            val mediaImage = imageProxy.image
                ?: throw IllegalStateException("CameraX supplied an ImageProxy with no image")
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            val uprightWidth = if (rotationDegrees % HALF_TURN_DEGREES == 0) {
                imageProxy.width
            } else {
                imageProxy.height
            }
            val uprightHeight = if (rotationDegrees % HALF_TURN_DEGREES == 0) {
                imageProxy.height
            } else {
                imageProxy.width
            }

            // UNDISTPATCHED executes the first statement immediately, making the ownership transfer
            // explicit before analyze() can enter its outer finally block. The inner finally owns
            // ImageProxy closure once ML Kit has released the Media.Image-backed InputImage.
            analyserScope.launch(start = CoroutineStart.UNDISPATCHED) {
                ownershipTransferredToOcr = true
                try {
                    val result = recognizer.recognize(
                        CardRecognitionInput(
                            image = inputImage,
                            uprightWidth = uprightWidth,
                            uprightHeight = uprightHeight,
                        ),
                    )
                    if (!closed.get()) {
                        runCatching { onRecognition(result) }
                            .onFailure { callbackError ->
                                Log.e(TAG, "Recognition callback failed", callbackError)
                            }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!closed.get()) reportError(error)
                } finally {
                    imageProxy.closeSafely()
                    processingFrame.set(false)
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) reportError(error)
        } finally {
            // Skipped, malformed and launch-failed frames all terminate here. Accepted OCR frames
            // are closed by the coroutine's finally block after ML Kit finishes with the buffer.
            if (!ownershipTransferredToOcr) {
                imageProxy.closeSafely()
                if (frameGateAcquired) processingFrame.set(false)
            }
        }
    }

    private fun isThrottleWindowOpen(now: Long): Boolean {
        val previous = lastAcceptedFrameAt.get()
        return previous == NO_FRAME_ACCEPTED || now - previous >= frameIntervalMillis
    }

    private fun claimThrottleWindow(now: Long): Boolean {
        while (true) {
            val previous = lastAcceptedFrameAt.get()
            if (previous != NO_FRAME_ACCEPTED && now - previous < frameIntervalMillis) {
                return false
            }
            if (lastAcceptedFrameAt.compareAndSet(previous, now)) return true
        }
    }

    private fun reportError(error: Throwable) {
        Log.e(TAG, "Unable to analyse camera frame", error)
        runCatching { onError(error) }
            .onFailure { callbackError ->
                Log.e(TAG, "Error callback failed", callbackError)
            }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            analyserScope.cancel("CardFrameAnalyser was closed")
        }
    }

    private fun ImageProxy.closeSafely() {
        runCatching { close() }
            .onFailure { error -> Log.e(TAG, "Unable to close camera frame", error) }
    }

    private companion object {
        const val TAG = "CardFrameAnalyser"
        const val DEFAULT_FRAME_INTERVAL_MILLIS = 500L
        const val NO_FRAME_ACCEPTED = -1L
        const val HALF_TURN_DEGREES = 180
    }
}
