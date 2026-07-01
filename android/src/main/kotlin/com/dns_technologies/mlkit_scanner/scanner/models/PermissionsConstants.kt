package com.dns_technologies.mlkit_scanner.scanner.models

import android.Manifest

class PermissionsConstants {
    companion object {
        const val REQUEST_CODE_PERMISSIONS = 10
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
