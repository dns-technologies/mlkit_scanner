package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraFrame
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Rect
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode as MlkitBarcode
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class MlkitImageBarcodeAnalyzerTest {
    @Test
    fun `nv21 roi is created with mlkit format and processed`() {
        val scanner = barcodeScanner()
        val inputImage = mock(InputImage::class.java)
        val bytes = ByteArray(24)
        var receivedBytes: ByteArray? = null
        var receivedMetadata: List<Int>? = null
        val analyzer = analyzer(
            scanner = scanner,
            fromByteArray = { data, width, height, rotation, format ->
                receivedBytes = data
                receivedMetadata = listOf(width, height, rotation, format)
                inputImage
            },
        )
        val cropRect = Rect(2, 0, 6, 4)
        val frame = FakeFrame(nv21Bytes = bytes)

        analyzer.analyze(frame, cropRect)

        assertSame(bytes, receivedBytes)
        assertEquals(
            listOf(cropRect.width, cropRect.height, ROTATION_DEGREES, InputImage.IMAGE_FORMAT_NV21),
            receivedMetadata,
        )
        assertEquals(cropRect, frame.receivedCropRect)
        verify(scanner).process(inputImage)
    }

    @Test
    fun `frame is not accessed while analysis delay is active`() {
        val scanner = barcodeScanner()
        val analyzer = analyzer(scanner)
        val firstFrame = FakeFrame()
        val delayedFrame = FakeFrame()

        analyzer.analyze(firstFrame, null)
        analyzer.analyze(delayedFrame, null)

        assertEquals(1, firstFrame.accessCalls)
        assertEquals(0, delayedFrame.accessCalls)
    }

    @Test
    fun `recognized mlkit barcode maps every scanner barcode field`() {
        val mlkitBarcode = mock(MlkitBarcode::class.java)
        doReturn(BARCODE_VALUE).`when`(mlkitBarcode).rawValue
        doReturn(DISPLAY_VALUE).`when`(mlkitBarcode).displayValue
        doReturn(MlkitBarcode.FORMAT_QR_CODE).`when`(mlkitBarcode).format
        doReturn(MlkitBarcode.TYPE_URL).`when`(mlkitBarcode).valueType
        val errors = mutableListOf<String>()
        val analyzer = analyzer(barcodeScanner(listOf(mlkitBarcode)), logError = errors::add)

        val result = analyzer.analyze(FakeFrame(), null)

        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(
            mapOf(
                "raw_value" to BARCODE_VALUE,
                "display_value" to DISPLAY_VALUE,
                "format" to MlkitBarcode.FORMAT_QR_CODE,
                "value_type" to MlkitBarcode.TYPE_URL,
            ),
            result?.toMap(),
        )
    }

    @Test
    fun `unknown mlkit format maps to dart unknown format code`() {
        val mlkitBarcode = mock(MlkitBarcode::class.java)
        doReturn(BARCODE_VALUE).`when`(mlkitBarcode).rawValue
        doReturn(MlkitBarcode.FORMAT_UNKNOWN).`when`(mlkitBarcode).format
        doReturn(MlkitBarcode.TYPE_UNKNOWN).`when`(mlkitBarcode).valueType
        val analyzer = analyzer(barcodeScanner(listOf(mlkitBarcode)))

        val result = analyzer.analyze(FakeFrame(), null)

        assertEquals(UNKNOWN_FORMAT_CODE, result?.toMap()?.get("format"))
        assertEquals(null, result?.toMap()?.get("display_value"))
    }

    @Test
    fun `dispose closes barcode scanner once`() {
        val scanner = barcodeScanner()
        val analyzer = analyzer(scanner)

        analyzer.dispose()
        analyzer.dispose()

        verify(scanner).close()
    }

    private fun barcodeScanner(barcodes: List<MlkitBarcode> = emptyList()): BarcodeScanner {
        val scanner = mock(BarcodeScanner::class.java)
        doReturn(Tasks.forResult(barcodes)).`when`(scanner).process(anyValue<InputImage>())
        return scanner
    }

    private fun analyzer(
        scanner: BarcodeScanner,
        logError: (String) -> Unit = {},
        awaitBarcodes: (Task<List<MlkitBarcode>>) -> List<MlkitBarcode> = { it.result },
        fromByteArray: (ByteArray, Int, Int, Int, Int) -> InputImage =
            { _, _, _, _, _ -> mock(InputImage::class.java) },
    ): MlkitImageBarcodeAnalyzer = MlkitImageBarcodeAnalyzer(
        barcodeScanner = scanner,
        currentTimeMs = { 0L },
        logError = logError,
        logDebug = {},
        awaitBarcodes = awaitBarcodes,
        fromByteArray = fromByteArray,
    )

    private class FakeFrame(
        private val nv21Bytes: ByteArray = ByteArray(24),
    ) : CameraFrame {
        override val width = FRAME_WIDTH
        override val height = FRAME_HEIGHT
        override val rotationDegree = ROTATION_DEGREES
        var accessCalls = 0
            private set
        var receivedCropRect: Rect? = null
            private set

        override fun <T> useNv21(
            cropRect: Rect?,
            block: (ByteArray, Int, Int, Int) -> T,
        ): T {
            accessCalls += 1
            receivedCropRect = cropRect
            val outputWidth = cropRect?.width ?: width
            val outputHeight = cropRect?.height ?: height
            return block(nv21Bytes, outputWidth, outputHeight, rotationDegree)
        }

        override fun close() = Unit
    }

    private companion object {
        const val FRAME_WIDTH = 8
        const val FRAME_HEIGHT = 6
        const val ROTATION_DEGREES = 90
        const val BARCODE_VALUE = "barcode-value"
        const val DISPLAY_VALUE = "Barcode value"
        const val UNKNOWN_FORMAT_CODE = 0

        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            any<T>()
            return null as T
        }
    }
}
