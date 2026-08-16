package ai.orynode.mobile.infrastructure.ocr

import ai.orynode.mobile.domain.OcrDocument
import ai.orynode.mobile.domain.OcrNormalizedRect
import ai.orynode.mobile.domain.OcrObservation
import ai.orynode.mobile.domain.TextRecognizer
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-device ML Kit OCR (Chinese + Latin). Used for sparse/empty PDF text layers.
 * Camera ingest product entry remains disabled — same port as iOS Vision OCR.
 */
class MlKitTextRecognizer : TextRecognizer {
    private val client by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognizeDocument(imagePath: Path): OcrDocument =
        recognizeImageData(Files.readAllBytes(imagePath))

    override suspend fun recognizeImageData(data: ByteArray): OcrDocument = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: return@withContext OcrDocument.fromObservations(emptyList())
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(client.process(image))
            val width = bitmap.width.toDouble().coerceAtLeast(1.0)
            val height = bitmap.height.toDouble().coerceAtLeast(1.0)
            val observations = result.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .mapNotNull { element ->
                    val box = element.boundingBox ?: return@mapNotNull null
                    val text = element.text.trim()
                    if (text.isEmpty()) return@mapNotNull null
                    val left = box.left / width
                    val top = box.top / height
                    val w = box.width() / width
                    val h = box.height() / height
                    // Convert ML Kit top-left origin to Vision-style bottom-left for line clustering.
                    OcrObservation(
                        text = text,
                        boundingBox = OcrNormalizedRect(
                            x = left,
                            y = 1.0 - (top + h),
                            width = w,
                            height = h,
                        ),
                    )
                }
            OcrDocument.fromObservations(observations)
        } finally {
            bitmap.recycle()
        }
    }
}
