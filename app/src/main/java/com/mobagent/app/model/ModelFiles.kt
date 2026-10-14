package com.mobagent.app.model

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * SINOV UCHUN: modellar ilova o'chirilganda ham yo'qolmasligi uchun
 * telefon xotirasining umumiy papkasida (/storage/emulated/0/MobAgent/models)
 * saqlanadi. Ilovaning shaxsiy papkasi (filesDir) ilova o'chirilganda
 * butunlay tozalanadi.
 *
 * Buning uchun "Barcha fayllarga kirish" (MANAGE_EXTERNAL_STORAGE) ruxsati
 * berilgan bo'lishi kerak. Ruxsat bo'lmasa — eski usul (filesDir).
 */
private fun hasSharedAccess(): Boolean = false // Google Play: umumiy xotiraga kirish ruxsati olib tashlangan

/** Ilova o'chirilsa ham saqlanib qoladigan papka; ruxsat bo'lmasa null. */
fun persistentModelsDir(): File? =
    if (hasSharedAccess()) File(Environment.getExternalStorageDirectory(), "MobAgent/models") else null

private fun internalModelsDir(context: Context): File = File(context.filesDir, "models")

/** Yangi yuklashlar shu papkaga tushadi. */
fun modelsDir(context: Context): File = persistentModelsDir() ?: internalModelsDir(context)

fun downloadedModelFile(context: Context, modelId: String): File? {
    val candidates = listOfNotNull(persistentModelsDir(), internalModelsDir(context))
    for (dir in candidates) {
        val f = File(dir, "$modelId.gguf")
        if (f.exists() && f.length() > 0) return f
    }
    return null
}

fun deleteDownloadedModel(context: Context, modelId: String) {
    listOfNotNull(persistentModelsDir(), internalModelsDir(context)).forEach {
        File(it, "$modelId.gguf").delete()
    }
    deleteModelPartials(context, modelId)
}

/**
 * Yarim yuklangan fayllarni (`.part` va `.meta.json`) o'chiradi — yuklab olish
 * bekor qilinganda yoki model o'chirilganda GB'larcha "yetim" fayl qolmasligi uchun.
 */
fun deleteModelPartials(context: Context, modelId: String) {
    listOfNotNull(persistentModelsDir(), internalModelsDir(context)).forEach {
        File(it, "$modelId.gguf.part").delete()
        File(it, "$modelId.gguf.meta.json").delete()
    }
}

/** Davom ettirish mumkin bo'lgan yarim yuklangan fayl hajmi (bayt) — ikkala papkadan eng kattasi; yo'q bo'lsa 0. */
fun partialDownloadBytes(context: Context, modelId: String): Long =
    listOfNotNull(persistentModelsDir(), internalModelsDir(context))
        .maxOfOrNull { File(it, "$modelId.gguf.part").takeIf { f -> f.exists() }?.length() ?: 0L } ?: 0L

/**
 * `.part` (va `.meta.json`) boshqa papkada qolgan bo'lsa (masalan "Barcha fayllarga kirish"
 * ruxsati yuklash o'rtasida berilgan/olingan), uni joriy [modelsDir]ga ko'chiradi — shunda
 * yuklash boshidan emas, qolgan joyidan davom etadi. Joy yetmasa — o'tkazib yuboradi.
 * Yuklash boshlanishidan oldin, fon tredida chaqiring.
 */
fun adoptPartialDownload(context: Context, modelId: String) {
    val targetDir = modelsDir(context)
    val dst = File(targetDir, "$modelId.gguf.part")
    val others = listOfNotNull(persistentModelsDir(), internalModelsDir(context)).filter { it != targetDir }
    val src = others.map { File(it, "$modelId.gguf.part") }.filter { it.exists() && it.length() > 0 }
        .maxByOrNull { it.length() } ?: return
    if (dst.exists() && dst.length() >= src.length()) return
    targetDir.mkdirs()
    val srcMeta = File(src.parentFile, "$modelId.gguf.meta.json")
    val dstMeta = File(targetDir, "$modelId.gguf.meta.json")
    try {
        if (!src.renameTo(dst)) {
            if (freeSpaceBytes(targetDir) < src.length() + DOWNLOAD_SPACE_MARGIN_BYTES) return
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        if (srcMeta.exists() && !srcMeta.renameTo(dstMeta)) {
            srcMeta.copyTo(dstMeta, overwrite = true)
            srcMeta.delete()
        }
    } catch (e: Exception) {
        // Ko'chirib bo'lmadi — yuklash oddiy tarzda (kerak bo'lsa boshidan) davom etadi.
    }
}

/** Yuklab olish uchun zaxira: fayl tizimi va boshqa yozuvlar uchun qo'shimcha joy. */
const val DOWNLOAD_SPACE_MARGIN_BYTES: Long = 256L * 1024 * 1024

/** `dir` joylashgan xotiradagi bo'sh joy (bayt). Papka hali yo'q bo'lsa, eng yaqin mavjud ota-papka olinadi. */
fun freeSpaceBytes(dir: File): Long {
    var d: File? = dir
    while (d != null && !d.exists()) d = d.parentFile
    if (d == null) return Long.MAX_VALUE
    return try {
        StatFs(d.path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE // o'lchab bo'lmasa — yuklashni to'sib qo'ymaymiz
    }
}

/** Faylning boshi "GGUF" belgisi bilan boshlanadimi (xato sahifa/buzuq fayldan himoya). */
fun hasGgufMagic(file: File): Boolean = try {
    file.inputStream().use { i ->
        val b = ByteArray(4)
        i.read(b) == 4 && b[0] == 'G'.code.toByte() && b[1] == 'G'.code.toByte() &&
            b[2] == 'U'.code.toByte() && b[3] == 'F'.code.toByte()
    }
} catch (e: Exception) {
    false
}

/**
 * Ilova ichida (filesDir) turgan modellarni umumiy papkaga KO'CHIRADI (yo'q bo'lsa).
 * Yangilangan APK ustidan o'rnatilganda eski model shu orqali "ilova o'chirilsa ham
 * saqlanadigan" joyga o'tadi. Fon tredida chaqirilishi kerak (katta fayl).
 *
 * Tuzatishlar: (1) qolib ketgan `.copying` fayllar tozalanadi; (2) nusxalashdan oldin
 * bo'sh joy tekshiriladi; (3) rename'dan oldin hajm tekshiriladi; (4) muvaffaqiyatli
 * nusxadan keyin ichki nusxa o'chiriladi — aks holda har bir model xotirada IKKI marta turardi.
 */
private val syncLock = Any()

fun syncModelsToPersistent(context: Context) = synchronized(syncLock) {
    syncModelsToPersistentLocked(context)
}

private fun syncModelsToPersistentLocked(context: Context) {
    val target = persistentModelsDir() ?: return
    val source = internalModelsDir(context)
    target.mkdirs()
    target.listFiles { f -> f.name.endsWith(".copying") }?.forEach { it.delete() }
    val files = source.listFiles { f -> f.isFile && f.name.endsWith(".gguf") } ?: return
    for (src in files) {
        val dst = File(target, src.name)
        if (dst.exists() && dst.length() == src.length()) {
            src.delete() // allaqachon ko'chirilgan — takroriy nusxa joy egallamasin
            continue
        }
        if (freeSpaceBytes(target) < src.length() + DOWNLOAD_SPACE_MARGIN_BYTES) continue
        val tmp = File(target, src.name + ".copying")
        try {
            src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            if (tmp.length() == src.length() && tmp.renameTo(dst)) src.delete() else tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
        }
    }
}
