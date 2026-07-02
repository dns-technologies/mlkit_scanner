package com.dns_technologies.mlkit_scanner.scanner.models

import android.Manifest

/** Contains Android permission constants required by the scanner plugin. */
class PermissionsConstants {
    companion object {
        /** Request code used when asking Android for scanner permissions. */
        const val REQUEST_CODE_PERMISSIONS = 10

        /** Android permissions required before camera initialization. */
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
