package com.dns_technologies.mlkit_scanner_example

import com.otaliastudios.cameraview.CameraLogger
import io.flutter.embedding.android.FlutterActivity

class MainActivity: FlutterActivity() {

    override fun onStart() {
        super.onStart()
        CameraLogger.setLogLevel(CameraLogger.LEVEL_INFO)
    }
}
