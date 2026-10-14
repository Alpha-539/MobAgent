package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.ui.theme.BrandPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Profil rasmi manbalari (tartib bo'yicha):
 *  1) Sozlamalarda qo'lda tanlangan rasm (files/profile_<uid>.jpg, 256x256) — faqat shu telefonda,
 *     har bir hisob uchun alohida;
 *  2) Google hisobining rasmi (files/google_<uid>.jpg — yuklab olinib keshlanadi, oflaynda ham ko'rinadi);
 *  3) hech biri bo'lmasa — ism/emailning bosh harfi (ProfileAvatar ichida).
 */
object ProfileStore {
    private const val SIZE = 256
    /** Rasm o'zgarganda oshadi — ko'rsatuvchi joylar qayta yuklanadi. */
    var version by mutableIntStateOf(0)
        private set

    // Bu sessiyada allaqachon yangilangan Google rasm manzillari (har ochilganda qayta yuklamaslik uchun).
    private val refreshed = mutableSetOf<String>()

    private fun uid(): String? = AuthRepository.user?.uid
    private fun customFile(context: Context, uid: String) = File(context.filesDir, "profile_$uid.jpg")
    private fun googleFile(context: Context, uid: String) = File(context.filesDir, "google_$uid.jpg")

    /** Joriy hisob uchun qo'lda tanlangan rasm bormi ("Rasmni olib tashlash" ko'rsatish uchun). */
    fun exists(context: Context): Boolean = uid()?.let { customFile(context, it).exists() } ?: false

    suspend fun saveFrom(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val id = uid() ?: return@withContext false
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext false
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
            customFile(context, id).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            version++
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Qo'lda tanlangan rasmni olib tashlaydi; Google rasmi (bo'lsa) qaytadan ko'rinadi. */
    fun remove(context: Context) {
        uid()?.let { customFile(context, it).delete() }
        version++
    }

    /** Hisob o'chirilganda uning telefondagi rasmlarini (qo'lda tanlangan va Google keshi) tozalaydi. */
    fun wipeFor(context: Context, uid: String) {
        customFile(context, uid).delete()
        googleFile(context, uid).delete()
    }

    private fun decode(f: File): androidx.compose.ui.graphics.ImageBitmap? =
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null

    /** Google rasmini yuklab, faylga yozadi. Xato bo'lsa false (eski kesh qoladi). */
    private fun downloadGoogle(rawUrl: String, target: File): Boolean = try {
        // Google odatda kichik (=s96-c) rasm beradi — kattaroq so'raymiz.
        val url = rawUrl.replace(Regex("=s\\d+-c"), "=s$SIZE-c")
        if (!url.startsWith("https://")) {
            false
        } else {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            try {
                if (conn.responseCode != 200) {
                    false
                } else {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                        false
                    } else {
                        target.writeBytes(bytes)
                        true
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    } catch (e: Exception) {
        false
    }

    /** Ko'rsatiladigan rasm: qo'lda tanlangan -> Google -> null (bosh harf ko'rsatiladi). */
    suspend fun load(context: Context, uid: String?, photoUrl: String?): androidx.compose.ui.graphics.ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (uid == null) return@withContext null
            try {
                decode(customFile(context, uid))?.let { return@withContext it }
                val cache = googleFile(context, uid)
                if (photoUrl == null) {
                    // Hisobda rasm yo'q (yoki email bilan kirilgan) — eskirgan keshni tozalaymiz.
                    cache.delete()
                    return@withContext null
                }
                val shouldRefresh = synchronized(refreshed) { refreshed.add("$uid|$photoUrl") }
                if (shouldRefresh && !downloadGoogle(photoUrl, cache)) {
                    synchronized(refreshed) { refreshed.remove("$uid|$photoUrl") }
                }
                decode(cache)
            } catch (e: Exception) {
                null
            }
        }
}

/** Rasm bo'lsa (qo'lda tanlangan yoki Google) — rasm, bo'lmasa — ism/emailning bosh harfi. */
@Composable
fun ProfileAvatar(size: Dp, fontSize: TextUnit = 18.sp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val user = AuthRepository.user
    val uid = user?.uid
    val photoUrl = user?.photoUrl?.toString()
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ProfileStore.version, uid, photoUrl) {
        value = ProfileStore.load(context, uid, photoUrl)
    }
    val initial = (AuthRepository.displayName?.takeIf { it.isNotBlank() } ?: user?.email)
        ?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(BrandPurple),
        contentAlignment = Alignment.Center
    ) {
        val img = image
        if (img != null) {
            Image(bitmap = img, contentDescription = tr("Profil rasmi"), contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(initial, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
        }
    }
}
