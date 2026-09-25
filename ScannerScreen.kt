package com.yourname.pokescanner.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.yourname.pokescanner.core.camera.CardFrameAnalyser
import com.yourname.pokescanner.core.recognition.CardRecognitionResult
import com.yourname.pokescanner.core.recognition.OcrCardRecognizer
import com.yourname.pokescanner.domain.model.MarketSale
import com.yourname.pokescanner.domain.model.PrintingVariant
import com.yourname.pokescanner.domain.model.RecognitionCandidate
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val Ink = Color(0xFF080B10)
private val ElevatedInk = Color(0xFF111720)
private val Panel = Color(0xFF171E29)
private val Muted = Color(0xFF96A2B2)
private val ElectricMint = Color(0xFF4DE2B1)
private val IceBlue = Color(0xFF69B7FF)
private val Danger = Color(0xFFFF6B78)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    onOpenDraftList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraPermissionGranted = granted }

    LaunchedEffect(cameraPermissionGranted) {
        viewModel.onCameraPermissionResult(cameraPermissionGranted)
    }
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraPermissionGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Ink) {
        Box(Modifier.fillMaxSize()) {
            if (cameraPermissionGranted) {
                LiveCameraPreview(
                    analysisEnabled = !state.isDraftSheetVisible &&
                        state.stage != ScannerStage.IDENTIFYING,
                    onRecognition = viewModel::onRecognition,
                    onError = viewModel::onCameraError,
                )
                ScannerTargetOverlay(stage = state.stage)
            } else {
                CameraPermissionPanel(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                )
            }

            ScannerTopBar(
                draftCount = state.draftCount,
                onOpenDraftList = onOpenDraftList,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )

            if (cameraPermissionGranted) {
                ScannerStatus(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(horizontal = 20.dp, vertical = 26.dp),
                )
            }
        }
    }

    if (state.isDraftSheetVisible && state.selectedCandidate != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissDraftPreview,
            sheetState = sheetState,
            containerColor = ElevatedInk,
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.72f),
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 42.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                )
            },
        ) {
            DraftCardSheet(
                state = state,
                onSelectPrinting = viewModel::selectPrinting,
                onRetryPricing = viewModel::retryPricing,
                onAddToDraft = viewModel::addSelectedToDraft,
            )
        }
    }
}

@Composable
private fun LiveCameraPreview(
    analysisEnabled: Boolean,
    onRecognition: (CardRecognitionResult) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestRecognition = rememberUpdatedState(onRecognition)
    val latestError = rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val recognizer = remember { OcrCardRecognizer() }
    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pokemon-card-analysis").apply { priority = Thread.NORM_PRIORITY }
        }
    }
    val analyser = remember(recognizer) {
        CardFrameAnalyser(
            recognizer = recognizer,
            onRecognition = { result -> latestRecognition.value(result) },
            onError = { error -> latestError.value(error) },
        )
    }

    // Declared before the binding effect so binding is disposed first, then OCR resources close.
    DisposableEffect(analyser, recognizer, cameraExecutor) {
        onDispose {
            analyser.close()
            recognizer.close()
            cameraExecutor.shutdown()
        }
    }

    DisposableEffect(
        lifecycleOwner,
        previewView,
        analyser,
        cameraExecutor,
        analysisEnabled,
    ) {
        val disposed = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null

        providerFuture.addListener(
            {
                if (disposed.get()) return@addListener
                try {
                    provider = providerFuture.get()
                    val previewUseCase = Preview.Builder().build().also { useCase ->
                        useCase.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    preview = previewUseCase
                    if (analysisEnabled) {
                        val analysisUseCase = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { useCase -> useCase.setAnalyzer(cameraExecutor, analyser) }
                        analysis = analysisUseCase
                        provider?.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            previewUseCase,
                            analysisUseCase,
                        )
                    } else {
                        provider?.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            previewUseCase,
                        )
                    }
                } catch (error: Exception) {
                    latestError.value(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed.set(true)
            analysis?.clearAnalyzer()
            val useCases = listOfNotNull(preview, analysis).toTypedArray()
            if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ScannerTargetOverlay(stage: ScannerStage) {
    val transition = rememberInfiniteTransition(label = "scan-line")
    val scanProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-progress",
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val maximumWidth = size.width * 0.82f
            val maximumHeight = size.height * 0.64f
            val guideWidth = minOf(maximumWidth, maximumHeight / CARD_HEIGHT_RATIO)
            val guideHeight = guideWidth * CARD_HEIGHT_RATIO
            val left = (size.width - guideWidth) / 2f
            val top = (size.height - guideHeight) / 2f
            val guideRect = Rect(left, top, left + guideWidth, top + guideHeight)
            val corner = 28.dp.toPx()

            val scrimPath = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(guideRect, CornerRadius(corner, corner)))
            }
            drawPath(scrimPath, Color.Black.copy(alpha = 0.58f))
            drawRoundRect(
                brush = Brush.linearGradient(listOf(ElectricMint, IceBlue)),
                topLeft = guideRect.topLeft,
                size = guideRect.size,
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = 2.dp.toPx()),
            )

            if (stage == ScannerStage.SCANNING) {
                val y = guideRect.top + (guideRect.height * scanProgress)
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, ElectricMint, Color.Transparent),
                        startX = guideRect.left,
                        endX = guideRect.right,
                    ),
                    start = Offset(guideRect.left + 12.dp.toPx(), y),
                    end = Offset(guideRect.right - 12.dp.toPx(), y),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

@Composable
private fun ScannerTopBar(
    draftCount: Int,
    onOpenDraftList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "CARD SCANNER",
                color = ElectricMint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp,
            )
            Text(
                text = "PokéVault",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Surface(
            onClick = onOpenDraftList,
            shape = RoundedCornerShape(18.dp),
            color = Panel.copy(alpha = 0.94f),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Drafts", fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(ElectricMint)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = draftCount.toString(),
                        color = Ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerStatus(state: ScannerUiState, modifier: Modifier = Modifier) {
    val (label, colour) = when (state.stage) {
        ScannerStage.SCANNING -> "Align the full card inside the frame" to Color.White
        ScannerStage.IDENTIFYING -> "Identifying card…" to ElectricMint
        ScannerStage.PRICING -> "Finding recent Australian sales…" to IceBlue
        ScannerStage.READY -> "Card captured" to ElectricMint
        ScannerStage.ERROR -> (state.message ?: "Try again") to Danger
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Panel.copy(alpha = 0.94f),
        contentColor = colour,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.stage == ScannerStage.IDENTIFYING || state.stage == ScannerStage.PRICING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colour,
                    strokeWidth = 2.dp,
                )
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
            }
            Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CameraPermissionPanel(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(ElectricMint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("◎", color = ElectricMint, fontSize = 42.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Camera access needed",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(9.dp))
        Text(
            "The live scanner needs the camera to read the card name and collector number.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = ElectricMint, contentColor = Ink),
        ) {
            Text("Allow camera", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DraftCardSheet(
    state: ScannerUiState,
    onSelectPrinting: (PrintingVariant) -> Unit,
    onRetryPricing: () -> Unit,
    onAddToDraft: () -> Unit,
) {
    val candidate = requireNotNull(state.selectedCandidate)
    val groupCandidates = state.candidates.filter { other ->
        other.variantGroupId == candidate.variantGroupId
    }
    val hasFirstEdition = groupCandidates.any {
        it.printingVariant == PrintingVariant.FIRST_EDITION
    }
    val hasUnlimited = groupCandidates.any {
        it.printingVariant == PrintingVariant.UNLIMITED
    }
    val hasShadowless = groupCandidates.any {
        it.printingVariant == PrintingVariant.SHADOWLESS
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.93f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = "DRAFT CARD",
            color = ElectricMint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AsyncImage(
                model = candidate.largeImageUrl ?: candidate.smallImageUrl,
                contentDescription = "${candidate.name} card artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(112.dp)
                    .height(157.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    candidate.name,
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(candidate.setName, color = IceBlue, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.detectedCollectorNumber.orEmpty()}  •  ${candidate.rarity ?: "Unknown rarity"}",
                    color = Muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Detected printing", color = Muted, fontSize = 12.sp)
                Text(
                    candidate.printingLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (hasFirstEdition && hasUnlimited) {
            Spacer(Modifier.height(22.dp))
            Text(
                "Choose the exact printing",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ink)
                    .padding(4.dp),
            ) {
                PrintingToggleOption(
                    label = "1st Edition",
                    selected = candidate.printingVariant == PrintingVariant.FIRST_EDITION,
                    onClick = { onSelectPrinting(PrintingVariant.FIRST_EDITION) },
                    modifier = Modifier.weight(1f),
                )
                PrintingToggleOption(
                    label = "Unlimited",
                    selected = candidate.printingVariant == PrintingVariant.UNLIMITED,
                    onClick = { onSelectPrinting(PrintingVariant.UNLIMITED) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Unlimited is selected by default unless the scanner reads a 1st Edition stamp.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (hasShadowless) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onSelectPrinting(PrintingVariant.SHADOWLESS) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (
                            candidate.printingVariant == PrintingVariant.SHADOWLESS
                        ) IceBlue.copy(alpha = 0.16f) else Color.Transparent,
                        contentColor = IceBlue,
                    ),
                ) {
                    Text(
                        if (candidate.printingVariant == PrintingVariant.SHADOWLESS) {
                            "✓ Shadowless selected"
                        } else {
                            "This card is Shadowless"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Recent eBay Australia sales", color = Color.White, fontWeight = FontWeight.Bold)
        Text("Raw • Near Mint • Sold prices in AUD", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        when {
            state.stage == ScannerStage.PRICING -> PriceLoadingPanel()
            state.marketPrice != null -> SoldPricePanel(state.marketPrice.recentSales)
            else -> PriceUnavailablePanel(
                message = state.message ?: "No current comparable sales were found.",
                onRetry = onRetryPricing,
            )
        }

        Spacer(Modifier.height(22.dp))
        state.marketPrice?.market?.let { market ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ElectricMint.copy(alpha = 0.10f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Estimated raw value", color = Muted, fontSize = 12.sp)
                    Text("Median of 3 sales", color = Color.White, fontWeight = FontWeight.Medium)
                }
                Text(
                    formatAud(market),
                    color = ElectricMint,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        Button(
            onClick = onAddToDraft,
            enabled = state.canAddToDraft,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricMint,
                contentColor = Ink,
                disabledContainerColor = Panel,
                disabledContentColor = Muted,
            ),
        ) {
            if (state.isAddingToDraft) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Ink,
                )
            } else {
                Text(
                    if (state.marketPrice == null) "Add to Draft without price" else "Add to Draft",
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

@Composable
private fun PrintingToggleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) ElectricMint else Color.Transparent)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Ink else Muted,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PriceLoadingPanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(16.dp),
    ) {
        Text("Checking completed listings…", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = IceBlue,
            trackColor = Color.White.copy(alpha = 0.08f),
        )
    }
}

@Composable
private fun SoldPricePanel(sales: List<MarketSale>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        repeat(3) { index ->
            val sale = sales.getOrNull(index)
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
            ) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 14.dp)) {
                    Text("SOLD ${index + 1}", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        sale?.amount?.let(::formatAud) ?: "—",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceUnavailablePanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(16.dp),
    ) {
        Text(message, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRetry) {
            Text("Retry sold prices", color = IceBlue, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatAud(value: BigDecimal): String = NumberFormat
    .getCurrencyInstance(Locale("en", "AU"))
    .format(value)

private const val CARD_HEIGHT_RATIO = 88.9f / 63.5f
