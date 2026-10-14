package com.mobagent.app.engine

import com.mobagent.app.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * llama.cpp ga JNI ko'prigi.
 * Native manba: app/src/main/cpp/llama-android.cpp
 */
class LlamaEngine {

    companion object {
        init {
            System.loadLibrary("llama-android")
        }
    }

    private var contextPtr: Long = 0L

    // llama_context bir vaqtning o'zida faqat BITTA decode/generate
    // chaqiruvini xavfsiz ko'taradi. Ilgari bu yerda hech qanday himoya yo'q
    // edi — agar ikkita generateStream() chaqiruvi bir-biriga ustma-ust
    // tushib qolsa (masalan, "Yuborish" tugmasi tez-tez bosilib, UI hali
    // "band" holatga o'tib ulgurmasdan), ikkalasi bitta native context'ga
    // bir vaqtda kirib, hech qanday xato bermay ABADIY OSILIB QOLARDI
    // ("AI yozmoqda..." to'xtamasdi). Endi ikkinchi chaqiruv birinchisi
    // tugagunicha shu yerda kutadi.
    private val generationMutex = Mutex()

    external fun nativeInit(modelPath: String, nThreads: Int, nCtx: Int): Long
    // mode 0 = oddiy suhbat, 1 = agent (grammar bo'yicha cheklangan generatsiya).
    external fun nativeGenerate(contextPtr: Long, prompt: String, callback: TokenCallback, mode: Int, grammar: String)
    external fun nativeCancel(contextPtr: Long)
    external fun nativeClose(contextPtr: Long)
    // System-prompt KV-kesh holatini oldindan hisoblab, diskka saqlash (warmup)
    // va keyingi suhbatlarda o'sha holatdan boshlash uchun. Batafsil:
    // ModelPromptStrategy.systemOnlyPrefix() kdoc'iga qarang.
    external fun nativeWarmupAndSave(contextPtr: Long, prefixPrompt: String, path: String): Boolean
    external fun nativeLoadState(contextPtr: Long, path: String): Boolean

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    /**
     * Model faylini yuklaydi. Fayl buzilgan, formatga mos kelmagan yoki
     * xotira yetarli bo'lmagan hollarda native tomon 0 qaytaradi — bu yerda
     * shuni ushlab, chaqiruvchiga (UI) aniq xato sifatida uzatamiz. Ilgari
     * bu holat e'tiborsiz qoldirilib, isModelReady abadiy false bo'lib qolardi.
     *
     * @param nCtx kontekst oynasi hajmi (token). Ilgari 4096'ga qattiq
     * kodlangan edi — endi ModelStoreScreen'dagi "Standart / Kengaytirilgan"
     * tanloviga qarab chaqiriladi (ContextMode.kt'ga qarang).
     * @param nThreads generatsiya uchun ishlatiladigan CPU tred soni. Ilgari
     * qurilma yadrolar sonidan qat'iy nazar QATTIQ 4'ga kodlangan edi —
     * ko'pchilik zamonaviy telefonlarda 6-8 yadro bor, shuning uchun bu
     * javobni sezilarli sekinlashtirardi. Keyin standart qiymat oddiy
     * "barcha yadro - 1" qilib qo'yilgan edi — lekin bu big.LITTLE
     * protsessorlarda (kuchli+kuchsiz yadrolar aralash) yana noto'g'ri:
     * sekin yadro tezkorlarni har qadamda kutdirib, umumiy tezlikni
     * pasaytiradi. Endi CpuTopology yordamida faqat eng tezkor klaster
     * yadrolari soni ishlatiladi (CpuTopology.kt'ga qarang).
     */
    fun loadModel(
        modelPath: String,
        nThreads: Int = CpuTopology.performantThreadCount(),
        nCtx: Int = 4096
    ) {
        // Xavfsizlik: agar shu engine'da eski model allaqachon yuklangan bo'lsa
        // (masalan, "Qayta urinish" bosilib, qisman muvaffaqiyatli holatdan
        // keyin qayta chaqirilsa), avval uni bo'shatamiz — aks holda eski
        // contextPtr xotirada "yetim" qolib, xotira sarfi ikki barobar oshib
        // ketardi (zaif telefonlarda bu OOM — xotira yetishmasligi tufayli
        // butun ilova o'chib qolishiga olib kelishi mumkin edi).
        if (contextPtr != 0L) {
            nativeClose(contextPtr)
            contextPtr = 0L
        }
        val ptr = nativeInit(modelPath, nThreads, nCtx)
        if (ptr == 0L) {
            throw IllegalStateException(
                tr("Model yuklanmadi. Fayl buzilgan, noto'g'ri formatda yoki qurilma xotirasi yetarli emas bo'lishi mumkin: %1\$s", modelPath)
            )
        }
        contextPtr = ptr
    }

    /**
     * System promptning KV-kesh holatini `path` fayldan tiklashga urinadi.
     * Muvaffaqiyatli bo'lsa, keyingi generateStream() chaqiruvi bu qismni
     * QAYTA HISOBLAMAYDI — chunki native tomon (`lastSequence`) allaqachon
     * shu tokenlar bilan to'ldirilgan bo'ladi va umumiy prefiks sifatida
     * tan olinadi.
     *
     * Fayl mavjud emas, buzilgan yoki model/nCtx mos kelmasa — xavfsiz
     * ravishda `false` qaytadi, hech qanday xato tashlanmaydi (bu shunchaki
     * "keshda hali yo'q" degani, birinchi generatsiya odatdagidek davom etadi).
     */
    fun tryRestoreCachedState(path: String): Boolean {
        if (contextPtr == 0L) return false
        return try {
            nativeLoadState(contextPtr, path)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * `prefixPrompt`ni (odatda ModelPromptStrategy.systemOnlyPrefix()) bir
     * marta decode qilib, KV-kesh holatini `path` fayliga saqlaydi. Bu
     * funksiya faqat tryRestoreCachedState() muvaffaqiyatsiz bo'lganda
     * (ya'ni hali kesh mavjud emasligida) chaqirilishi kerak — aks holda
     * allaqachon tiklangan holatni bekor qilib qo'yadi.
     */
    fun warmupSystemPromptAndCache(prefixPrompt: String, path: String): Boolean {
        if (contextPtr == 0L) return false
        return try {
            nativeWarmupAndSave(contextPtr, prefixPrompt, path)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Warmup'ning FONDA (UI'ni bloklamaydigan) versiyasi. Ilgari warmup model
     * yuklanish bosqichining ichida edi: telefon sekin bo'lsa, system promptni
     * (minglab token) decode qilish daqiqalar olib, ekran "Loading model into
     * memory..." holatida qotib turardi. Endi chat darhol ochiladi; warmup
     * generationMutex ichida ishlaydi, shuning uchun foydalanuvchi xabar
     * yuborsa, generatsiya warmup tugagach navbat bilan boshlanadi (native
     * context bir vaqtda faqat bitta chaqiruvni ko'taradi).
     */
    suspend fun warmupSystemPromptAndCacheAsync(prefixPrompt: String, path: String): Boolean =
        generationMutex.withLock {
            if (contextPtr == 0L) return@withLock false
            try {
                nativeWarmupAndSave(contextPtr, prefixPrompt, path)
            } catch (e: Exception) {
                false
            }
        }

    /**
     * @param grammar GBNF grammatika (ToolGrammar.GRAMMAR). Berilsa, model faqat
     * grammatikaga mos matn yoza oladi (yaroqli tool-call JSON yoki oddiy javob)
     * va JSON qismida past temperatura ishlatiladi. null bo'lsa, oddiy suhbat
     * sozlamasi (temp 0.7, takror jazosi) qo'llanadi.
     */
    fun generateStream(prompt: String, grammar: String? = null): Flow<String> = callbackFlow {
        generationMutex.withLock {
            // MUHIM TUZATISH: ilgari shu yerda trySend(token) ishlatilgan edi.
            // callbackFlow'ning ichki kanali cheklangan buferga ega — agar
            // UI (Compose) tokenlarni yetarlicha tez iste'mol qila olmasa
            // (masalan bir vaqtning o'zida boshqa ishlar bilan band bo'lsa),
            // bufer to'lib, trySend HECH QANDAY XATOSIZ, JIMGINA keyingi
            // tokenlarni tashlab yuborardi. Natijada native tomon "generatsiya
            // muvaffaqiyatli tugadi" deb to'g'ri log yozsa ham, foydalanuvchi
            // javobning bir qismini (yoki uzoq javoblarda ko'pini) umuman
            // ko'rmasdi — xuddi "javob kelmagan"day tuyulardi.
            // send() esa navbat bo'shamaguncha KUTADI (hech narsani tashlamaydi);
            // buni native callback'dan (suspend funksiya emas) chaqirish uchun
            // runBlocking bilan o'raymiz — bu xavfsiz, chunki biz allaqachon
            // fon (IO) tredidamiz va shu tred baribir native chaqiruv bilan band.
            val callback = TokenCallback { token -> runBlocking { send(token) } }
            // nativeGenerate sinxron (bloklovchi) chaqiruv, shuning uchun uni
            // flowOn(Dispatchers.IO) orqali fon oqimiga chiqaramiz — aks holda
            // chaqiruvchi (odatda UI) tred bloklanib, "to'xtatish" tugmasi ham
            // bosilmay qolardi.
            nativeGenerate(contextPtr, prompt, callback, if (grammar != null) 1 else 0, grammar.orEmpty())
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /** Joriy generatsiyani to'xtatadi (native sikl keyingi tekshiruvda chiqadi). */
    fun cancel() {
        if (contextPtr != 0L) nativeCancel(contextPtr)
    }

    fun close() {
        if (contextPtr != 0L) {
            nativeClose(contextPtr)
            contextPtr = 0L
        }
    }
}
