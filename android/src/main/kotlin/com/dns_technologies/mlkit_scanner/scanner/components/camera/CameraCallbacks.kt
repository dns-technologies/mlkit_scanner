package com.dns_technologies.mlkit_scanner.scanner.components.camera

import com.dns_technologies.mlkit_scanner.scanner.components.analyzer.models.NV21AnalysingImage

typealias OnInit = () -> Unit
typealias OnError = (e: Exception) -> Unit
typealias OnCameraFrame = (image: NV21AnalysingImage) -> Unit
