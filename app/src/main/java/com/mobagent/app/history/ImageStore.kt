package com.mobagent.app.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Onlayn chatda yuborilgan rasmlar. Rasm yuborishdan oldin kichraytiriladi (eng uzun tomoni
 * [MAX_SIDE] px) va JPEG qilib `files/chat_images/` ga saqlanadi. Suhbat tarixida faqat fayl nomi
 * turadi (StoredMessage.images) — shu sabab tarix JSON'i kichik qoladi.
 */
object ImageStore {
    const val MAX_PER_MESSAGE = 4
    private const val MAX_SIDE = 1280
    private const val JPEG_QUALITY = 85

    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "chat_images").apply { mkdirs() }
    }

    private fun fileOf(name: String): File? = dir?.let { File(it, name) }

    /** Galereyadan tanlangan rasmni o'qib, kichraytirib saqlaydi. Muvaffaqiyatsiz bo'lsa null. */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val target = dir ?: return@withContext null
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null

            // Kamera rasmlari ko'pincha EXIF orqali burilgan bo'ladi.
            val rotation = runCatching {
                resolver.openInputStream(uri)?.use { ins ->
                    when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }
            }.getOrNull() ?: 0f

            val longest = maxOf(bmp.width, bmp.height)
            val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
            if (rotation != 0f || scale != 1f) {
                val m = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
                val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (t !== bmp) bmp.recycle()
                bmp = t
            }
            // JPEG shaffoflikni bilmaydi — shaffof joylar qora bo'lib qolmasin.
            if (bmp.hasAlpha()) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                bmp.recycle()
                bmp = flat
            }

            val name = UUID.randomUUID().toString() + ".jpg"
            File(target, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bmp.recycle()
            name
        } catch (e: Exception) {
            null
        }
    }

    /** API'ga yuborish uchun base64 (prefikssiz). Fayl yo'q bo'lsa null. */
    fun base64(name: String): String? = try {
        fileOf(name)?.takeIf { it.exists() }?.readBytes()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    } catch (e: Exception) {
        null
    }

    /** Ekranda ko'rsatish uchun kichik nusxa (eng uzun tomoni ~[maxSide] px). */
    fun thumbnail(name: String, maxSide: Int = 480): Bitmap? = try {
        val f = fileOf(name)?.takeIf { it.exists() }
        if (f == null) null else {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, b)
            var sample = 1
            while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    fun delete(name: String) {
        runCatching { fileOf(name)?.delete() }
    }
}
