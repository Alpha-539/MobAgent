package com.mobagent.app.linux

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Ubuntu Base rootfs'ni yuklab oladi, SHA-256 bilan tekshiradi va ochadi.
 * Fayl nomi qattiq yozilmaydi: SHA256SUMS dan eng yangi `ubuntu-base-*-base-arm64.tar.gz` olinadi.
 */
object LinuxInstaller {

    // 24.04 LTS. Kerak bo'lsa boshqa reliz papkasiga almashtiring.
    private const val RELEASE_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
    private const val MIN_FREE_BYTES = 500L * 1024 * 1024

    // APK ichidagi rootfs (Google Play tashqaridan fayl yuklashni yoqtirmaydi): app/src/main/assets/linux/ ichida.
    // Eng ixcham format birinchi: .tar.xz (scripts/build-rootfs.sh tayyorlaydi, CI o'zi ishga tushiradi), keyin .tar.gz.
    private const val BUNDLED_ASSET_DIR = "linux"
    private val BUNDLED_ASSETS = listOf("ubuntu-base-arm64.tar.xz", "ubuntu-base-arm64.tar.gz")

    // false qilsangiz, APK ichida rootfs bo'lmasa internetdan YUKLAMAYDI (Play uchun toza variant).
    private const val ALLOW_NETWORK_FALLBACK = true

    private fun bundledAssetName(context: Context): String? = try {
        val have = context.assets.list(BUNDLED_ASSET_DIR)?.toSet().orEmpty()
        BUNDLED_ASSETS.firstOrNull { it in have }
    } catch (_: Exception) { null }

    fun hasBundledRootfs(context: Context): Boolean = bundledAssetName(context) != null

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class Downloading(val done: Long, val total: Long) : State()
        object Verifying : State()
        data class Extracting(val files: Int) : State()
        object Configuring : State()
        object Done : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    // Ilova umri davomida yashaydi: foydalanuvchi ekrandan chiqib ketsa ham o'rnatish davom etadi.
    // (Ilova butunlay o'ldirilsa to'xtaydi — keyingi bosqichda foreground service qilinadi.)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    // install() bloklovchi kod (HTTP/fayl), shuning uchun coroutine bekor qilinishi uni o'zi to'xtatmaydi —
    // sikllar shu bayroqni tekshiradi.
    @Volatile private var cancelRequested = false

    private class UnsupportedDeviceException(message: String) : Exception(message)

    /** `auto` — foydalanuvchi bosmagan, ilova o'zi boshlagan o'rnatish: oldin proot tekshiriladi, xatolar hisoblanadi. */
    fun start(context: Context, auto: Boolean = false) {
        if (isRunning()) return
        val app = context.applicationContext
        cancelRequested = false
        // Sinxron o'rnatiladi: kuzatuvchilar (servis) eski Done/Failed holatini ko'rib qolmasligi uchun.
        _state.value = State.Checking
        job = scope.launch {
            try {
                install(app, auto)
                LinuxEnvironment.onInstallSucceeded(app)
                _state.value = State.Done
            } catch (e: Exception) {
                cleanupPartial(app)
                if (cancelRequested) {
                    _state.value = State.Idle
                } else {
                    val msg = e.message ?: e.javaClass.simpleName
                    LinuxEnvironment.onInstallFailed(app, msg, auto, unsupported = e is UnsupportedDeviceException)
                    _state.value = State.Failed(msg)
                }
            }
        }
    }

    fun cancel() {
        cancelRequested = true
        job?.cancel()
        _state.value = State.Idle
    }

    fun resetState() {
        if (!isRunning()) _state.value = State.Idle
    }

    private fun checkCancelled() {
        if (cancelRequested) throw java.util.concurrent.CancellationException("Bekor qilindi")
    }

    /** Muvaffaqiyatsiz/bekor qilingan o'rnatishdan qolgan yarim rootfs va yuklangan arxivni o'chiradi. */
    private fun cleanupPartial(context: Context) {
        try {
            if (!LinuxEnvironment.isInstalled(context)) LinuxEnvironment.wipeRootfsBlocking(context)
            context.cacheDir.listFiles { f -> f.name.startsWith("ubuntu-base-") }?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun install(context: Context, auto: Boolean) {
        if (auto) {
            // Avtomatik rejimda avval proot shu telefonda ishlashini tekshiramiz — ishlamasa 30 MB yuklanmaydi.
            _state.value = State.Checking
            val pf = LinuxEnvironment.preflight(context)
            if (pf.exitCode != 0) {
                throw UnsupportedDeviceException("proot bu telefonda ishga tushmadi: " + pf.output.take(300).ifBlank { "exit=${pf.exitCode}" })
            }
            checkCancelled()
        }
        val base = LinuxEnvironment.baseDir(context).apply { mkdirs() }
        if (base.usableSpace < MIN_FREE_BYTES) {
            error("Xotira yetarli emas (kamida ${MIN_FREE_BYTES / 1024 / 1024} MB bo'sh joy kerak)")
        }

        val bundled = bundledAssetName(context)
        val tarball: File?
        val fileName: String
        if (bundled != null) {
            // APK ichida tayyor rootfs bor: internetsiz, vaqtinchalik nusxasiz — to'g'ridan-to'g'ri oqimdan ochiladi.
            fileName = bundled
            tarball = null
            _state.value = State.Extracting(0)
        } else {
            if (!ALLOW_NETWORK_FALLBACK) error("APK ichida Ubuntu rootfs yo'q (assets/linux/ubuntu-base-arm64.tar.xz)")
            // 1) Fayl nomi va nazorat yig'indisi
            _state.value = State.Downloading(0, 0)
            val sums = httpGetText("$RELEASE_URL/SHA256SUMS")
            val entry = Regex("^([0-9a-f]{64}) [ *]?(ubuntu-base-[0-9.]+-base-arm64\\.tar\\.gz)\\s*$", RegexOption.MULTILINE)
                .findAll(sums).map { it.groupValues[1] to it.groupValues[2] }.lastOrNull()
                ?: error("SHA256SUMS ichida arm64 rootfs topilmadi")
            val (expectedSha, name) = entry
            fileName = name

            // 2) Yuklab olish (yo'l-yo'lakay SHA-256 hisoblanadi)
            val dl = File(context.cacheDir, fileName)
            tarball = dl
            val actualSha = download("$RELEASE_URL/$fileName", dl)
            _state.value = State.Verifying
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                dl.delete()
                error("Yuklangan fayl nazorat yig'indisiga mos kelmadi (buzilgan yoki o'zgartirilgan)")
            }
        }

        // 3) Eski yarim-o'rnatilgan holatni tozalab, qaytadan ochish
        val rootfs = LinuxEnvironment.rootfsDir(context)
        if (rootfs.exists()) {
            LinuxEnvironment.wipeRootfsBlocking(context)
        }
        rootfs.mkdirs()
        extract(openTarStream(context, bundled, tarball), rootfs)
        tarball?.delete()

        // 4) proot ichida apt/DNS ishlashi uchun sozlash
        _state.value = State.Configuring
        configure(rootfs)
        LinuxEnvironment.markerFile(context).writeText(fileName)
    }

    private fun httpGetText(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) {
            val code = c.responseCode
            c.disconnect()
            error("Server javobi: HTTP $code ($url)")
        }
        return c
    }

    /** Faylni yuklaydi va SHA-256 (hex) ni qaytaradi. */
    private fun download(url: String, dest: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(url)
        try {
            val total = c.contentLengthLong
            var done = 0L
            var lastEmit = 0L
            c.inputStream.use { input ->
                dest.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - lastEmit >= 256 * 1024) {
                            lastEmit = done
                            _state.value = State.Downloading(done, total)
                        }
                    }
                }
            }
            _state.value = State.Downloading(done, total)
        } finally {
            c.disconnect()
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Siqilgan arxivni ochuvchi oqim: APK assets'idan (.tar.xz / .tar.gz) yoki yuklangan fayldan (.tar.gz). */
    private fun openTarStream(context: Context, bundled: String?, tarball: File?): java.io.InputStream {
        val raw = BufferedInputStream(
            if (bundled != null) context.assets.open("$BUNDLED_ASSET_DIR/$bundled") else FileInputStream(tarball!!),
            64 * 1024
        )
        val name = bundled ?: tarball!!.name
        return if (name.endsWith(".xz")) XZCompressorInputStream(raw) else GZIPInputStream(raw)
    }

    private fun extract(input: java.io.InputStream, root: File) {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        var count = 0
        TarArchiveInputStream(input).use { tar ->
            var e: TarArchiveEntry? = tar.nextTarEntry
            while (e != null) {
                checkCancelled()
                val entry: TarArchiveEntry = e
                val rel = entry.name.removePrefix("./").trimEnd('/')
                if (rel.isNotEmpty()) {
                    val destPath = rootPath.resolve(rel).normalize()
                    // Tashqariga chiqib ketishdan himoya ("../" yoki symlink orqali)
                    if (destPath.startsWith(rootPath) && !hasSymlinkAncestor(rootPath, destPath)) {
                        extractEntry(tar, entry, destPath.toFile(), rootPath)
                    }
                }
                count++
                if (count % 200 == 0) _state.value = State.Extracting(count)
                e = tar.nextTarEntry
            }
        }
        _state.value = State.Extracting(count)
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, dest: File, root: java.nio.file.Path) {
        val mode = entry.mode and 0b111_111_111
        when {
            entry.isDirectory -> {
                dest.mkdirs()
                // Egasi doim o'qiy/yoza olishi shart, aks holda ichiga yozib yoki keyin o'chirib bo'lmaydi
                Os.chmod(dest.path, mode or 0b111_000_000)
            }
            entry.isSymbolicLink -> {
                dest.parentFile?.mkdirs()
                java.nio.file.Files.deleteIfExists(dest.toPath())
                Os.symlink(entry.linkName, dest.path)
            }
            entry.isLink -> { // hard link
                dest.parentFile?.mkdirs()
                val target = root.resolve(entry.linkName.removePrefix("./")).normalize()
                if (!target.startsWith(root)) return
                java.nio.file.Files.deleteIfExists(dest.toPath())
                try {
                    Os.link(target.toString(), dest.path)
                } catch (_: Exception) {
                    target.toFile().copyTo(dest, overwrite = true)
                }
            }
            entry.isFile -> {
                dest.parentFile?.mkdirs()
                java.nio.file.Files.deleteIfExists(dest.toPath())
                dest.outputStream().use { out -> tar.copyTo(out) }
                Os.chmod(dest.path, mode or 0b110_000_000)
            }
            // qurilma fayllari (char/block/fifo) — /dev proot orqali ulanadi, bu yerda kerak emas
            else -> Unit
        }
    }

    private fun hasSymlinkAncestor(root: java.nio.file.Path, dest: java.nio.file.Path): Boolean {
        var p = dest.parent
        while (p != null && p != root && p.startsWith(root)) {
            if (java.nio.file.Files.isSymbolicLink(p)) return true
            p = p.parent
        }
        return false
    }

    private fun configure(rootfs: File) {
        fun write(rel: String, text: String) {
            val f = File(rootfs, rel)
            f.parentFile?.mkdirs()
            java.nio.file.Files.deleteIfExists(f.toPath()) // resolv.conf ba'zan symlink bo'ladi
            f.writeText(text)
        }
        // Android'da /etc/resolv.conf yo'q — DNS'ni o'zimiz beramiz
        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        // apt odatda _apt foydalanuvchisiga tushadi; proot ichida setgroups ishlamaydi
        write("etc/apt/apt.conf.d/99mobagent", "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n")
        write("etc/dpkg/dpkg.cfg.d/99mobagent", "force-unsafe-io\n")
        // dpkg o'rnatgan xizmatlarni ishga tushirishga urinmasin
        write("usr/sbin/policy-rc.d", "#!/bin/sh\nexit 101\n")
        Os.chmod(File(rootfs, "usr/sbin/policy-rc.d").path, 0b111_101_101)
        File(rootfs, "sdcard").mkdirs() // telefon xotirasini ulash nuqtasi
        File(rootfs, "tmp").mkdirs()
        Os.chmod(File(rootfs, "tmp").path, 0b111_111_111)
        File(rootfs, "root").mkdirs()
        Os.chmod(File(rootfs, "root").path, 0b111_000_000)
    }
}
