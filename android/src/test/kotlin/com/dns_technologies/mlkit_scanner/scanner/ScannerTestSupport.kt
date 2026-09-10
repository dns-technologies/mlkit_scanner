package com.dns_technologies.mlkit_scanner.scanner

import android.os.Handler
import androidx.lifecycle.Lifecycle
import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.ImageBarcodeAnalyzer
import com.dns_technologies.mlkit_scanner.scanner.components.camera.Camera
import com.dns_technologies.mlkit_scanner.scanner.components.camera.CameraConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

/** Real scanner with a ready preview and synchronous main dispatcher for frame/lifetime tests. */
internal fun scannerForTest(
    camera: Camera,
    analyzer: ImageBarcodeAnalyzer,
    view: ScannerView = mock(ScannerView::class.java),
): Scanner {
    doReturn(42).`when`(view).viewId
    doReturn(true).`when`(view).isPreviewReady()
    return Scanner(camera, analyzer, mock(Handler::class.java), { _, _ -> },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        connection = CameraConnection()).apply {
        select(view)
        attachActivity(mock(Lifecycle::class.java))
    }
}

internal fun Scanner.captureForTest() = runBlocking {
    capture(ScannerConfiguration()) { true }
}
