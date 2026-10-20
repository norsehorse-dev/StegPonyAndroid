// Ported from PassPony Android (same author, Apache-2.0).
package com.stegpony.android.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * CameraX analyzer that hands each frame's luminance plane to zxing (pure
 * Java, no Play services) and reports the first QR code it decodes. Frames
 * stay in memory and are never written anywhere. After a hit it pauses until
 * [resume], so one code isn't reported on every following frame.
 */
class QrAnalyzer(
    private val mainExecutor: Executor,
    private val onText: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private val active = AtomicBoolean(true)
    private var buffer = ByteArray(0)

    fun resume() {
        active.set(true)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!active.get()) return
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val needed = rowStride * image.height
            if (buffer.size < needed) buffer = ByteArray(needed)
            val source = plane.buffer
            source.rewind()
            source.get(buffer, 0, min(needed, source.remaining()))
            val luminance = PlanarYUVLuminanceSource(
                buffer, rowStride, image.height, 0, 0, image.width, image.height, false,
            )
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text
            } catch (e: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (text != null && active.compareAndSet(true, false)) {
                mainExecutor.execute { onText(text) }
            }
        } finally {
            image.close()
        }
    }
}
