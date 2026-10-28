package com.mobagent.app.model

import android.content.Context
import android.net.Uri
import com.mobagent.app.prompt.ModelFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

@Serializable
data class CustomModelData(
    val id: String,
    val name: String,
    val downloadUrl: String,
    val direction: String,
    val baseSizeMb: Long,
    val baseRamMb: Long,
    val familyName: String = "GENERIC",
    val paramsBillion: Double = 1.5
)

fun CustomModelData.toModelItem(): ModelItem {
    val fam = try { ModelFamily.valueOf(familyName) } catch (_: Exception) { ModelFamily.GENERIC }
    return ModelItem(
        id = id,
        name = name,
        downloadUrl = downloadUrl,
        direction = direction,
        category = ModelCategory.CHAT,
        baseSizeMb = baseSizeMb,
        baseRamMb = baseRamMb,
        minPhoneClass = "Maxsus model",
        family = fam,
        paramsBillion = paramsBillion
    )
}

fun ModelItem.toCustomData(): CustomModelData {
    return CustomModelData(
        id = id,
        name = name,
        downloadUrl = downloadUrl,
        direction = direction,
        baseSizeMb = baseSizeMb,
        baseRamMb = baseRamMb,
        familyName = family.name,
        paramsBillion = paramsBillion
    )
}

data class HuggingFaceGgufFile(
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long
) {
    val sizeMb: Long get() = (sizeBytes / (1024 * 1024)).coerceAtLeast(1)
}

data class HuggingFaceModelSummary(
    val id: String,
    val author: String,
    val name: String,
    val downloads: Long,
    val likes: Int,
    val description: String,
    val category: ModelCategory = ModelCategory.CHAT
)

val TOP_10_CURATED_MODELS = listOf(
    HuggingFaceModelSummary(
        id = "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
        author = "Qwen",
        name = "Qwen2.5-Coder-1.5B",
        downloads = 2_850_000,
        likes = 0,
        description = "Mobil telefonlar uchun eng sara kod va avtomatizatsiya modeli",
        category = ModelCategory.CODING
    ),
    HuggingFaceModelSummary(
        id = "bartowski/Llama-3.2-1B-Instruct-GGUF",
        author = "Meta / bartowski",
        name = "Llama-3.2-1B-Instruct",
        downloads = 3_400_000,
        likes = 0,
        description = "Meta'ning yengil va tezkor umumiy suhbat va matn modeli",
        category = ModelCategory.CHAT
    ),
    HuggingFaceModelSummary(
        id = "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
        author = "DeepSeek / unsloth",
        name = "DeepSeek-R1-Distill-1.5B",
        downloads = 4_900_000,
        likes = 0,
        description = "Qadam-baqadam mantiqiy fikrlash (Reasoning) va hisob-kitob",
        category = ModelCategory.REASONING
    ),
    HuggingFaceModelSummary(
        id = "bartowski/gemma-2-2b-it-GGUF",
        author = "Google / bartowski",
        name = "Gemma-2-2B-IT",
        downloads = 1_950_000,
        likes = 0,
        description = "Google'ning bilim, qisqartirish va tarjima uchun ajoyib modeli",
        category = ModelCategory.TRANSLATION
    ),
    HuggingFaceModelSummary(
        id = "bartowski/Phi-3.5-mini-instruct-GGUF",
        author = "Microsoft / bartowski",
        name = "Phi-3.5-Mini (3.8B)",
        downloads = 2_100_000,
        likes = 0,
        description = "Microsoft'ning yuqori intellekt va murakkab fikrlash modeli",
        category = ModelCategory.CODING
    ),
    HuggingFaceModelSummary(
        id = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
        author = "HuggingFaceTB",
        name = "SmolLM2-360M",
        downloads = 950_000,
        likes = 0,
        description = "Ultra yengil (360M) — eng arzon telefonlarda ham tez ishlaydi",
        category = ModelCategory.CHAT
    ),
    HuggingFaceModelSummary(
        id = "bartowski/Qwen2.5-Coder-3B-Instruct-GGUF",
        author = "Qwen / bartowski",
        name = "Qwen2.5-Coder-3B",
        downloads = 1_820_000,
        likes = 0,
        description = "Katta aniqlikdagi kod yozish va buyruqlar bajarish (6 GB+ RAM)",
        category = ModelCategory.CODING
    ),
    HuggingFaceModelSummary(
        id = "QuantFactory/stable-code-instruct-3b-GGUF",
        author = "Stability AI",
        name = "Stable-Code-Instruct-3B",
        downloads = 820_000,
        likes = 0,
        description = "SQL, Python va ko'p tilli dasturlash yordamchisi",
        category = ModelCategory.CODING
    ),
    HuggingFaceModelSummary(
        id = "janhq/Jan-code-4b-gguf",
        author = "Jan.ai",
        name = "Jan-Code-4B",
        downloads = 640_000,
        likes = 0,
        description = "Mahalliy agent va buyruqlar uchun maxsus sozlangan model",
        category = ModelCategory.CODING
    ),
    HuggingFaceModelSummary(
        id = "bartowski/Mistral-7B-Instruct-v0.3-GGUF",
        author = "Mistral AI / bartowski",
        name = "Mistral-7B-Instruct-v0.3",
        downloads = 2_700_000,
        likes = 0,
        description = "Ko'p tilli afsonaviy kuchli model (yuqori sinf telefonlar uchun)",
        category = ModelCategory.CHAT
    )
)

object CustomModelStore {
    private const val FILE_NAME = "custom_models.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(context: Context): List<ModelItem> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val list = json.decodeFromString<List<CustomModelData>>(file.readText())
            list.map { it.toModelItem() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, models: List<ModelItem>) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            val data = models.map { it.toCustomData() }
            file.writeText(json.encodeToString(data))
        } catch (_: Exception) {}
    }

    fun add(context: Context, model: ModelItem) {
        val current = load(context).filter { it.id != model.id }
        val updated = current + model
        save(context, updated)
        ModelCatalog.custom = updated
    }

    fun remove(context: Context, modelId: String) {
        val current = load(context).filter { it.id != modelId }
        save(context, current)
        ModelCatalog.custom = current
        deleteDownloadedModel(context, modelId)
    }
}

fun inferModelFamily(nameOrUrl: String): ModelFamily {
    val lower = nameOrUrl.lowercase()
    return when {
        "qwen" in lower -> ModelFamily.QWEN
        "llama" in lower -> ModelFamily.LLAMA3
        "gemma" in lower -> ModelFamily.GEMMA
        "phi" in lower -> ModelFamily.PHI3
        "deepseek" in lower || "r1" in lower -> ModelFamily.DEEPSEEK_R1
        "mistral" in lower -> ModelFamily.MISTRAL
        else -> ModelFamily.GENERIC
    }
}

fun inferParamsBillion(nameOrUrl: String): Double {
    val regex = Regex("""(\d+(?:\.\d+)?)\s*[bB]""")
    val match = regex.find(nameOrUrl)
    return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.5
}

fun normalizeGgufUrl(url: String): String {
    var u = url.trim()
    if (u.contains("huggingface.co/") && u.contains("/blob/")) {
        u = u.replace("/blob/", "/resolve/")
    }
    return u
}

fun parseHuggingFaceRepoId(input: String): String? {
    val trimmed = input.trim()
    val urlRegex = Regex("""https?://huggingface\.co/([^/\s]+)/([^/\s]+)""")
    val match = urlRegex.find(trimmed)
    if (match != null) {
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        if (owner !in listOf("models", "datasets", "spaces", "api")) {
            return "$owner/$repo"
        }
    }
    val simpleRegex = Regex("""^([A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+)$""")
    if (simpleRegex.matches(trimmed)) {
        return trimmed
    }
    return null
}

fun getCuratedFallbackQuants(repoId: String): List<HuggingFaceGgufFile> {
    val cleanId = repoId.trim()
    fun makeFile(filename: String, mb: Long) = HuggingFaceGgufFile(
        filename = filename,
        downloadUrl = "https://huggingface.co/$cleanId/resolve/main/$filename",
        sizeBytes = mb * 1024 * 1024
    )

    return when {
        cleanId.contains("Llama-3.2-1B", ignoreCase = true) -> listOf(
            makeFile("Llama-3.2-1B-Instruct-Q4_K_M.gguf", 748),
            makeFile("Llama-3.2-1B-Instruct-Q5_K_M.gguf", 878),
            makeFile("Llama-3.2-1B-Instruct-Q8_0.gguf", 1320)
        )
        cleanId.contains("Qwen2.5-Coder-1.5B", ignoreCase = true) -> listOf(
            makeFile("qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", 986),
            makeFile("qwen2.5-coder-1.5b-instruct-q5_k_m.gguf", 1160),
            makeFile("qwen2.5-coder-1.5b-instruct-q8_0.gguf", 1640)
        )
        cleanId.contains("DeepSeek-R1-Distill-Qwen-1.5B", ignoreCase = true) -> listOf(
            makeFile("DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", 1120),
            makeFile("DeepSeek-R1-Distill-Qwen-1.5B-Q8_0.gguf", 1890)
        )
        cleanId.contains("gemma-2-2b-it", ignoreCase = true) -> listOf(
            makeFile("gemma-2-2b-it-Q4_K_M.gguf", 1650),
            makeFile("gemma-2-2b-it-Q5_K_M.gguf", 1920)
        )
        cleanId.contains("Phi-3.5-mini-instruct", ignoreCase = true) -> listOf(
            makeFile("Phi-3.5-mini-instruct-Q4_K_M.gguf", 2390)
        )
        cleanId.contains("SmolLM2-360M", ignoreCase = true) -> listOf(
            makeFile("SmolLM2-360M-Instruct-Q4_K_M.gguf", 240),
            makeFile("SmolLM2-360M-Instruct-Q8_0.gguf", 386)
        )
        cleanId.contains("Qwen2.5-Coder-3B", ignoreCase = true) -> listOf(
            makeFile("Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf", 2020)
        )
        cleanId.contains("stable-code-instruct-3b", ignoreCase = true) -> listOf(
            makeFile("stable-code-instruct-3b-Q4_K_M.gguf", 1980)
        )
        cleanId.contains("Jan-code-4b", ignoreCase = true) -> listOf(
            makeFile("jan-code-4b-Q4_K_M.gguf", 2450)
        )
        cleanId.contains("Mistral-7B-Instruct", ignoreCase = true) -> listOf(
            makeFile("Mistral-7B-Instruct-v0.3-Q4_K_M.gguf", 4370)
        )
        else -> emptyList()
    }
}

suspend fun searchHuggingFaceModels(query: String): Result<List<HuggingFaceModelSummary>> = withContext(Dispatchers.IO) {
    val trimmed = query.trim()
    val searchParam = if (trimmed.isBlank()) "gguf" else "$trimmed gguf"
    val encoded = URLEncoder.encode(searchParam, "UTF-8")
    val url = "https://huggingface.co/api/models?search=$encoded&sort=downloads&direction=-1&limit=30"
    val client = HttpClient(Android)
    try {
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            return@withContext Result.failure(Exception("Hugging Face API xatosi (${response.status.value})"))
        }
        val text = response.bodyAsText()
        val parsed = Json.parseToJsonElement(text).jsonArray
        val results = mutableListOf<HuggingFaceModelSummary>()
        for (item in parsed) {
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            val downloads = obj["downloads"]?.jsonPrimitive?.longOrNull ?: 0L
            val likes = obj["likes"]?.jsonPrimitive?.intOrNull ?: 0
            val author = if (id.contains("/")) id.substringBefore("/") else "HuggingFace"
            val name = if (id.contains("/")) id.substringAfter("/") else id
            val cat = when {
                "coder" in id.lowercase() || "code" in id.lowercase() -> ModelCategory.CODING
                "r1" in id.lowercase() || "reason" in id.lowercase() -> ModelCategory.REASONING
                "translate" in id.lowercase() || "gemma" in id.lowercase() -> ModelCategory.TRANSLATION
                else -> ModelCategory.CHAT
            }
            results.add(
                HuggingFaceModelSummary(
                    id = id,
                    author = author,
                    name = name,
                    downloads = downloads,
                    likes = 0,
                    description = "$author tomonidan ochiq GGUF modeli",
                    category = cat
                )
            )
        }
        Result.success(results)
    } catch (e: Exception) {
        val isNetworkError = e is java.net.UnknownHostException ||
                e.javaClass.simpleName.contains("UnknownHost", ignoreCase = true) ||
                e.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                e.message?.contains("failed to connect", ignoreCase = true) == true
        if (isNetworkError) {
            Result.failure(Exception("Internet aloqasi yo'q. Qidirish uchun internetni yoqing."))
        } else {
            Result.failure(e)
        }
    } finally {
        client.close()
    }
}

suspend fun fetchHuggingFaceGgufFiles(repoIdOrUrl: String): Result<List<HuggingFaceGgufFile>> = withContext(Dispatchers.IO) {
    val repoId = parseHuggingFaceRepoId(repoIdOrUrl)
        ?: return@withContext Result.failure(IllegalArgumentException("Hugging Face repo formati noto'g'ri (masalan: TheBloke/Llama-2-7B-Chat-GGUF)"))
    val apiUrl = "https://huggingface.co/api/models/$repoId/tree/main"
    val client = HttpClient(Android)
    try {
        val response = client.get(apiUrl)
        if (!response.status.isSuccess()) {
            val fallback = getCuratedFallbackQuants(repoId)
            if (fallback.isNotEmpty()) return@withContext Result.success(fallback)
            return@withContext Result.failure(Exception("Hugging Face API xatosi (${response.status.value}). Repo mavjudligini tekshiring."))
        }
        val text = response.bodyAsText()
        val parsed = Json.parseToJsonElement(text).jsonArray
        val ggufFiles = mutableListOf<HuggingFaceGgufFile>()
        for (item in parsed) {
            val obj = item.jsonObject
            val path = obj["path"]?.jsonPrimitive?.content ?: continue
            if (path.endsWith(".gguf", ignoreCase = true)) {
                val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val dlUrl = "https://huggingface.co/$repoId/resolve/main/$path"
                ggufFiles.add(HuggingFaceGgufFile(filename = path, downloadUrl = dlUrl, sizeBytes = size))
            }
        }
        if (ggufFiles.isEmpty()) {
            val fallback = getCuratedFallbackQuants(repoId)
            if (fallback.isNotEmpty()) return@withContext Result.success(fallback)
            return@withContext Result.failure(Exception("Ushbu Hugging Face omborida .gguf fayllar topilmadi."))
        }
        Result.success(ggufFiles)
    } catch (e: Exception) {
        val fallback = getCuratedFallbackQuants(repoId)
        if (fallback.isNotEmpty()) {
            return@withContext Result.success(fallback)
        }
        val isNetworkError = e is java.net.UnknownHostException ||
                e.javaClass.simpleName.contains("UnknownHost", ignoreCase = true) ||
                e.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                e.message?.contains("failed to connect", ignoreCase = true) == true
        if (isNetworkError) {
            Result.failure(Exception("Internet aloqasi mavjud emas yoki Hugging Face'ga ulanib bo'lmadi. Iltimos, internet yoki Wi-Fi ulanishini tekshiring."))
        } else {
            Result.failure(e)
        }
    } finally {
        client.close()
    }
}

suspend fun importLocalGgufFile(context: Context, uri: Uri, customName: String): Result<ModelItem> = withContext(Dispatchers.IO) {
    try {
        val contentResolver = context.contentResolver
        var displayName = customName.trim()
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && displayName.isBlank()) {
                    val n = it.getString(nameIdx)
                    if (!n.isNullOrBlank()) displayName = n.removeSuffix(".gguf")
                }
            }
        }
        if (displayName.isBlank()) displayName = "Local-Model-" + (System.currentTimeMillis() % 10000)

        val safeId = "custom_" + System.currentTimeMillis()
        val destDir = modelsDir(context)
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, "$safeId.gguf")

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: return@withContext Result.failure(Exception("Faylni o'qib bo'lmadi."))

        val actualSizeMb = (destFile.length() / (1024 * 1024)).coerceAtLeast(1)
        val family = inferModelFamily(displayName)
        val params = inferParamsBillion(displayName)
        val model = ModelItem(
            id = safeId,
            name = displayName,
            downloadUrl = "",
            direction = "Qurilmadan qo'shilgan model",
            category = ModelCategory.CHAT,
            baseSizeMb = actualSizeMb,
            baseRamMb = (actualSizeMb * 1.3).toLong().coerceAtLeast(800),
            minPhoneClass = "Qurilmaga bog'liq",
            family = family,
            paramsBillion = params
        )
        CustomModelStore.add(context, model)
        ModelStarStore.recordDownload(context, safeId)
        Result.success(model)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
