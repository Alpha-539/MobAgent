package com.mobagent.app.model

import com.mobagent.app.i18n.tr
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Bitta faylni yuklab olish holati: umumiy foizi va bosqich nomi. */
data class DownloadProgress(
    val stage: String,
    val bytesDone: Long,
    val bytesTotal: Long
) {
    val percent: Int
        get() = if (bytesTotal <= 0L) 0 else ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
}

/** `<file>.part` bilan birga saqlanadigan kichik metama'lumot — qayta ochilganda shu manba haqiqatan ham davom ettirilishi mumkinligini tekshirish uchun. */
@Serializable
private data class PartialMeta(val url: String, val totalBytes: Long)

/**
 * ModelStoreScreen'da "Yuklab olish" bosilganda haqiqiy faylni internetdan
 * olib, qurilma xotirasiga yozadi va progressni chaqiruvchiga (UI) yuboradi.
 *
 * Ikkita muhim kafolat qo'shildi:
 * 1. **Davom ettirish (resume):** internet uzilib qolsa, `.part` fayl va uning
 *    yonidagi kichik `.meta.json`i saqlanib qoladi; keyingi urinishda HTTP
 *    `Range` so'rovi bilan aynan shu joydan davom etadi (agar server
 *    qo'llab-quvvatlasa; aks holda avtomatik boshidan boshlanadi).
 * 2. **Yaxlitlik tekshiruvi:** HuggingFace bu fayllar uchun ochiq SHA-256
 *    e'lon qilmagani sababli (apt paketlaridan farqli o'laroq), haqiqiy
 *    kripto-checksum solishtirib bo'lmaydi. Shuning uchun eng ishonchli
 *    amaliy tekshiruv qilinadi: yuklab olingan fayl hajmi serverning
 *    `Content-Length`iga aniq teng bo'lishi kerak — mos kelmasa, fayl
 *    buzuq deb hisoblanadi va o'chirilib, xato qaytariladi (chat ekraniga
 *    "buzuq model bilan davom etish" hech qachon ruxsat berilmaydi).
 */
class ModelDownloader(private val client: HttpClient = HttpClient(Android)) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun downloadModel(
        model: ModelItem,
        targetDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): File {
        if (!targetDir.exists()) targetDir.mkdirs()

        val baseFile = File(targetDir, "${model.id}.gguf")
        // Fayl allaqachon bor bo'lsa u oldin tekshirilgan — qayta hisoblamaymiz (bir necha GB o'qish sekin).
        val alreadyThere = baseFile.exists()
        downloadWithResume(model.downloadUrl, baseFile, "Model") { done, total ->
            onProgress(DownloadProgress("Model", done, total))
        }
        val expectedSha = model.sha256
        if (!alreadyThere && expectedSha != null) {
            val actual = withContext(Dispatchers.IO) { sha256Of(baseFile) }
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                baseFile.delete()
                throw IOException(tr("Yuklab olingan fayl SHA-256 tekshiruvidan o'tmadi — fayl buzuq yoki almashtirilgan. Qayta urinib ko'ring."))
            }
        }

        return baseFile
    }

    /**
     * @throws IOException fayl buzuq (hajmi mos kelmasa) yoki tarmoq xatosida —
     * chaqiruvchi shu holatda `.part`ni saqlab qo'yishi (keyinroq davom
     * ettirish uchun) yoki to'liq o'chirib qayta boshlashi mumkin.
     */
    private suspend fun downloadWithResume(
        url: String,
        target: File,
        stage: String,
        onProgress: (Long, Long) -> Unit
    ) {
        // Allaqachon muvaffaqiyatli yuklab olingan — lekin faqat haqiqiy GGUF bo'lsa.
        // Ilgari xato sahifa (HTML) yoki bo'sh fayl "yuklangan" deb qolib ketardi
        // va hech qachon qayta yuklanmasdi.
        if (target.exists()) {
            if (target.length() > 0 && hasGgufMagic(target)) return
            target.delete()
        }

        val partFile = File(target.parentFile, "${target.name}.part")
        val metaFile = File(target.parentFile, "${target.name}.meta.json")

        // [FIX 2] Umumiy hajmni ishonchli aniqlash: HEAD -> bo'lmasa "Range: bytes=0-0" GET.
        val remoteTotal = probeRemoteSize(url)

        val previousMeta = readMeta(metaFile)

        // .part allaqachon to'liq (rename'dan oldin jarayon o'lgan) — qayta yuklamasdan yakunlaymiz.
        if (remoteTotal > 0 && partFile.exists() && partFile.length() == remoteTotal && hasGgufMagic(partFile)) {
            if (!partFile.renameTo(target)) {
                partFile.copyTo(target, overwrite = true)
                partFile.delete()
            }
            metaFile.delete()
            return
        }

        val resumeLimit = if (remoteTotal > 0) remoteTotal else Long.MAX_VALUE
        val canResume = partFile.exists() &&
            previousMeta != null &&
            previousMeta.url == url &&
            (remoteTotal <= 0 || previousMeta.totalBytes == remoteTotal) &&
            resumeLimit > partFile.length()

        if (!canResume && partFile.exists()) {
            partFile.delete() // remote o'zgargan yoki mos kelmayapti — boshidan boshlaymiz
            metaFile.delete()
        }

        val startOffset = if (canResume) partFile.length() else 0L

        fun checkSpace(total: Long) {
            val needed = total - startOffset + DOWNLOAD_SPACE_MARGIN_BYTES
            val free = freeSpaceBytes(target.parentFile ?: target)
            if (free < needed) {
                throw IOException(
                    tr("Xotirada bo'sh joy yetarli emas: kerak %1\$s MB, bor %2\$s MB.", needed / MB, free / MB)
                )
            }
        }
        if (remoteTotal > 0) {
            checkSpace(remoteTotal)
            writeMeta(metaFile, PartialMeta(url, remoteTotal))
        }

        // Bu qiymat javob kelgach aniqlanadi (HEAD/probe hajm bermagan bo'lsa, GET Content-Length'dan).
        var expectedTotal = remoteTotal

        client.prepareGet(url) {
            if (startOffset > 0) {
                headers { append(HttpHeaders.Range, "bytes=$startOffset-") }
            }
        }.execute { response: HttpResponse ->
            if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                partFile.delete()
                metaFile.delete()
                throw IOException(tr("Server xatosi (%1\$s). Keyinroq qayta urinib ko'ring.", response.status.value))
            }
            if (!response.status.isSuccess()) {
                throw IOException(tr("Server xatosi (%1\$s). Keyinroq qayta urinib ko'ring.", response.status.value))
            }

            // [FIX 1] Server Range'ni e'tiborsiz qoldirib 200 qaytarsa — fayl boshidan keladi:
            // offset 0 ga tushadi va .part qayta yoziladi (append=false). Progress shunga mos hisoblanadi.
            val resumed = startOffset > 0 && response.status == HttpStatusCode.PartialContent
            val base = if (resumed) startOffset else 0L

            if (expectedTotal <= 0) {
                // HEAD ham, probe ham hajm bermadi — GET javobining Content-Length'iga tayanamiz.
                val len = response.contentLength()
                if (len != null && len > 0) {
                    expectedTotal = base + len
                    if (!resumed) checkSpace(expectedTotal)
                    writeMeta(metaFile, PartialMeta(url, expectedTotal))
                }
            }

            val channel = response.bodyAsChannel()
            var written = 0L
            var lastReported = 0L
            FileOutputStream(partFile, resumed).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= PROGRESS_STEP) {
                            lastReported = written
                            onProgress(base + written, expectedTotal)
                        }
                    }
                }
            }
            onProgress(base + written, expectedTotal)

            // [FIX 3] Ulanish uzilganda kanal xato bilan yopiladi, lekin sikl jimgina tugardi.
            channel.closedCause?.let { cause ->
                throw IOException(
                    cause.localizedMessage ?: tr("Internet aloqasi uzildi. Qayta urinib ko'ring."), cause
                )
            }
        }

        // YAXLITLIK TEKSHIRUVI: hajm serverning e'lon qilgan hajmiga aniq teng bo'lishi shart.
        val expected = if (expectedTotal > 0) expectedTotal else null
        if (expected != null && partFile.length() != expected) {
            throw IOException(
                tr("Yuklab olingan fayl hajmi mos kelmadi (%1\$s /%2\$s) — fayl buzuq bo'lishi mumkin. Internet aloqasi tiklangach qayta urinib ko'ring.", partFile.length(), expected)
            )
        }

        if (!hasGgufMagic(partFile)) {
            partFile.delete()
            metaFile.delete()
            throw IOException(tr("Yuklab olingan fayl model (GGUF) formatida emas. Qayta urinib ko'ring."))
        }

        if (!partFile.renameTo(target)) {
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
        }
        metaFile.delete()
    }

    /**
     * Fayl hajmini aniqlaydi. HuggingFace/CDN ba'zan HEAD'ga Content-Length bermaydi,
     * shuning uchun zaxira: "Range: bytes=0-0" GET va "Content-Range: bytes 0-0/TOTAL".
     * Aniqlab bo'lmasa -1.
     */
    private suspend fun probeRemoteSize(url: String): Long {
        try {
            val head = client.head(url)
            if (head.status.isSuccess()) {
                head.contentLength()?.takeIf { it > 0 }?.let { return it }
            }
        } catch (e: Exception) { /* zaxiraga o'tamiz */ }
        return try {
            // MUHIM: client.get() butun javob tanasini xotiraga o'qiydi — server Range'ni e'tiborsiz
            // qoldirib 200 (butun fayl) qaytarsa, bir necha GB OOM beradi. Shuning uchun prepareGet:
            // faqat sarlavhalarni o'qib, tanani o'qimay chiqamiz (ulanish yopiladi).
            client.prepareGet(url) { headers { append(HttpHeaders.Range, "bytes=0-0") } }
                .execute { r: HttpResponse ->
                    r.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.trim()?.toLongOrNull()
                        ?.takeIf { it > 0 }
                        ?: if (r.status == HttpStatusCode.OK) (r.contentLength() ?: -1L) else -1L
                }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun readMeta(f: File): PartialMeta? = try {
        if (f.exists()) json.decodeFromString<PartialMeta>(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun writeMeta(f: File, meta: PartialMeta) {
        try {
            f.writeText(json.encodeToString(meta))
        } catch (e: Exception) {
            // Meta yozib bo'lmasa ham yuklab olish davom etadi — faqat resume imkoniyati yo'qoladi.
        }
    }

    companion object {
        private const val DEFAULT_BUFFER = 8192
        private const val PROGRESS_STEP = 256L * 1024L
        private const val MB = 1024L * 1024L
    }
}
