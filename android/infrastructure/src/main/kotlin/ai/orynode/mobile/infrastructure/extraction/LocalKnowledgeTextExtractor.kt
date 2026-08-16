package ai.orynode.mobile.infrastructure.extraction

import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.KnowledgeExtraction
import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.KnowledgeTextExtractor
import ai.orynode.mobile.domain.TextRecognizer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * TXT / Markdown / PDF / OOXML extractor. Sparse PDF pages fall back to [TextRecognizer]
 * (ML Kit), matching iOS `LocalKnowledgeTextExtractor` + Vision OCR.
 *
 * PDF text (PdfBox) and page raster OCR (PdfRenderer) run in separate passes so the
 * same file is never opened by both engines at once — dual-open has crashed on device.
 */
class LocalKnowledgeTextExtractor(
    private val textRecognizer: TextRecognizer? = null,
    context: Context? = null,
) : KnowledgeTextExtractor {
    init {
        if (context != null) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    override suspend fun extract(from: Path): KnowledgeExtraction {
        val name = from.fileName.toString().lowercase()
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            name.endsWith(".txt") || name.endsWith(".text") -> {
                val text = PdfOcrPolicy.normalize(readPlainText(from))
                if (text.trim().isEmpty()) throw KnowledgeBaseError.EmptyDocument
                KnowledgeExtraction(kind = KnowledgeExtraction.Kind.PlainText, indexedText = text)
            }
            name.endsWith(".md") || name.endsWith(".markdown") -> {
                val text = PdfOcrPolicy.normalize(readPlainText(from))
                if (text.trim().isEmpty()) throw KnowledgeBaseError.EmptyDocument
                KnowledgeExtraction(kind = KnowledgeExtraction.Kind.Markdown, indexedText = text)
            }
            name.endsWith(".pdf") -> readPdf(from)
            OfficeOpenXMLMarkdownExtractor.supportsExtension(ext) -> {
                val markdown = withContext(Dispatchers.IO) {
                    OfficeOpenXMLMarkdownExtractor.extract(from)
                }
                KnowledgeExtraction(
                    kind = KnowledgeExtraction.Kind.Markdown,
                    indexedText = PdfOcrPolicy.normalize(markdown),
                )
            }
            else -> throw KnowledgeBaseError.UnsupportedFileType(ext.ifEmpty { name })
        }
    }

    private suspend fun readPdf(from: Path): KnowledgeExtraction = withContext(Dispatchers.IO) {
        try {
            val nativePages = extractPdfNativePages(from)
            val pages = nativePages.mapIndexed { index, native ->
                var pageText = native
                if (textRecognizer != null &&
                    PdfOcrPolicy.shouldAttemptOcr(native) &&
                    hasHeadroomForOcr()
                ) {
                    val png = renderPagePng(from, index)
                    if (png != null) {
                        val ocr = PdfOcrPolicy.normalize(
                            runCatching { textRecognizer.recognizeImageData(png) }
                                .map { it.plainText }
                                .getOrDefault(""),
                        )
                        pageText = PdfOcrPolicy.selectPageText(native = native, ocr = ocr)
                    }
                }
                pageText
            }
            val spans = mutableListOf<KnowledgePageSpan>()
            var cursor = 0
            pages.forEachIndexed { index, pageText ->
                if (index > 0) cursor += 2
                val start = cursor
                val end = start + pageText.length
                spans += KnowledgePageSpan(page = index + 1, start = start, end = end)
                cursor = end
            }
            val indexed = pages.joinToString("\n\n")
            if (indexed.trim().isEmpty()) throw KnowledgeBaseError.EmptyDocument
            KnowledgeExtraction(
                kind = KnowledgeExtraction.Kind.Pdf,
                indexedText = indexed,
                pageSpans = spans,
            )
        } catch (error: OutOfMemoryError) {
            System.gc()
            throw KnowledgeBaseError.Storage("内存不足，无法解析这份 PDF。请先在设置中释放模型后再导入。")
        } catch (error: KnowledgeBaseError) {
            throw error
        } catch (error: Exception) {
            throw KnowledgeBaseError.Storage("无法打开 PDF：${error.message ?: "文件损坏或已加密"}")
        }
    }

    private fun extractPdfNativePages(from: Path): List<String> {
        val document = PDDocument.load(from.toFile())
        document.use { pdf ->
            val pageCount = pdf.numberOfPages
            return (0 until pageCount).map { index ->
                val stripper = PDFTextStripper().apply {
                    startPage = index + 1
                    endPage = index + 1
                    sortByPosition = true
                }
                PdfOcrPolicy.normalize(stripper.getText(pdf).orEmpty())
            }
        }
    }

    private fun renderPagePng(from: Path, pageIndex: Int, maxPixelWidth: Int = 1024): ByteArray? {
        val pfd = ParcelFileDescriptor.open(from.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    if (page.width <= 1 || page.height <= 1) return null
                    val scale = minOf(
                        maxPixelWidth.toFloat() / maxOf(page.width, page.height),
                        2f,
                    )
                    val width = maxOf(1, (page.width * scale).toInt())
                    val height = maxOf(1, (page.height * scale).toInt())
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().use { out ->
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                                return null
                            }
                            out.toByteArray()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun hasHeadroomForOcr(): Boolean {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val headroom = runtime.maxMemory() - used
        return headroom > 96L * 1024 * 1024
    }

    private fun readPlainText(from: Path): String {
        val bytes = Files.readAllBytes(from)
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16, Charset.forName("ISO-8859-1"))) {
            runCatching { return String(bytes, charset) }
        }
        throw KnowledgeBaseError.Storage("无法以已知编码读取文本。")
    }
}
