package ai.orynode.mobile.app.ui.preview

import ai.orynode.mobile.app.serving.DocumentPreviewIntent
import ai.orynode.mobile.app.ui.components.DocumentTypeBadge
import ai.orynode.mobile.app.ui.theme.OrynodeColors
import ai.orynode.mobile.app.ui.theme.PaperBackground
import ai.orynode.mobile.domain.SourceLocator
import ai.orynode.mobile.domain.Utf16TextIndex
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** PDFKit-like selection yellow wash. */
private val PdfSelectionFlash = Color(0xFFFFE14D)

/**
 * Citation preview — mirrors iOS `DocumentPreviewShell`:
 * - PDF → land on page + whole-page yellow flash, then fade
 * - TXT / MD / OOXML → same yellow selection wash
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPreviewScreen(
    intent: DocumentPreviewIntent,
    onBack: () -> Unit,
) {
    val ext = intent.fileName.substringAfterLast('.', "").lowercase()
    val isPdf = ext == "pdf"
    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(intent.title, color = OrynodeColors.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OrynodeColors.ink)
                        }
                    },
                    actions = {
                        Box(modifier = Modifier.padding(end = 12.dp)) {
                            DocumentTypeBadge(intent.fileName)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                intent.subtitle?.let { subtitle ->
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OrynodeColors.inkSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(OrynodeColors.paper.copy(alpha = 0.95f))
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (isPdf) {
                    PdfPreviewPages(
                        path = intent.filePath,
                        locator = intent.locator,
                        excerpt = intent.excerpt,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // OOXML / MD / TXT: always prefer indexedText (parity with iOS).
                    val text = intent.indexedText?.takeIf { it.isNotEmpty() }
                        ?: when (ext) {
                            "txt", "text", "md", "markdown" ->
                                runCatching {
                                    String(Files.readAllBytes(Paths.get(intent.filePath)), Charsets.UTF_8)
                                }.getOrDefault("")
                            else -> intent.indexedText.orEmpty()
                        }
                    TextPreviewLines(
                        text = text,
                        locator = intent.locator,
                        excerpt = intent.excerpt,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextPreviewLines(
    text: String,
    locator: SourceLocator?,
    excerpt: String?,
    modifier: Modifier = Modifier,
) {
    val lines = remember(text) {
        text.split("\n").mapIndexed { index, line ->
            LineItem(index + 1, if (line.isEmpty()) " " else line)
        }
    }
    val highlight = remember(text, locator, excerpt) {
        highlightedLineRange(text, locator, excerpt)
    }
    val scrollTarget = remember(text, highlight, excerpt) {
        scrollTargetLine(text, highlight, excerpt)
    }
    val listState = remember(lines.size, scrollTarget) {
        val target = scrollTarget
        if (target == null || lines.isEmpty()) {
            LazyListState()
        } else {
            LazyListState(
                firstVisibleItemIndex = (target - 1).coerceIn(0, lines.lastIndex),
            )
        }
    }
    val lineNumberWidth = remember(lines.size) {
        (maxOf(2, lines.size.toString().length) * 10 + 8).dp
    }

    LazyColumn(state = listState, modifier = modifier.padding(vertical = 16.dp)) {
        items(lines, key = { it.lineNumber }) { item ->
            val highlighted = highlight?.contains(item.lineNumber) == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        // Same yellow wash as PDF selection flash (PDFKit-like).
                        if (highlighted) PdfSelectionFlash.copy(alpha = 0.55f)
                        else Color.Transparent,
                    )
                    .padding(horizontal = 16.dp, vertical = 1.dp),
            ) {
                Text(
                    "${item.lineNumber}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (highlighted) Color(0xFFB8860B) else OrynodeColors.inkFaint,
                    modifier = Modifier.width(lineNumberWidth).padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    item.text,
                    fontSize = 16.sp,
                    color = OrynodeColors.ink,
                    modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PdfPreviewPages(
    path: String,
    locator: SourceLocator?,
    excerpt: String?,
    modifier: Modifier = Modifier,
) {
    var pageCount by remember(path) { mutableStateOf(0) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var source by remember(path) { mutableStateOf<PdfPageSource?>(null) }
    val pdfLocator = locator as? SourceLocator.Pdf
    val resolvedTarget = remember(pdfLocator) {
        pdfLocator?.page?.coerceAtLeast(1) ?: 1
    }

    DisposableEffect(path) {
        onDispose {
            source?.close()
            source = null
        }
    }

    LaunchedEffect(path) {
        loading = true
        error = null
        source?.close()
        source = null
        val opened = withContext(Dispatchers.IO) {
            runCatching { PdfPageSource(path) }
        }
        opened.onSuccess {
            source = it
            pageCount = it.pageCount
            loading = false
        }.onFailure {
            error = it.message ?: "无法打开 PDF"
            pageCount = 0
            loading = false
        }
    }

    val listState = remember(pageCount, resolvedTarget) {
        if (pageCount <= 0) {
            LazyListState()
        } else {
            LazyListState(
                firstVisibleItemIndex = (resolvedTarget - 1).coerceIn(0, pageCount - 1),
            )
        }
    }

    when {
        loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OrynodeColors.accent)
        }
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error.orEmpty(), color = OrynodeColors.caution)
        }
        source != null && pageCount > 0 -> {
            val pdf = source!!
            LazyColumn(state = listState, modifier = modifier.padding(12.dp)) {
                items(pageCount) { index ->
                    val pageNumber = index + 1
                    val isCitationPage = pageNumber == resolvedTarget &&
                        (pdfLocator != null || !excerpt.isNullOrBlank())
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(
                            "第 $pageNumber 页",
                            fontSize = 12.sp,
                            color = if (isCitationPage) OrynodeColors.accent else OrynodeColors.inkFaint,
                            fontWeight = if (isCitationPage) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        PdfPageBitmap(
                            source = pdf,
                            pageIndex = index,
                            flashPage = isCitationPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageBitmap(
    source: PdfPageSource,
    pageIndex: Int,
    flashPage: Boolean,
) {
    var bitmap by remember(source, pageIndex) { mutableStateOf<Bitmap?>(null) }
    val flashAlpha = remember(source, pageIndex, flashPage) { Animatable(0f) }

    LaunchedEffect(source, pageIndex) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { source.renderPage(pageIndex) }.getOrNull()
        }
    }

    // Whole-page yellow attention flash (PDFKit animate), then fade — no fragile text-box geometry.
    LaunchedEffect(bitmap, flashPage) {
        if (bitmap == null || !flashPage) {
            flashAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        flashAlpha.snapTo(0.42f)
        delay(750)
        flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 1100))
    }

    DisposableEffect(source, pageIndex) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    val current = bitmap
    if (current == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(Color.White.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = OrynodeColors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "PDF 第 ${pageIndex + 1} 页",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.8f)),
            )
            val alpha = flashAlpha.value
            if (alpha > 0.02f) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(color = PdfSelectionFlash.copy(alpha = alpha))
                }
            }
        }
    }
}

/** Thread-safe on-demand PdfRenderer. */
private class PdfPageSource(path: String) : AutoCloseable {
    val file: File = File(path).also { require(it.isFile) { "找不到 PDF 文件" } }
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

    suspend fun renderPage(index: Int, scale: Float = 2f): Bitmap = mutex.withLock {
        renderer.openPage(index).use { page ->
            val width = maxOf(1, (page.width * scale).toInt())
            val height = maxOf(1, (page.height * scale).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

private data class LineItem(val lineNumber: Int, val text: String)

/**
 * Line range highlight — mirrors iOS `TextDocumentPreviewView.highlightedLineRange`
 * for Markdown / PlainText / excerpt fallback (TXT · MD · OOXML).
 */
private fun highlightedLineRange(
    text: String,
    locator: SourceLocator?,
    excerpt: String?,
): IntRange? {
    return when (locator) {
        is SourceLocator.Markdown -> {
            val start = locator.startLine.coerceAtLeast(1)
            val end = maxOf(start, locator.endLine)
            start..end
        }
        is SourceLocator.PlainText -> {
            val start = Utf16TextIndex.lineNumber(locator.startOffset, text)
            val end = Utf16TextIndex.lineNumber(
                maxOf(locator.startOffset, locator.endOffset - 1),
                text,
            )
            start..maxOf(start, end)
        }
        is SourceLocator.Pdf, null -> findExcerptLineRange(text, excerpt)
    }
}

private fun findExcerptLineRange(text: String, excerpt: String?): IntRange? {
    val needle = excerpt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    var index = text.indexOf(needle, ignoreCase = true)
    var length = needle.length
    if (index < 0) {
        val head = needle.take(24)
        if (head.length < 4) return null
        index = text.indexOf(head, ignoreCase = true)
        length = head.length
        if (index < 0) return null
    }
    val start = Utf16TextIndex.lineNumber(index, text)
    val end = Utf16TextIndex.lineNumber(index + length - 1, text)
    return start..maxOf(start, end)
}

private fun scrollTargetLine(text: String, highlight: IntRange?, excerpt: String?): Int? {
    if (highlight == null) return null
    if (highlight.first == highlight.last) return highlight.first
    val needle = excerpt?.trim().orEmpty()
    if (needle.length >= 4) {
        text.split("\n").forEachIndexed { index, line ->
            if (line.contains(needle, ignoreCase = true) && highlight.contains(index + 1)) {
                return index + 1
            }
        }
        val head = needle.take(24)
        text.split("\n").forEachIndexed { index, line ->
            if (line.contains(head) && highlight.contains(index + 1)) return index + 1
        }
    }
    return highlight.last
}
