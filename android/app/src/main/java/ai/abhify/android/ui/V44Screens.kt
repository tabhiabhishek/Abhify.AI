package ai.abhify.android.ui

import ai.abhify.android.chat.SavedPrompt
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * v4.4 PDF page viewer: the pages as images (Android's PdfRenderer, already used for OCR; no new library), rendered
 * lazily one at a time, zoom 1× / 1.5× / 2× (sideways scrolling when zoomed). Opens a PDF the user picks; the library
 * itself keeps only extracted text, so nothing is stored by viewing.
 */
@Composable
fun PdfPagesDialog(uri: Uri, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val lock = remember { Mutex() }
    DisposableEffect(uri) {
        var pfd: ParcelFileDescriptor? = null
        try { pfd = ctx.contentResolver.openFileDescriptor(uri, "r"); renderer = PdfRenderer(pfd!!) }
        catch (e: SecurityException) { error = "This PDF is password-protected or can't be opened." }
        catch (e: Exception) { error = "Couldn't open this PDF: ${e.message}" }
        onDispose { runCatching { renderer?.close() }; runCatching { pfd?.close() } }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                ScreenTitle(title, onClose) {
                    listOf(1f to "1×", 1.5f to "1.5×", 2f to "2×").forEach { (z, l) -> FilterChip(zoom == z, { zoom = z }, { Text(l) }); Spacer(Modifier.width(4.dp)) }
                }
                error?.let { EmptyState("📄", it); return@Column }
                val r = renderer ?: run { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val baseWidthPx = with(density) { (maxWidth - 16.dp).toPx() }
                    val widthPx = (baseWidthPx * zoom).toInt().coerceAtMost(2200)     // memory cap: a few MB per page on screen
                    val pageWidthDp = with(density) { widthPx.toDp() }
                    LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(r.pageCount, key = { it }) { i ->
                            val bmp by produceState<Bitmap?>(null, i, widthPx) {
                                value = withContext(Dispatchers.IO) { runCatching { lock.withLock {
                                    r.openPage(i).use { p ->
                                        val hPx = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                        Bitmap.createBitmap(widthPx, hPx, Bitmap.Config.ARGB_8888).also { b ->
                                            b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                    } } }.getOrNull() }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val b = bmp
                                if (b == null) Box(Modifier.width(pageWidthDp).height(pageWidthDp * 1.3f).background(Color.White), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp)) }
                                else Image(b.asImageBitmap(), "Page ${i + 1} of ${r.pageCount}", Modifier.width(pageWidthDp), contentScale = ContentScale.FillWidth)
                                Text("${i + 1} / ${r.pageCount}", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** v4.4 prompt library: saved prompts to reuse in chat (on this phone; included in "Notes and tasks" backups). */
@Composable
fun PromptLibraryDialog(vm: AppViewModel, onClose: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPrompts() }
    var editing by remember { mutableStateOf<SavedPrompt?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedPrompt?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Saved prompts") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
            if (vm.prompts.isEmpty()) EmptyState("📋", "No saved prompts yet", "Tap ＋ New to save one, for example \"Explain like I'm new to ML and give one code example\".")
            vm.prompts.forEach { p -> Glass(padding = 12, onClick = { vm.usePrompt(p); onClose() }) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.body, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row { TextButton({ editing = p }) { Text("Edit") }; TextButton({ deleting = p }) { Text("Delete", color = LocalExtra.current.bad) } }
            } }
            DialogText("Tap a prompt to put it in the message box (you can change it before sending).", MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ creating = true }) { Text("＋ New") } }, dismissButton = { TextButton(onClose) { Text("Close") } })
    if (creating || editing != null) {
        val p = editing
        var title by remember(p) { mutableStateOf(p?.title ?: "") }
        var body by remember(p) { mutableStateOf(p?.body ?: vm.draft.value) }
        AlertDialog(onDismissRequest = { creating = false; editing = null }, title = { Text(if (p == null) "New prompt" else "Edit prompt") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
                OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                OutlinedTextField(body, { body = it.take(4000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, label = { Text("Prompt") })
            } },
            confirmButton = { TextButton({ vm.savePrompt(p?.id, title, body); creating = false; editing = null }, enabled = title.isNotBlank() && body.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ creating = false; editing = null }) { Text("Cancel") } })
    }
    deleting?.let { d -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"${d.title}\"?") },
        text = { DialogText("The saved prompt is removed from this phone. Chats are not affected.") },
        confirmButton = { TextButton({ vm.deletePrompt(d); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}
