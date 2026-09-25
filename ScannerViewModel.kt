package com.yourname.pokescanner.feature.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.pokescanner.core.recognition.CardRecognitionResult
import com.yourname.pokescanner.domain.model.DraftCard
import com.yourname.pokescanner.domain.model.MarketPrice
import com.yourname.pokescanner.domain.model.PrintingVariant
import com.yourname.pokescanner.domain.model.RecognitionCandidate
import com.yourname.pokescanner.domain.repository.DraftCardStore
import com.yourname.pokescanner.domain.usecase.CardValueRequest
import com.yourname.pokescanner.domain.usecase.IdentifyCardUseCase
import com.yourname.pokescanner.domain.usecase.RecognisedCardIdentity
import com.yourname.pokescanner.domain.usecase.RefreshCardValueUseCase
import com.yourname.pokescanner.presentation.scanner.toRecognisedCardIdentity
import java.time.Clock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScannerStage {
    SCANNING,
    IDENTIFYING,
    PRICING,
    READY,
    ERROR,
}

data class ScannerUiState(
    val stage: ScannerStage = ScannerStage.SCANNING,
    val cameraPermissionGranted: Boolean = false,
    val candidates: List<RecognitionCandidate> = emptyList(),
    val selectedCandidateId: String? = null,
    val detectedCollectorNumber: String? = null,
    val marketPrice: MarketPrice? = null,
    val isDraftSheetVisible: Boolean = false,
    val isAddingToDraft: Boolean = false,
    val draftCount: Int = 0,
    val message: String? = null,
) {
    val selectedCandidate: RecognitionCandidate?
        get() = candidates.firstOrNull { candidate -> candidate.candidateId == selectedCandidateId }

    val canAddToDraft: Boolean
        get() = selectedCandidate != null && stage != ScannerStage.PRICING && !isAddingToDraft
}

/**
 * Owns the OCR → catalogue → edition selection → AUD pricing state machine. Camera callbacks may
 * arrive on a worker thread; every mutation is marshalled through [viewModelScope].
 */
class ScannerViewModel(
    private val identifyCard: IdentifyCardUseCase,
    private val refreshCardValue: RefreshCardValueUseCase,
    private val draftStore: DraftCardStore,
    private val clock: Clock = Clock.systemUTC(),
    private val draftIdFactory: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val mutableState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    private val recognitionAdmission = AtomicBoolean(false)
    private var pricingJob: Job? = null
    private var lastFingerprint: String? = null
    private var lastFingerprintAtMillis: Long = Long.MIN_VALUE

    init {
        viewModelScope.launch {
            draftStore.cards.collectLatest { cards ->
                mutableState.update { state -> state.copy(draftCount = cards.size) }
            }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        mutableState.update { state ->
            state.copy(
                cameraPermissionGranted = granted,
                message = if (granted) null else "Camera access is required to scan cards.",
            )
        }
    }

    fun onRecognition(result: CardRecognitionResult) {
        if (!recognitionAdmission.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                if (mutableState.value.isDraftSheetVisible) return@launch

                val identity = result.toRecognisedCardIdentity()
                val name = identity.name?.trim().orEmpty()
                val cardNumber = identity.cardNumber?.trim().orEmpty()
                if (name.isEmpty() || cardNumber.isEmpty()) return@launch

                val fingerprint = listOf(
                    name.lowercase(Locale.ROOT),
                    cardNumber.lowercase(Locale.ROOT),
                    identity.totalSetCards.orEmpty().lowercase(Locale.ROOT),
                ).joinToString("|")
                val now = clock.millis()
                if (
                    fingerprint == lastFingerprint &&
                    now - lastFingerprintAtMillis in 0 until DUPLICATE_COOLDOWN_MILLIS
                ) {
                    return@launch
                }
                lastFingerprint = fingerprint
                lastFingerprintAtMillis = now

                identify(identity)
            } finally {
                recognitionAdmission.set(false)
            }
        }
    }

    private suspend fun identify(identity: RecognisedCardIdentity) {
        mutableState.update { state ->
            state.copy(stage = ScannerStage.IDENTIFYING, message = "Matching card…")
        }

        try {
            val candidates = identifyCard(identity)
            if (candidates.isEmpty()) {
                mutableState.update { state ->
                    state.copy(
                        stage = ScannerStage.ERROR,
                        message = "No exact card match found. Hold the card steady and try again.",
                    )
                }
                return
            }

            val selected = selectSafeDefault(
                candidates = candidates,
                firstEditionStampDetected = identity.firstEditionStampDetected,
            )
            val fullCollectorNumber = identity.totalSetCards
                ?.let { total -> "${identity.cardNumber}/$total" }
                ?: requireNotNull(identity.cardNumber)

            mutableState.update { state ->
                state.copy(
                    stage = ScannerStage.PRICING,
                    candidates = candidates,
                    selectedCandidateId = selected.candidateId,
                    detectedCollectorNumber = fullCollectorNumber,
                    marketPrice = null,
                    isDraftSheetVisible = true,
                    message = null,
                )
            }
            loadPrice(selected, fullCollectorNumber)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { state ->
                state.copy(
                    stage = ScannerStage.ERROR,
                    message = "Card matching failed. Check your connection and scan again.",
                )
            }
        }
    }

    /** Called by the edition selector; unsupported choices are ignored. */
    fun selectPrinting(variant: PrintingVariant) {
        val state = mutableState.value
        val current = state.selectedCandidate ?: return
        val replacement = state.candidates.firstOrNull { candidate ->
            candidate.variantGroupId == current.variantGroupId &&
                candidate.printingVariant == variant
        } ?: return
        if (replacement.candidateId == current.candidateId) return

        mutableState.update {
            it.copy(
                selectedCandidateId = replacement.candidateId,
                marketPrice = null,
                stage = ScannerStage.PRICING,
                message = null,
            )
        }
        loadPrice(replacement, requireNotNull(state.detectedCollectorNumber))
    }

    fun retryPricing() {
        val state = mutableState.value
        val selected = state.selectedCandidate ?: return
        val collectorNumber = state.detectedCollectorNumber ?: return
        loadPrice(selected, collectorNumber)
    }

    private fun loadPrice(candidate: RecognitionCandidate, collectorNumber: String) {
        pricingJob?.cancel()
        mutableState.update { state ->
            state.copy(stage = ScannerStage.PRICING, marketPrice = null, message = null)
        }

        pricingJob = viewModelScope.launch {
            try {
                val price = refreshCardValue(
                    CardValueRequest(
                        candidateId = candidate.candidateId,
                        apiCardId = candidate.apiCardId,
                        cardName = candidate.name,
                        collectorNumber = collectorNumber,
                        setName = candidate.setName,
                        printingVariant = candidate.printingVariant,
                    ),
                )
                mutableState.update { state ->
                    if (state.selectedCandidateId != candidate.candidateId) state
                    else state.copy(stage = ScannerStage.READY, marketPrice = price, message = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { state ->
                    if (state.selectedCandidateId != candidate.candidateId) state
                    else state.copy(
                        stage = ScannerStage.READY,
                        marketPrice = null,
                        message = "Three trustworthy sold prices were not available.",
                    )
                }
            }
        }
    }

    fun addSelectedToDraft() {
        val state = mutableState.value
        val candidate = state.selectedCandidate ?: return
        val collectorNumber = state.detectedCollectorNumber ?: return
        if (!state.canAddToDraft) return

        mutableState.update { it.copy(isAddingToDraft = true, message = null) }
        viewModelScope.launch {
            try {
                draftStore.add(
                    DraftCard(
                        draftId = draftIdFactory(),
                        candidate = candidate,
                        collectorNumber = collectorNumber,
                        marketPrice = state.marketPrice,
                        detectedAtEpochMillis = clock.millis(),
                    ),
                )
                resetPreview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(isAddingToDraft = false, message = "Could not add this scan to Drafts.")
                }
            }
        }
    }

    fun dismissDraftPreview() {
        pricingJob?.cancel()
        resetPreview()
    }

    fun onCameraError(error: Throwable) {
        mutableState.update { state ->
            if (state.isDraftSheetVisible) state
            else state.copy(
                stage = ScannerStage.ERROR,
                message = error.message?.takeIf(String::isNotBlank)
                    ?: "The camera could not be started.",
            )
        }
    }

    private fun resetPreview() {
        mutableState.update { state ->
            state.copy(
                stage = ScannerStage.SCANNING,
                candidates = emptyList(),
                selectedCandidateId = null,
                detectedCollectorNumber = null,
                marketPrice = null,
                isDraftSheetVisible = false,
                isAddingToDraft = false,
                message = null,
            )
        }
    }

    private fun selectSafeDefault(
        candidates: List<RecognitionCandidate>,
        firstEditionStampDetected: Boolean,
    ): RecognitionCandidate {
        val strongestGroup = candidates.first().variantGroupId
        val group = candidates.filter { candidate -> candidate.variantGroupId == strongestGroup }

        return if (firstEditionStampDetected) {
            group.firstOrNull { it.printingVariant == PrintingVariant.FIRST_EDITION }
                ?: group.first()
        } else {
            // Never infer 1st Edition merely because the API exposes that historical price type.
            group.firstOrNull { it.printingVariant == PrintingVariant.UNLIMITED }
                ?: group.firstOrNull { it.printingVariant == PrintingVariant.STANDARD }
                ?: group.firstOrNull(RecognitionCandidate::isSuggested)
                ?: group.first()
        }
    }

    private companion object {
        const val DUPLICATE_COOLDOWN_MILLIS = 2_500L
    }
}
