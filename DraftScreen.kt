package com.yourname.pokescanner.feature.draft

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.yourname.pokescanner.domain.model.DraftCard
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private val DraftInk = Color(0xFF080B10)
private val DraftPanel = Color(0xFF151C26)
private val DraftMuted = Color(0xFF96A2B2)
private val DraftMint = Color(0xFF4DE2B1)
private val DraftBlue = Color(0xFF69B7FF)
private val DraftDanger = Color(0xFFFF5E70)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftScreen(
    viewModel: DraftViewModel,
    onBack: () -> Unit,
    onCollectionConfirmed: (addedCount: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DraftEvent.CollectionSaved -> onCollectionConfirmed(event.count)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DraftInk,
        contentColor = Color.White,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            DraftTopBar(
                cardCount = state.cards.size,
                onBack = onBack,
            )
        },
        bottomBar = {
            ConfirmCollectionBar(
                cardCount = state.cards.size,
                isSaving = state.isSaving,
                onConfirm = viewModel::confirmAndAddToCollection,
            )
        },
    ) { innerPadding ->
        if (state.cards.isEmpty()) {
            EmptyDraftState(Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = 8.dp,
                    bottom = 18.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.errorMessage?.let { message ->
                    item(key = "save-error") {
                        SaveErrorBanner(message = message, onDismiss = viewModel::clearError)
                    }
                }
                item(key = "hint") {
                    Text(
                        "Swipe a card left to discard a bad scan.",
                        color = DraftMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                    )
                }
                items(
                    items = state.cards,
                    key = DraftCard::draftId,
                ) { card ->
                    DismissibleDraftCard(
                        card = card,
                        enabled = !state.isSaving,
                        onDismiss = { viewModel.discard(card.draftId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftTopBar(cardCount: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("Back", color = DraftBlue, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "DRAFT LIST",
                color = DraftMint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.7.sp,
            )
            Text("Review scans", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
        }
        Surface(
            shape = CircleShape,
            color = DraftPanel,
            contentColor = Color.White,
        ) {
            Text(
                cardCount.toString(),
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleDraftCard(
    card: DraftCard,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { newValue ->
            if (newValue == SwipeToDismissBoxValue.EndToStart && enabled) {
                onDismiss()
                true
            } else {
                false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DraftDanger)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "DISCARD",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
            }
        },
    ) {
        DraftCardRow(card)
    }
}

@Composable
private fun DraftCardRow(card: DraftCard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DraftPanel),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AsyncImage(
                model = card.candidate.largeImageUrl ?: card.candidate.smallImageUrl,
                contentDescription = "${card.candidate.name} card artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(82.dp)
                    .height(115.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DraftInk),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            card.candidate.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${card.candidate.setName} • ${card.collectorNumber}",
                            color = DraftMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        card.marketPrice?.market?.let(::formatDraftAud) ?: "—",
                        color = DraftMint,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DraftBlue.copy(alpha = 0.12f),
                    contentColor = DraftBlue,
                ) {
                    Text(
                        card.candidate.printingLabel,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(3) { index ->
                        val sale = card.marketPrice?.recentSales?.getOrNull(index)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DraftInk)
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                sale?.amount?.let(::formatDraftAud) ?: "—",
                                color = if (sale == null) DraftMuted else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmCollectionBar(
    cardCount: Int,
    isSaving: Boolean,
    onConfirm: () -> Unit,
) {
    Surface(
        color = DraftInk.copy(alpha = 0.98f),
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Button(
                onClick = onConfirm,
                enabled = cardCount > 0 && !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DraftMint,
                    contentColor = DraftInk,
                    disabledContainerColor = DraftPanel,
                    disabledContentColor = DraftMuted,
                ),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(21.dp),
                        strokeWidth = 2.dp,
                        color = DraftInk,
                    )
                } else {
                    Text(
                        if (cardCount == 0) "No cards to confirm"
                        else "Confirm & Add to Collection ($cardCount)",
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDraftState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(DraftMint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", color = DraftMint, fontSize = 38.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Your Draft List is empty",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan a card, verify the printing and add it here before committing it to your collection.",
            color = DraftMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SaveErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DraftDanger.copy(alpha = 0.14f))
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = DraftDanger, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatDraftAud(value: BigDecimal): String = NumberFormat
    .getCurrencyInstance(Locale("en", "AU"))
    .format(value)
