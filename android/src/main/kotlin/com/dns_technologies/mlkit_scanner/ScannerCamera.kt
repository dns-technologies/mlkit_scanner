package com.dns_technologies.mlkit_scanner

import com.dns_technologies.mlkit_scanner.analyzer.CameraImageAnalyzer

typealias OnInit = () -> Unit
typealias OnError = (e: Exception) -> Unit

interface ScannerCamera {
    fun startCamera(onInit: OnInit, onError: OnError)
    fun isActive(): Boolean
    fun toggleFlashLight()
    fun changeFocusCenter(widthOffset: Float, heightOffset: Float)
    fun attachAnalyser(analyzer: CameraImageAnalyzer)
    fun clearAnalyzer()
    fun setZoom(value: Float)
}