package com.mobagent.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Xizmat ikonkalari papkasi: `app/src/main/assets/provider_icons/<xizmat-id>.png` (README.txt'ga qarang). */
private const val ICON_DIR = "provider_icons"
private const val MAX_ICON_PX = 256

/** PNG ni assets'dan yuklaydi (katta rasm kichraytiriladi). Fayl yo'q yoki buzuq bo'lsa — null. */
private fun loadProviderIcon(context: Context, providerId: String): ImageBitmap? = runCatching {
    val path = "$ICON_DIR/$providerId.png"
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_ICON_PX && bounds.outHeight / (sample * 2) >= MAX_ICON_PX) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}.getOrNull()

/**
 * Onlayn xizmatning (model emas) ikonkasi — faqat chat sarlavhasida. Rasmiy logotip oq doira ustida,
 * o'z ranglarida chiziladi (qora logotip ham qorong'i mavzuda ko'rinsin). PNG topilmasa — bulut belgisi.
 */
@Composable
fun ProviderAvatar(providerId: String, size: Dp = 34.dp, iconSize: Dp = 22.dp) {
    val context = LocalContext.current
    val bitmap = remember(providerId) { loadProviderIcon(context, providerId) }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(iconSize))
        } else {
            Icon(Icons.Filled.Cloud, contentDescription = null, tint = Color(0xFF444444), modifier = Modifier.size(iconSize))
        }
    }
}
