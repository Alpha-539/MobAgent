package com.mobagent.app.permission

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Google Play uchun: "Barcha fayllarga kirish" (MANAGE_EXTERNAL_STORAGE) ruxsati
 * ilovadan olib tashlangan. Agent va Linux muhiti faqat ilovaning shaxsiy papkasida
 * (filesDir) ishlaydi — shuning uchun bu yerda hech qanday ruxsat so'ralmaydi.
 */
fun hasStorageAccess(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

/** Agentning ishchi papkasi: ilovaning shaxsiy (sandbox) papkasi. */
fun effectiveWorkingDir(context: Context): java.io.File = context.filesDir

class StoragePermissionState internal constructor() {
    val isGranted: Boolean = false
    fun request() { /* ruxsat so'ralmaydi */ }
}

@Composable
fun rememberStoragePermissionState(): StoragePermissionState = StoragePermissionState()
