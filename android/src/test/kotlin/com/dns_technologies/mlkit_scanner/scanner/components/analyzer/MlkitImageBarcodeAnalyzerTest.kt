package com.dns_technologies.mlkit_scanner.scanner.components.analyzer

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

internal class MlkitImageBarcodeAnalyzerTest {
    @Test
    fun `input image is processed`() {
        val scanner = barcodeScanner()
        val analyzer = analyzer(scanner)
        val inputImage = mock(InputImage::class.java)

        analyzer.analyze { inputImage }

        verify(scanner).process(inputImage)
    }

    @Test
    fun `dispose closes barcode scanner`() {
        val scanner = barcodeScanner()
        val analyzer = analyzer(scanner)

        analyzer.dispose()
        analyzer.dispose()

        verify(scanner).close()
    }

    private fun barcodeScanner(): BarcodeScanner {
        val scanner = mock(BarcodeScanner::class.java)
        doReturn(
            Tasks.forResult(emptyList<com.google.mlkit.vision.barcode.common.Barcode>()),
        ).`when`(scanner).process(anyValue<InputImage>())
        return scanner
    }

    private fun analyzer(scanner: BarcodeScanner): MlkitImageBarcodeAnalyzer {
        return MlkitImageBarcodeAnalyzer(
            logTag = LOG_TAG,
            barcodeScanner = scanner,
            currentTimeMs = { 0L },
            logError = {},
        )
    }

    private companion object {
        const val LOG_TAG = "MlkitImageBarcodeAnalyzerTest"

        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            any<T>()
            return null as T
        }
    }
}
