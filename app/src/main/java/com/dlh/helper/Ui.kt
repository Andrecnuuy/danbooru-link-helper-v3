@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.dlh.helper

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GRID_COLUMNS = 3

private enum class Confirm { CLEAR_ASSETS, RESET_MARKER }

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "never" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

/** When the post was made: stored for most links, and X post ids carry their own time. 0 when unknown. */
private fun postTime(item: LinkItem): Long = when {
    item.timeMs > 0L -> item.timeMs
    item.key.startsWith("tw:") -> UrlParsers.twitterTimeMs(item.key.substringAfter(':'))
    else -> 0L
}

private fun kindLabel(item: LinkItem): String? = when (item.kind) {
    KIND_VIDEO -> if (item.key.startsWith("px:")) "animated" else "video"
    KIND_MIXED -> "images + video"
    else -> null
}

/** A tap: ticks the link while selecting, otherwise opens its menu. */
private fun onItemClick(vm: MainViewModel, item: LinkItem) {
    if (vm.selected.isNotEmpty()) vm.toggleSelect(item.key) else vm.actionItem = item
}

private fun openLink(handler: UriHandler, url: String, vm: MainViewModel) {
    try {
        handler.openUri(url)
    } catch (e: Exception) {
        vm.linksNote = "Could not open the link: ${e.message}"
    }
}

@Composable
fun HelperApp(vm: MainViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val clipboard = LocalClipboardManager.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> if (uri != null) vm.exportAssets(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) vm.importAssets(uri) }
    val saveTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> if (uri != null) vm.saveLinks(uri) }
    val cookieLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) vm.importCookies(uri) }

    var tab by remember { mutableIntStateOf(0) }
    val selecting = vm.selected.isNotEmpty() && tab == 0
    BackHandler(enabled = selecting) { vm.clearSelection() }

    val gridRows = remember(vm.items, vm.gridView) {
        if (vm.gridView) vm.items.chunked(GRID_COLUMNS) else emptyList<List<LinkItem>>()
    }
    val pairs = if (tab == 1) vm.visiblePairs() else emptyList()

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${vm.selected.size} selected") },
                    actions = { TextButton(onClick = { vm.clearSelection() }) { Text("Cancel") } }
                )
            } else {
                TopAppBar(
                    title = { Text("Danbooru Link Helper") },
                    actions = {
                        TextButton(onClick = { showStats = true }) { Text("Stats") }
                        TextButton(onClick = { showSettings = true }) { Text("Settings") }
                    }
                )
            }
        },
        bottomBar = {
            if (selecting) SelectionBar(vm)
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).imePadding(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (vm.gridView) 4.dp else 2.dp)
        ) {
            item { ArtistCard(vm) }
            item { Spacer(Modifier.height(8.dp)) }
            item { ApiCard(vm, onImportCookies = { cookieLauncher.launch(arrayOf("*/*")) }) }
            item { Spacer(Modifier.height(8.dp)) }
            item { ArtistPostsCard(vm) }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                AssetsCard(
                    vm = vm,
                    onExport = { exportLauncher.launch("danbooru-link-helper-assets.json") },
                    onImport = { importLauncher.launch(arrayOf("*/*")) }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Links") })
                    Tab(selected = tab == 1, onClick = { vm.clearSelection(); tab = 1 }, text = { Text("Similar images") })
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            if (tab == 0) {
                item {
                    LinksCard(
                        vm = vm,
                        onCopy = {
                            try {
                                clipboard.setText(AnnotatedString(vm.allLinksText()))
                                vm.linksNote = "Copied ${vm.linksInScope()} links."
                            } catch (e: Exception) {
                                vm.linksNote = "Copy failed (${e.message}). Use Save .txt instead."
                            }
                        },
                        onSave = { saveTxtLauncher.launch("artist-links.txt") }
                    )
                }
                item { Spacer(Modifier.height(6.dp)) }
                if (vm.gridView) {
                    items(gridRows, key = { it.first().key }) { row -> GridRow(vm, row) }
                } else {
                    items(vm.items, key = { it.key }) { item -> LinkRow(vm, item) }
                }
            } else {
                item { SimilarCard(vm, pairs.size) }
                item { Spacer(Modifier.height(6.dp)) }
                items(pairs, key = { it.key }) { pair -> PairRow(vm, pair) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(vm = vm, onDismiss = { showSettings = false })
    }
    if (showStats) {
        StatsDialog(
            vm = vm,
            onDismiss = { showStats = false },
            onResetMarker = { confirm = Confirm.RESET_MARKER },
            onClearAssets = { confirm = Confirm.CLEAR_ASSETS }
        )
    }
    when (confirm) {
        Confirm.CLEAR_ASSETS -> ConfirmDialog(
            text = "Delete every stored media asset link? You can import a backup afterwards if you exported one.",
            action = "Delete",
            onConfirm = { vm.clearStoredAssets(); confirm = null },
            onDismiss = { confirm = null }
        )
        Confirm.RESET_MARKER -> ConfirmDialog(
            text = "Forget where the last fetch stopped? The next fetch will go through all your uploads again (stored links are kept).",
            action = "Reset",
            onConfirm = { vm.resetAssetMarker(); confirm = null },
            onDismiss = { confirm = null }
        )
        null -> {}
    }

    if (vm.simWarning > 0) {
        val n = vm.simWarning
        ConfirmDialog(
            text = "Deleting the marked posts would leave $n group${if (n == 1) "" else "s"} of look-alike pictures with no copy at all " +
                "(every post of the group is marked). Delete anyway?",
            action = "Delete anyway",
            onConfirm = { vm.confirmDeleteMarked() },
            onDismiss = { vm.cancelSimWarning() }
        )
    }
    vm.actionItem?.let { ItemMenuDialog(vm, it) }
    vm.previewItem?.let { PreviewDialog(vm, it) }
}

// ---------------------------------------------------------------------- building blocks

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusText(text: String) {
    if (text.isNotBlank()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BusyBar(busy: Boolean) {
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

// ---------------------------------------------------------------------- the link list and grid

/** One link in the list. Tap for the menu; long-press to start selecting. */
@Composable
private fun LinkRow(vm: MainViewModel, item: LinkItem) {
    val isSelected = item.key in vm.selected
    val background = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .combinedClickable(
                onClick = { onItemClick(vm, item) },
                onLongClick = { vm.toggleSelect(item.key) }
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        }
        Text(
            item.url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val label = kindLabel(item)
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun GridRow(vm: MainViewModel, row: List<LinkItem>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in row) {
            GridCell(vm, item, Modifier.weight(1f))
        }
        for (i in row.size until GRID_COLUMNS) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** One thumbnail of the grid, with the post date, a video / multi-image badge and the selection mark. */
@Composable
private fun GridCell(vm: MainViewModel, item: LinkItem, modifier: Modifier) {
    val isSelected = item.key in vm.selected
    val info by produceState<MediaInfo?>(Previews.fromItem(item), item.key, item.thumb, vm.lazyPixivThumbs) {
        val stored = Previews.fromItem(item)
        if (stored != null) {
            value = stored
        } else if (item.key.startsWith("px:") && vm.lazyPixivThumbs) {
            value = Previews.resolve(item, vm.pixivCookie())
        }
    }
    val scrim = Color(0x99000000)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = { onItemClick(vm, item) },
                onLongClick = { vm.toggleSelect(item.key) }
            )
    ) {
        val shown = info
        if (shown == null) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            RemoteImage(
                url = if (vm.lowData) shown.thumbLow else shown.thumb,
                referer = shown.referer,
                targetPx = if (vm.lowData) 180 else 360,
                modifier = Modifier.fillMaxSize()
            )
        }
        val label = kindLabel(item)
        if (label != null) {
            Text(
                "▶",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(scrim, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp)
            )
        }
        if (item.count > 1) {
            Text(
                "×${item.count}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(scrim, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp)
            )
        }
        val time = postTime(item).takeIf { it > 0L } ?: info?.timeMs ?: 0L
        if (time > 0L) {
            Text(
                formatDate(time),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(scrim, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp)
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

/** Bottom bar while selecting: delete, fill the gap between two ticked links, tick all, flip the selection. */
@Composable
private fun SelectionBar(vm: MainViewModel) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { vm.deleteSelected() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete (${vm.selected.size})") }
            TextButton(onClick = { vm.selectBetween() }, enabled = vm.selected.size >= 2) { Text("Between") }
            TextButton(onClick = { vm.selectAll() }) { Text("All") }
            TextButton(onClick = { vm.invertSelection() }) { Text("Invert") }
        }
    }
}

// ---------------------------------------------------------------------- the link menu and preview

@Composable
private fun MenuButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (danger) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        }
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
}

@Composable
private fun ItemMenuDialog(vm: MainViewModel, item: LinkItem) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { vm.actionItem = null },
        title = { Text("Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                MenuButton("Preview") {
                    vm.actionItem = null
                    vm.previewItem = item
                }
                MenuButton("Open link") {
                    openLink(uriHandler, item.url, vm)
                    vm.actionItem = null
                }
                MenuButton("Delete this link", danger = true) {
                    vm.deleteKeys(setOf(item.key))
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.actionItem = null }) { Text("Cancel") } }
    )
}

/**
 * Shows the post: its picture (or, for a video, the poster with a play button, so nothing heavy is
 * downloaded until asked), the post date and what is in it. If no picture can be had, only the date and the buttons.
 */
@Composable
private fun PreviewDialog(vm: MainViewModel, item: LinkItem) {
    val uriHandler = LocalUriHandler.current
    var looked by remember(item.key) { mutableStateOf(false) }
    var playing by remember(item.key) { mutableStateOf(false) }
    var videoFailed by remember(item.key) { mutableStateOf(false) }
    val info by produceState<MediaInfo?>(null, item.key) {
        value = Previews.resolve(item, vm.pixivCookie())
        looked = true
    }
    val shown = info

    AlertDialog(
        onDismissRequest = { vm.previewItem = null },
        title = { Text("Preview") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val clip = shown?.full
                    if (shown != null && shown.video && clip != null && playing && !videoFailed) {
                        VideoPlayer(url = clip, modifier = Modifier.fillMaxSize(), onError = { videoFailed = true })
                    } else if (shown != null) {
                        RemoteImage(
                            url = if (shown.video) shown.thumb else (shown.full ?: shown.thumb),
                            referer = shown.referer,
                            targetPx = 1200,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (shown.video && !videoFailed) {
                            Button(onClick = { playing = true }) { Text("Play video") }
                        }
                    } else if (!looked) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            "No picture could be loaded for this link. You can still open it.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                val time = postTime(item).takeIf { it > 0L } ?: shown?.timeMs ?: 0L
                val count = shown?.count ?: item.count
                val parts = ArrayList<String>()
                parts.add(if (time > 0L) "Posted " + formatTime(time) else "Post date unknown")
                if (count > 1) parts.add("$count items")
                kindLabel(item)?.let { parts.add(it) }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                Text(
                    item.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (videoFailed) {
                    Text(
                        "The video could not be played here. Open the link to watch it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                ButtonRow {
                    Button(onClick = { openLink(uriHandler, item.url, vm) }) { Text("Open link") }
                    OutlinedButton(
                        onClick = { vm.deleteKeys(setOf(item.key)) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                    TextButton(onClick = { vm.previewItem = null }) { Text("Close") }
                }
            }
        },
        confirmButton = {}
    )
}

// ---------------------------------------------------------------------- the five sections

@Composable
private fun ArtistCard(vm: MainViewModel) {
    SectionCard("1 · Artist link") {
        OutlinedTextField(
            value = vm.artistInput,
            onValueChange = { vm.artistInput = it },
            label = { Text("Twitter/X or Pixiv profile link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Text("Include", style = MaterialTheme.typography.labelLarge)
        ButtonRow {
            FilterChip(
                selected = vm.mediaFilter == MediaFilter.BOTH,
                onClick = { vm.chooseFilter(MediaFilter.BOTH) },
                label = { Text("Images + video") }
            )
            FilterChip(
                selected = vm.mediaFilter == MediaFilter.IMAGES,
                onClick = { vm.chooseFilter(MediaFilter.IMAGES) },
                label = { Text("Images only") }
            )
            FilterChip(
                selected = vm.mediaFilter == MediaFilter.VIDEOS,
                onClick = { vm.chooseFilter(MediaFilter.VIDEOS) },
                label = { Text("Video only") }
            )
        }
        StatusText("On Pixiv, video means animated works (ugoira).")
        ButtonRow {
            Button(onClick = { vm.startScan() }, enabled = !vm.scanBusy) { Text("Scan posts") }
            if (vm.scanBusy) OutlinedButton(onClick = { vm.stopScan() }) { Text("Stop") }
            OutlinedButton(onClick = { vm.clearAll() }) { Text("Clear / new artist") }
        }
        BusyBar(vm.scanBusy)
        StatusText(vm.scanStatus)
    }
}

@Composable
private fun ApiCard(vm: MainViewModel, onImportCookies: () -> Unit) {
    SectionCard("2 · Danbooru API & cookies") {
        var showKey by remember { mutableStateOf(false) }
        var showPaste by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = vm.apiLogin,
            onValueChange = { vm.setLogin(it) },
            label = { Text("Danbooru username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.apiKey,
            onValueChange = { vm.updateApiKey(it) },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        ButtonRow {
            Button(onClick = { vm.testApi() }, enabled = !vm.apiBusy) { Text("Test connection") }
            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide key" else "Show key") }
        }
        BusyBar(vm.apiBusy)
        StatusText(vm.apiStatus)

        HorizontalDivider()
        Text("Cookies for X and Pixiv (cookies.txt)", style = MaterialTheme.typography.titleSmall)
        Text(
            "X scans need them (they are your login). Pixiv uses them to show login-only and R-18 works. " +
                "Importing a new file replaces the old cookies.",
            style = MaterialTheme.typography.bodySmall
        )
        ButtonRow {
            Button(onClick = onImportCookies) { Text("Import cookies.txt") }
            OutlinedButton(onClick = { showPaste = true }) { Text("Paste") }
            OutlinedButton(onClick = { vm.clearCookies() }, enabled = vm.cookieList.isNotEmpty()) { Text("Clear") }
        }
        StatusText(if (vm.cookieStatus.isNotBlank()) vm.cookieStatus else "No cookies loaded.")
        if (showPaste) {
            PasteCookiesDialog(
                onDismiss = { showPaste = false },
                onSave = {
                    vm.importCookiesText(it)
                    showPaste = false
                }
            )
        }
    }
}

@Composable
private fun ArtistPostsCard(vm: MainViewModel) {
    SectionCard("3 · Artist post check") {
        OutlinedTextField(
            value = vm.artistTag,
            onValueChange = { vm.onTagEdited(it) },
            label = { Text("Artist tag (filled in automatically, editable)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (vm.candidates.size > 1) {
            Text("Other matching artists:", style = MaterialTheme.typography.bodySmall)
            ButtonRow {
                for (c in vm.candidates) {
                    FilterChip(
                        selected = c.name == vm.artistTag,
                        onClick = { vm.fetchArtistPosts(c.name) },
                        label = { Text(c.name) }
                    )
                }
            }
        }
        val overlap = remember(vm.sources, vm.danbooruKeys) {
            vm.sources.sumOf { s -> s.items.count { Matcher.isKnown(it, vm.danbooruKeys) } }
        }
        ButtonRow {
            Button(onClick = { vm.fetchArtistPosts() }, enabled = !vm.artistBusy) { Text("Fetch artist posts") }
            OutlinedButton(
                onClick = { vm.purgeDanbooru() },
                enabled = vm.danbooruKeys.isNotEmpty() && vm.totalLinks > 0
            ) { Text("Purge ($overlap)") }
            if (vm.artistBusy) OutlinedButton(onClick = { vm.stopArtist() }) { Text("Stop") }
        }
        BusyBar(vm.artistBusy)
        StatusText(vm.artistStatus)
        if (vm.sources.size > 1) StatusText("Purge applies to all ${vm.sources.size} sources.")

        HorizontalDivider()
        Text("Other platforms", style = MaterialTheme.typography.titleSmall)
        Text(
            "Reads the artist's link list on Danbooru and offers the other X and Pixiv accounts in it " +
                "(links marked inactive with a \"-\" are ignored). Added accounts become sources in the Links section. " +
                "Fetching the artist posts above also does this.",
            style = MaterialTheme.typography.bodySmall
        )
        val pending = vm.otherPlatforms.count { !vm.isAdded(it) }
        ButtonRow {
            OutlinedButton(onClick = { vm.findOtherPlatforms() }, enabled = !vm.otherBusy && !vm.artistBusy) {
                Text("Find other platforms")
            }
            Button(onClick = { vm.addAllCandidates() }, enabled = pending > 0) { Text("Add all ($pending)") }
        }
        BusyBar(vm.otherBusy)
        StatusText(vm.otherStatus)
        for (c in vm.otherPlatforms) {
            val added = vm.isAdded(c)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        c.url,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (added) {
                    Text("Added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = { vm.addCandidate(c) }) { Text("Add") }
                }
            }
        }
    }
}

@Composable
private fun AssetsCard(vm: MainViewModel, onExport: () -> Unit, onImport: () -> Unit) {
    SectionCard("4 · Media asset check") {
        val s = vm.assetStats
        Text(
            "Stored: ${s.links} links from ${s.uploads} uploads (X ${s.twitter} · Pixiv ${s.pixiv} · other ${s.other})",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Last fetch: ${formatTime(s.lastFetchAt)} · next fetch stops at upload #${s.lastSeenId}",
            style = MaterialTheme.typography.bodySmall
        )
        ButtonRow {
            Button(onClick = { vm.fetchAssets() }, enabled = !vm.assetBusy) { Text("Fetch new") }
            if (vm.assetBusy) OutlinedButton(onClick = { vm.stopAssets() }) { Text("Stop") }
            OutlinedButton(onClick = { vm.checkAssets() }, enabled = vm.totalLinks > 0) { Text("Check") }
            OutlinedButton(
                onClick = { vm.purgeAssets() },
                enabled = vm.assetChecked && vm.totalAssetMatches > 0
            ) { Text("Purge (${vm.totalAssetMatches})") }
        }
        ButtonRow {
            OutlinedButton(onClick = onExport, enabled = s.links > 0) { Text("Export database") }
            OutlinedButton(onClick = onImport) { Text("Import database") }
        }
        BusyBar(vm.assetBusy)
        StatusText(vm.assetStatus)
        if (vm.assetChecked && vm.totalAssetMatches > 0) {
            val allMatches = remember(vm.sources) { vm.sources.flatMap { it.assetMatches } }
            var showMatches by remember { mutableStateOf(false) }
            TextButton(onClick = { showMatches = !showMatches }) {
                Text(if (showMatches) "Hide matches" else "Show matches")
            }
            if (showMatches) {
                for (m in allMatches.take(50)) {
                    Text(
                        m.url,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (allMatches.size > 50) {
                    Text("…and ${allMatches.size - 50} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LinksCard(vm: MainViewModel, onCopy: () -> Unit, onSave: () -> Unit) {
    SectionCard("5 · Links") {
        val many = vm.sources.size > 1
        if (vm.sources.isNotEmpty()) {
            Text(if (many) "Sources (tap to switch)" else "Source", style = MaterialTheme.typography.labelLarge)
            ButtonRow {
                for ((i, s) in vm.sources.withIndex()) {
                    FilterChip(
                        selected = s.id == vm.activeId,
                        onClick = { vm.selectSource(s.id) },
                        label = { Text("${i + 1} · ${s.title} (${s.items.size})" + if (s.busy) " …" else "") }
                    )
                }
            }
            val st = vm.activeSource?.status.orEmpty()
            if (st.isNotBlank()) StatusText(st)
        }
        val removed = vm.removedByDanbooru + vm.removedByAssets + vm.removedManually
        Text("${vm.items.size} links", style = MaterialTheme.typography.titleLarge)
        if (many) StatusText("${vm.totalLinks} links in all ${vm.sources.size} sources")
        if (vm.originalCount > 0) {
            Text(
                "Scanned ${vm.originalCount} · removed $removed " +
                    "(${vm.removedByDanbooru} already on Danbooru, ${vm.removedByAssets} in your media assets, " +
                    "${vm.removedManually} by hand)",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (many) {
            Text("Copy / save", style = MaterialTheme.typography.labelLarge)
            ButtonRow {
                FilterChip(
                    selected = !vm.linksScopeAll,
                    onClick = { vm.chooseScope(false) },
                    label = { Text("This source (${vm.items.size})") }
                )
                FilterChip(
                    selected = vm.linksScopeAll,
                    onClick = { vm.chooseScope(true) },
                    label = { Text("All sources (${vm.totalLinks})") }
                )
            }
        }
        ButtonRow {
            Button(onClick = onCopy, enabled = vm.linksInScope() > 0) { Text("Copy ${vm.linksInScope()}") }
            OutlinedButton(onClick = onSave, enabled = vm.linksInScope() > 0) { Text("Save .txt") }
            if (many) {
                TextButton(onClick = { vm.removeSource(vm.activeId) }, enabled = !vm.scanBusy) { Text("Remove this source") }
            }
        }
        ButtonRow {
            FilterChip(selected = !vm.gridView, onClick = { vm.chooseGrid(false) }, label = { Text("List") })
            FilterChip(selected = vm.gridView, onClick = { vm.chooseGrid(true) }, label = { Text("Grid") })
            if (vm.gridView) {
                FilterChip(
                    selected = vm.lowData,
                    onClick = { vm.chooseLowData(!vm.lowData) },
                    label = { Text("Small thumbnails") }
                )
            }
        }
        if (vm.items.isNotEmpty()) {
            StatusText("Tap a link for preview, open or delete. Long-press to select several.")
        }
        if (vm.linksNote.isNotBlank() || vm.canUndo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { StatusText(vm.linksNote) }
                if (vm.canUndo) TextButton(onClick = { vm.undoDelete() }) { Text("Undo") }
            }
        }
    }
}

// ---------------------------------------------------------------------- similar images

private fun dims(img: SimImage): String =
    if (img.width > 0 && img.height > 0) "${img.width}×${img.height}" else "size unknown"

/** The controls of the Similar images tab: what a run costs, match level, run, and quick marking / deleting. */
@Composable
private fun SimilarCard(vm: MainViewModel, shown: Int) {
    SectionCard("Similar images") {
        Text(
            "Compares every picture of every source, on one site and across sites, and lists the pairs that look alike " +
                "side by side. The picture with more pixels is always on the left (Pixiv first when the sizes are equal). " +
                "Mark the ones to delete; deleting removes the whole post from the Links.",
            style = MaterialTheme.typography.bodySmall
        )
        val plan = remember(vm.sources, vm.simBusy) { vm.simPlan() }
        if (vm.totalLinks == 0) {
            StatusText("Scan an artist first.")
        } else {
            val mb = (plan.toDownload.toLong() * SimilarityScan.BYTES_PER_PICTURE / (1024 * 1024)).toInt()
            StatusText(
                "${plan.pictures} pictures found in the links. ${plan.toDownload} still have to be downloaded " +
                    "(about $mb MB, small copies of ~60 KB each; pictures seen before are remembered)." +
                    if (plan.needDetails > 0) " ${plan.needDetails} Pixiv works need their details first (fetched automatically)." else ""
            )
        }
        Text("Match level", style = MaterialTheme.typography.labelLarge)
        ButtonRow {
            for (t in listOf(80, 85, 90, 95)) {
                FilterChip(
                    selected = vm.simThreshold == t,
                    onClick = { vm.chooseSimThreshold(t) },
                    label = { Text("$t%") }
                )
            }
        }
        StatusText("A higher level lists fewer, surer pairs. It applies at the next run (nothing is downloaded twice).")
        ButtonRow {
            Button(onClick = { vm.runSimilarity() }, enabled = !vm.simBusy && vm.totalLinks > 0) {
                Text(if (vm.simRan) "Run again" else "Find similar images")
            }
            if (vm.simBusy) OutlinedButton(onClick = { vm.stopSimilar() }) { Text("Stop") }
        }
        BusyBar(vm.simBusy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) { StatusText(vm.simStatus) }
            if (vm.canUndo) TextButton(onClick = { vm.undoDelete() }) { Text("Undo") }
        }
        if (vm.simRan) {
            HorizontalDivider()
            Text("$shown pairs shown", style = MaterialTheme.typography.titleSmall)
            if (shown > 0) {
                StatusText("Tap a picture to mark it for deletion (tap again to unmark).")
                ButtonRow {
                    OutlinedButton(onClick = { vm.simMarkSide(true) }) { Text("Mark all left") }
                    OutlinedButton(onClick = { vm.simMarkSide(false) }) { Text("Mark all right") }
                    OutlinedButton(onClick = { vm.simClearMarks() }, enabled = vm.simMarked.isNotEmpty()) { Text("Clear marks") }
                }
                val posts = vm.simMarkedPosts()
                OutlinedButton(
                    onClick = { vm.requestDeleteMarked() },
                    enabled = vm.simMarked.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete marked ($posts posts)") }
            }
        }
    }
}

/** One pair, side by side: left is the higher resolution. Tapping a picture marks it for deletion. */
@Composable
private fun PairRow(vm: MainViewModel, pair: SimPair) {
    val handler = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PairSide(vm, pair.left, "Left", Modifier.weight(1f), handler)
                PairSide(vm, pair.right, "Right", Modifier.weight(1f), handler)
            }
            Text(
                "Left is ${dims(pair.left)}, right is ${dims(pair.right)} · ${pair.score}% match",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PairSide(vm: MainViewModel, img: SimImage, label: String, modifier: Modifier, handler: UriHandler) {
    val marked = img.id in vm.simMarked
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(onClick = { vm.simToggle(img.id) })
        ) {
            RemoteImage(
                url = img.url,
                referer = if (img.platform == Platform.PIXIV) Previews.PIXIV_REFERER else null,
                targetPx = 400,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (marked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("DELETE", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text("$label · ${dims(img)}", style = MaterialTheme.typography.labelLarge)
        Text(
            vm.sourceTitle(img.sourceId),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val url = vm.postUrl(img)
        if (url != null) TextButton(onClick = { openLink(handler, url, vm) }) { Text("Open post") }
    }
}

// ---------------------------------------------------------------------- dialogs

@Composable
private fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    var base by remember { mutableStateOf(s.baseUrl) }
    var delayText by remember { mutableStateOf(s.delayMs.toString()) }
    var host by remember { mutableStateOf(s.twitterHost) }
    var includeDeleted by remember { mutableStateOf(s.includeDeleted) }
    var onlyUnposted by remember { mutableStateOf(s.onlyUnposted) }
    var newestFirst by remember { mutableStateOf(s.newestFirst) }
    var userQid by remember { mutableStateOf(s.twUserQid) }
    var mediaQid by remember { mutableStateOf(s.twMediaQid) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("Danbooru address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = delayText,
                    onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                    label = { Text("Pause between requests (ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchRow("Include deleted posts when checking the artist", includeDeleted) { includeDeleted = it }
                SwitchRow("Media assets: only uploads that were never posted", onlyUnposted) { onlyUnposted = it }
                SwitchRow("Newest links first", newestFirst) { newestFirst = it }
                Text("Twitter link style", style = MaterialTheme.typography.bodyMedium)
                ButtonRow {
                    FilterChip(selected = host == "x.com", onClick = { host = "x.com" }, label = { Text("x.com") })
                    FilterChip(
                        selected = host == "twitter.com",
                        onClick = { host = "twitter.com" },
                        label = { Text("twitter.com") }
                    )
                }
                HorizontalDivider()
                Text("X query IDs (advanced)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Leave both empty: the app finds X's current IDs by itself. Only fill them in if X scans keep failing " +
                        "with a 404 and you know the current IDs.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = userQid,
                    onValueChange = { userQid = it },
                    label = { Text("UserByScreenName ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mediaQid,
                    onValueChange = { mediaQid = it },
                    label = { Text("UserMedia ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(
                    base = base.trim(),
                    delayMs = delayText.toLongOrNull() ?: 300L,
                    twitterHost = host,
                    includeDeleted = includeDeleted,
                    onlyUnposted = onlyUnposted,
                    newestFirst = newestFirst,
                    userQueryId = userQid.trim(),
                    mediaQueryId = mediaQid.trim()
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatsDialog(
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onResetMarker: () -> Unit,
    onClearAssets: () -> Unit
) {
    val s = vm.assetStats
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stats") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Current artist check", style = MaterialTheme.typography.titleSmall)
                if (vm.sources.size > 1) {
                    Text("${vm.sources.size} sources, ${vm.totalLinks} links in all. Shown source: ${vm.activeSource?.title ?: "–"}")
                }
                Text("Scanned links: ${vm.originalCount}")
                Text("Removed (already on Danbooru): ${vm.removedByDanbooru}")
                Text("Removed (in your media assets): ${vm.removedByAssets}")
                Text("Removed (by hand): ${vm.removedManually}")
                Text("Remaining: ${vm.items.size}")
                Text("Artist tag: ${vm.artistTag.ifBlank { "–" }} · ${vm.danbooruPosts} posts · ${vm.danbooruKeys.size} source keys")
                Text("Stored media assets", style = MaterialTheme.typography.titleSmall)
                Text("${s.links} links from ${s.uploads} uploads")
                Text("X ${s.twitter} · Pixiv ${s.pixiv} · other ${s.other}")
                Text("Last fetch: ${formatTime(s.lastFetchAt)} · fetch point: upload #${s.lastSeenId}")
                ButtonRow {
                    OutlinedButton(onClick = onResetMarker) { Text("Reset fetch point") }
                    OutlinedButton(onClick = onClearAssets) { Text("Delete stored links") }
                }
                Text("Activity log", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (vm.log.isEmpty()) "(nothing yet)" else vm.log.takeLast(60).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun PasteCookiesDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste cookies.txt") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Netscape cookies.txt contents") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Use these cookies") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmDialog(text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Are you sure?") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
