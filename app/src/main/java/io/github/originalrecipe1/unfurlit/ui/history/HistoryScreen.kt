package io.github.originalrecipe1.unfurlit.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.compose.itemContentType
import androidx.paging.insertSeparators
import androidx.paging.map
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.originalrecipe1.unfurlit.data.history.StoredHistoryThumbnail
import io.github.originalrecipe1.unfurlit.R
import io.github.originalrecipe1.unfurlit.domain.model.HistoryEntry
import io.github.originalrecipe1.unfurlit.domain.model.HistoryMediaKind
import java.net.URI
import java.util.Calendar

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpen: (HistoryEntry) -> Unit,
    visible: Boolean = true,
    prepareForNextVisit: Boolean = false,
) {
    val entries = viewModel.history.collectAsLazyPagingItems()
    var awaitingNewest by remember { mutableStateOf(false) }
    val prepare = visible || prepareForNextVisit
    var wasPrepared by remember { mutableStateOf(prepare) }
    LaunchedEffect(prepare) {
        if (prepare && !wasPrepared) {
            if (entries.itemCount > 0 && !entries.loadState.prepend.endOfPaginationReached) {
                awaitingNewest = true
                viewModel.loadNewest()
            } else if (entries.loadState.refresh is LoadState.Error) {
                entries.retry()
            }
        }
        wasPrepared = prepare
    }
    val newestReady = awaitingNewest &&
        entries.loadState.refresh is LoadState.NotLoading &&
        entries.loadState.prepend.endOfPaginationReached
    HistoryScreen(
        entries, onBack, onOpen, viewModel::remove, viewModel::clear, prepare,
        resetToNewest = newestReady,
    )
    SideEffect { if (newestReady) awaitingNewest = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryScreen(
    entries: LazyPagingItems<HistoryListItem>,
    onBack: () -> Unit,
    onOpen: (HistoryEntry) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    visible: Boolean = true,
    resetToNewest: Boolean = false,
) {
    val listState = rememberLazyListState()
    var prepared by remember { mutableStateOf(false) }
    SideEffect {
        // Request the top before the next layout, rather than suspending until after
        // History has drawn. The pager also prepares us once fully offscreen.
        // A cancelled Back never changes this flag, preserving the current place.
        if ((visible && !prepared) || resetToNewest) listState.requestScrollToItem(0)
        prepared = visible
    }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    val refresh = entries.loadState.refresh

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "Back")
                    }
                },
                actions = {
                    if (entries.itemCount > 0) {
                        TextButton(onClick = { showClearConfirmation = true }) {
                            Text("Clear all")
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                entries.itemCount == 0 && refresh is LoadState.Loading -> HistoryMessage("Loading history…", showProgress = true)
                entries.itemCount == 0 && refresh is LoadState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f)) { HistoryMessage("History is unavailable", "Your history could not be loaded.") }
                    TextButton(onClick = entries::retry) { Text("Retry") }
                }
                entries.itemCount == 0 -> HistoryMessage("A little rewind", "Media you watch will appear here.\nOpen a link to start your collection.")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (refresh is LoadState.Error) {
                        item(key = "refresh-error") { HistoryLoadStatus(refresh, entries::retry) }
                    }
                    if (entries.loadState.prepend is LoadState.Loading || entries.loadState.prepend is LoadState.Error) {
                        item(key = "prepend-status") { HistoryLoadStatus(entries.loadState.prepend, entries::retry) }
                    }
                    items(
                        count = entries.itemCount,
                        key = entries.itemKey { it.key },
                        contentType = entries.itemContentType { if (it is HistoryListItem.Day) "day" else "entry" },
                    ) { index ->
                        when (val item = entries[index]) {
                            is HistoryListItem.Day -> HistoryDay(item.timestamp)
                            is HistoryListItem.Visit -> HistoryRow(
                                entry = item.entry,
                                onOpen = { onOpen(item.entry) },
                                onRemove = { onRemove(item.entry.id) },
                                modifier = Modifier.animateItem(),
                            )
                            null -> Unit
                        }
                    }
                    if (entries.loadState.append is LoadState.Loading || entries.loadState.append is LoadState.Error) {
                        item(key = "append-status") { HistoryLoadStatus(entries.loadState.append, entries::retry) }
                    }
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), null) },
            title = { Text("Clear viewing history?") },
            text = { Text("This removes all visits and their saved thumbnails from this device.") },
            confirmButton = {
                TextButton(onClick = { showClearConfirmation = false; onClear() }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

internal sealed interface HistoryListItem {
    val key: String
    data class Visit(val entry: HistoryEntry, val day: Long) : HistoryListItem {
        override val key get() = "visit-${entry.id}"
    }
    data class Day(val timestamp: Long) : HistoryListItem {
        override val key get() = "day-$timestamp"
    }
}

internal fun PagingData<HistoryEntry>.withDateHeaders(): PagingData<HistoryListItem> =
    map<HistoryEntry, HistoryListItem.Visit> { entry ->
        val day = Calendar.getInstance().apply {
            timeInMillis = entry.viewedAtEpochMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        HistoryListItem.Visit(entry, day)
    }.insertSeparators<HistoryListItem.Visit, HistoryListItem> { before, after ->
        if (after != null && before?.day != after.day) HistoryListItem.Day(after.day) else null
    }

@Composable
private fun HistoryLoadStatus(state: LoadState, retry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is LoadState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
            is LoadState.Error -> TextButton(onClick = retry) { Text("Couldn’t load history. Retry") }
            else -> Unit
        }
    }
}

@Composable
private fun HistoryDay(day: Long) {
    val context = LocalContext.current
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val label = when {
        DateUtils.isToday(day) -> "Today"
        Calendar.getInstance().apply { timeInMillis = day }.let {
            it.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                it.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
        } -> "Yesterday"
        else -> DateUtils.formatDateTime(context, day, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
    Text(
        label,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = remember(entry.sourceUrl) { entry.sourceHost() }
    val title = entry.title ?: host ?: "Untitled media"
    val time = remember(context, entry.viewedAtEpochMillis) {
        DateUtils.formatDateTime(context, entry.viewedAtEpochMillis, DateUtils.FORMAT_SHOW_TIME)
    }
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HistoryThumbnail(entry)
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.platform ?: host ?: "Media",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.author?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${entry.mediaDescription()} · $time",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    "Remove $title from history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryThumbnail(entry: HistoryEntry) {
    val context = LocalContext.current
    val thumbnailRequest = remember(context, entry.id, entry.viewedAtEpochMillis, entry.thumbnail, entry.hasThumbnail) {
        if (entry.hasThumbnail) {
            ImageRequest.Builder(context)
                .data(entry.thumbnail ?: StoredHistoryThumbnail(entry.id))
                .memoryCacheKey("history-${entry.id}-${entry.viewedAtEpochMillis}")
                .size(192, 192)
                .build()
        } else null
    }
    val icon = when (entry.mediaKind) {
        HistoryMediaKind.Video -> R.drawable.ic_play
        HistoryMediaKind.Audio -> R.drawable.ic_audio
        HistoryMediaKind.Image -> R.drawable.ic_image
        HistoryMediaKind.Gallery, HistoryMediaKind.Mixed -> R.drawable.ic_gallery
    }
    Box(
        Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        thumbnailRequest?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (entry.hasThumbnail) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Icon(painterResource(icon), null, Modifier.padding(3.dp).size(14.dp))
            }
        }
    }
}

@Composable
private fun HistoryMessage(title: String, description: String? = null, showProgress: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (showProgress) {
            CircularProgressIndicator()
        } else {
            Box(
                Modifier.size(96.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_history), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        description?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    }
}

private fun HistoryEntry.mediaDescription(): String {
    val media = when {
        mediaCount > 1 -> "$mediaCount items"
        mediaKind == HistoryMediaKind.Video -> "Video"
        mediaKind == HistoryMediaKind.Image -> "Image"
        mediaKind == HistoryMediaKind.Audio -> "Audio"
        mediaKind == HistoryMediaKind.Gallery -> "Gallery"
        else -> "Media"
    }
    return durationSeconds?.let { "$media · ${it.formattedDuration()}" } ?: media
}

private fun Long.formattedDuration(): String {
    val hours = this / 3_600
    val minutes = (this % 3_600) / 60
    val seconds = this % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun HistoryEntry.sourceHost(): String? = runCatching { URI(sourceUrl).host }.getOrNull()
