#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <atomic>
#include <algorithm>
#include <mutex>
#include <chrono>
#include <android/log.h>
#include <dlfcn.h>
#include <libgen.h>
#include "llama.h"
#include "ggml-backend.h"

#define TAG "LlamaEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Bitta generatsiyada yaratiladigan token'lar soni chegarasi
// (cheksiz sikl / xotira toshib ketishining oldini olish uchun).
static constexpr int32_t MAX_NEW_TOKENS = 512;
// Ilgari N_CTX=4096 qattiq kodlangan edi. Endi nativeInit'ga jint sifatida
// uzatiladi (ModelStoreScreen'dagi "Standart / Kengaytirilgan" tanlovi —
// ContextMode.kt) va xavfsizlik uchun shu chegaralarda ushlab turiladi.
static constexpr int32_t MIN_CTX = 512;
static constexpr int32_t MAX_CTX = 16384;

struct EngineContext {
    llama_model        *model = nullptr;
    llama_context       *ctx  = nullptr;
    const llama_vocab   *vocab = nullptr;
    int32_t              nCtx = 4096;
    // MUHIM TUZATISH (tezlik): oldingi chaqiruvda KV kesh'da qolgan to'liq
    // token ketma-ketligi (prompt + yaratilgan javob). Chat tarixi har doim
    // FAQAT OXIRIGA QO'SHILGANI uchun (ModelPromptStrategy.buildPrompt hech
    // qachon eski xabarlarni o'zgartirmaydi), keyingi chaqiruvning yangi
    // prompt'i deyarli har doim shu ketma-ketlikning DAVOMI bo'ladi. Shuni
    // solishtirib, faqat YANGI (farq qiluvchi) qismini qayta decode qilish
    // mumkin — butun suhbat tarixini har safar boshidan hisoblash SHART
    // EMAS. Batafsil: nativeGenerate ichidagi "KV-kesh qayta ishlatish"
    // izohiga qarang.
    std::vector<llama_token> lastSequence;
    // Foydalanuvchi "to'xtatish" tugmasini bossa, generatsiya sikli shu flag'ni
    // ko'rib chiqib to'xtaydi. Native tomon uzun sinxron sikl bo'lgani uchun
    // faqat ishora (flag) orqali "so'nggi to'qnashuvsiz" to'xtatish mumkin.
    std::atomic<bool>   cancelRequested{false};
};

// MUHIM TUZATISH ("Stop tugmasi ishlamayapti" xatosi uchun): ilgari
// cancelRequested faqat har bir llama_decode() CHAQIRUVIDAN OLDIN
// tekshirilardi. Agar aynan o'sha bitta chaqiruv (masalan, uzun promptni
// bir yo'la qayta ishlash) juda uzoq davom etsa, uning ICHIDA to'xtatib
// bo'lmasdi — foydalanuvchi "Stop" bossa ham hech narsa o'zgarmasdi.
// llama.cpp'ning o'zi buning uchun rasmiy mexanizm beradi: abort_callback —
// bu funksiya llama_decode() ICHIDA, hisoblash davomida davriy ravishda
// chaqirib turiladi; true qaytarsa, decode DARHOL, o'rtadan to'xtaydi.
static bool llamaAbortCallback(void *data) {
    auto *cancelFlag = reinterpret_cast<std::atomic<bool> *>(data);
    return cancelFlag != nullptr && cancelFlag->load();
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_mobagent_app_engine_LlamaEngine_nativeInit(
        JNIEnv *env, jobject /* this */,
        jstring modelPath, jint nThreads, jint nCtx) {

    const char *cModelPath = env->GetStringUTFChars(modelPath, nullptr);

    // MUHIM TUZATISH: llama_backend_init()/llama_backend_free() ILGARI HAR
    // BIR model yuklanganda/yopilganda chaqirilardi. Bular esa PROSESS
    // darajasidagi GLOBAL holatni boshqaradi (ggml backend registri) — bir
    // martagina chaqirilishi kerak. Foydalanuvchi bir necha marta model
    // ochib-yopganda (masalan, orqaga qaytib qayta tanlaganda), ESKI
    // ekranning "yopish" chaqiruvi (llama_backend_free) YANGI ekranning
    // "ochish" chaqiruvidan (llama_backend_init) keyin ishga tushib qolsa —
    // yangi context tayanadigan global backend kutilmaganda o'chib ketardi.
    // Bu esa aynan vaqt-bog'liq, tasodifiy "goh ishlaydi, goh osilib qoladi"
    // xatti-harakatga sabab bo'lardi. Endi backend jarayon davomida FAQAT
    // BIR MARTA ishga tushiriladi va hech qachon (model yopilganda ham)
    // o'chirilmaydi — bu llama.cpp'ning o'zi tavsiya qilgan usul.
    static std::once_flag backendInitFlag;
    std::call_once(backendInitFlag, []() {
        // CPU backend variantlari (libggml-cpu-*.so) alohida modul sifatida
        // quriladi — ularni shu kutubxona (libllama-android.so) joylashgan
        // papkadan (nativeLibraryDir) yuklaymiz; ggml telefon protsessoriga
        // eng mos variantni o'zi tanlaydi.
        Dl_info info;
        if (dladdr((void *) &llama_backend_init, &info) && info.dli_fname) {
            std::string path(info.dli_fname);
            std::string dir = path.substr(0, path.find_last_of('/'));
            ggml_backend_load_all_from_path(dir.c_str());
            LOGI("ggml backend'lar yuklandi: %s (soni=%zu)", dir.c_str(), ggml_backend_reg_count());
        } else {
            ggml_backend_load_all();
            LOGE("dladdr ishlamadi — standart yo'l bilan yuklandi");
        }
        llama_backend_init();
        LOGI("llama backend ishga tushirildi (faqat 1 marta, jarayon davomida)");
    });

    llama_model_params mparams = llama_model_default_params();
    // Vulkan backend kompilyatsiya qilingan bo'lsa, mavjud GPU'ga imkon
    // qadar ko'p qatlamni yuklaydi (tezlashtirish uchun). Agar qurilmada
    // mos Vulkan drayveri topilmasa, ggml buni avtomatik e'tiborsiz
    // qoldirib, xavfsiz tarzda CPU'ga qaytadi — shuning uchun bu qiymatni
    // doim baland qo'yish xavfsiz.
    mparams.n_gpu_layers = 999;
    llama_model *model = llama_model_load_from_file(cModelPath, mparams);
    env->ReleaseStringUTFChars(modelPath, cModelPath);

    if (!model) {
        LOGE("Model yuklanmadi");
        return 0;
    }

    const llama_vocab *vocab = llama_model_get_vocab(model);

    const int32_t effectiveCtx = std::max(MIN_CTX, std::min(MAX_CTX, (int32_t) nCtx));

    // EngineContext CONTEXT yaratilishidan OLDIN ajratiladi — shunda uning
    // cancelRequested manzilini abort_callback_data sifatida darhol uzatib
    // bo'ladi (pastga qarang).
    auto *engineCtx = new EngineContext();
    engineCtx->model = model;
    engineCtx->vocab = vocab;
    engineCtx->nCtx = effectiveCtx;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = effectiveCtx;
    cparams.n_batch = effectiveCtx;
    // MUHIM TUZATISH: n_ubatch (bitta hisoblash grafigi/qadam hajmi) ni
    // ilgari n_ctx'ga (masalan, 4096) tenglashtirgan edik — bu XATO edi.
    // n_batch (chaqiruv chegarasi) katta bo'lishi kerak edi, lekin n_ubatch
    // katta bo'lishi SHART EMAS: llama_decode() katta batch'ni o'zi ichki
    // ravishda kichik ubatch'larga bo'lib qayta ishlaydi. n_ubatch'ni ham
    // 4096'ga qo'yish har bir "salom" kabi qisqa xabar uchun ham ulkan
    // hisoblash buferini ajratishga majbur qilardi — zaif telefonlarda bu
    // amalda abadiy osilib qolganday tuyulardi (xato bermay, juda sekinlashadi).
    cparams.n_ubatch = std::min(effectiveCtx, 512);
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    // Endi "Stop" llama_decode() ICHIDA ham darhol ishlaydi (yuqoridagi izohga qarang).
    cparams.abort_callback = llamaAbortCallback;
    cparams.abort_callback_data = &engineCtx->cancelRequested;

    // TEZLIK/XOTIRA: Flash Attention + 8-bit KV-kesh. KV-kesh f16 dan q8_0 ga
    // o'tsa, kontekst xotirasi ~2 baravar kamayadi (3B+ modellarda telefonning
    // qotib qolishini kamaytiradi) va uzun suhbatlarda attention tezroq bo'ladi.
    // Qisqa kontekstda (<4096) foyda kam, shuning uchun faqat katta kontekstda yoqamiz.
    // V-kesh kvantizatsiyasi Flash Attention'ni talab qiladi: AUTO uni model
    // qo'llasa yoqadi. Agar kombinatsiya ishlamasa — pastda f16 ga qaytamiz.
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    const bool quantizeKv = effectiveCtx >= 4096;
    if (quantizeKv) {
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx && quantizeKv) {
        LOGE("q8_0 KV-kesh bilan context yaratilmadi — f16 ga qaytilmoqda");
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        ctx = llama_init_from_model(model, cparams);
    }
    if (!ctx) {
        LOGE("Context yaratilmadi (n_ctx=%d) — ehtimol xotira yetarli emas", effectiveCtx);
        llama_model_free(model);
        delete engineCtx;
        return 0;
    }
    engineCtx->ctx = ctx;

    LOGI("Model yuklandi: n_ctx=%d, n_threads=%d, kv=%s", effectiveCtx, nThreads,
         (cparams.type_k == GGML_TYPE_Q8_0) ? "q8_0" : "f16");
    return reinterpret_cast<jlong>(engineCtx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mobagent_app_engine_LlamaEngine_nativeGenerate(
        JNIEnv *env, jobject /* this */,
        jlong contextPtr, jstring prompt, jobject callback, jint mode, jstring grammar) {

    auto *engineCtx = reinterpret_cast<EngineContext *>(contextPtr);
    if (!engineCtx || !engineCtx->ctx || !engineCtx->vocab) {
        LOGE("Context ishga tushmagan");
        return;
    }
    // mode: 0 = oddiy suhbat (grammatikasiz, eski sozlama),
    //       1 = agent (GBNF grammatika + JSON qismida past temperatura).
    // Grammatika bo'sh yoki yaroqsiz bo'lsa, agent rejimi ham oddiy suhbatga tushadi.
    std::string grammarStr;
    if (mode == 1 && grammar != nullptr) {
        const char *cg = env->GetStringUTFChars(grammar, nullptr);
        if (cg) { grammarStr = cg; env->ReleaseStringUTFChars(grammar, cg); }
    }

    llama_context *ctx = engineCtx->ctx;
    const llama_vocab *vocab = engineCtx->vocab;
    engineCtx->cancelRequested.store(false);

    using clock = std::chrono::steady_clock;
    auto t0 = clock::now();
    auto elapsedMs = [&](clock::time_point from) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(clock::now() - from).count();
    };

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");

    const char *cPrompt = env->GetStringUTFChars(prompt, nullptr);
    const int32_t promptLen = (int32_t) strlen(cPrompt);

    // 1) TOKENIZE: promptni token'larga aylantiramiz.
    int32_t n_prompt = -llama_tokenize(vocab, cPrompt, promptLen, nullptr, 0, true, true);
    if (n_prompt <= 0) {
        LOGE("Tokenize muvaffaqiyatsiz tugadi");
        env->ReleaseStringUTFChars(prompt, cPrompt);
        return;
    }

    std::vector<llama_token> promptTokens(n_prompt);
    if (llama_tokenize(vocab, cPrompt, promptLen, promptTokens.data(), n_prompt, true, true) < 0) {
        LOGE("Tokenize (2-chaqiruv) muvaffaqiyatsiz tugadi");
        env->ReleaseStringUTFChars(prompt, cPrompt);
        return;
    }
    env->ReleaseStringUTFChars(prompt, cPrompt);
    LOGI("Tokenize tugadi: %d token (+%lld ms)", n_prompt, (long long) elapsedMs(t0));

    if (n_prompt >= engineCtx->nCtx - 4) {
        LOGE("Prompt juda uzun (%d token), kontekst hajmi %d", n_prompt, engineCtx->nCtx);
        return;
    }

    // MUHIM TUZATISH (tezlik): ILGARI bu yerda HAR DOIM
    // llama_memory_clear(...) chaqirilib, butun suhbat tarixi (tizim
    // ko'rsatmasi + barcha oldingi xabarlar + yangi xabar) HAR BIR YANGI
    // xabar uchun qaytadan, boshidan decode qilinardi. Suhbat uzunlashgan
    // sayin bu chaqiruv asta-sekin SEKINLASHIB BORARDI — 10-xabarda
    // javob kutish 1-xabardagidan bir necha barobar uzoqroq bo'lardi.
    //
    // Endi: oldingi chaqiruvdan KV kesh'da qolgan ketma-ketlik
    // (engineCtx->lastSequence) bilan YANGI promptning UMUMIY BOSHINI
    // (common prefix) topamiz. Chat tarixi faqat oxiriga qo'shilgani
    // uchun bu deyarli har doim juda katta bo'ladi — faqat ENG SO'NGGI
    // foydalanuvchi xabari "yangi" hisoblanadi. Faqat shu farq qiluvchi
    // qismini decode qilamiz, qolgan hammasi kesh'dan tekin olinadi.
    size_t commonPrefix = 0;
    {
        const size_t maxCompare = std::min(engineCtx->lastSequence.size(), promptTokens.size());
        while (commonPrefix < maxCompare &&
               engineCtx->lastSequence[commonPrefix] == promptTokens[commonPrefix]) {
            commonPrefix++;
        }
    }
    // Oxirgi umumiy tokenni solishtirmaymiz — ehtimol u qisman/noto'liq
    // bo'lgan bo'lishi mumkin (masalan avvalgi generatsiya "to'xtatish"
    // bilan kesilgan bo'lsa). Xavfsizlik uchun bittaga kamaytiramiz.
    if (commonPrefix > 0 && commonPrefix == engineCtx->lastSequence.size() &&
        commonPrefix == promptTokens.size()) {
        commonPrefix--;
    }

    auto *memory = llama_get_memory(ctx);
    if (commonPrefix < 8) {
        // Umumiylik deyarli yo'q (masalan birinchi xabar, yoki tarix
        // siqilib/o'zgarib ketgan) — to'liq tozalash arzonroq va xavfsizroq.
        llama_memory_clear(memory, true);
        commonPrefix = 0;
        LOGI("KV-kesh: to'liq tozalandi (umumiylik yo'q) (+%lld ms)", (long long) elapsedMs(t0));
    } else if (commonPrefix < engineCtx->lastSequence.size()) {
        // Umumiy prefiksdan KEYINGI (endi eskirgan) qismini kesh'dan olib
        // tashlaymiz — umumiy qismning o'zi (va uning KV holati) saqlanib
        // qoladi.
        llama_memory_seq_rm(memory, 0, (llama_pos) commonPrefix, -1);
        LOGI("KV-kesh: %zu/%zu token qayta ishlatildi (+%lld ms)",
             commonPrefix, promptTokens.size(), (long long) elapsedMs(t0));
    } else {
        LOGI("KV-kesh: %zu/%zu token qayta ishlatildi (+%lld ms)",
             commonPrefix, promptTokens.size(), (long long) elapsedMs(t0));
    }

    LOGI("Generatsiya boshlandi: %d token prompt (%zu yangisi decode qilinadi), max %d yangi token",
         n_prompt, promptTokens.size() - commonPrefix, MAX_NEW_TOKENS);

    // 2) SAMPLER: navbatdagi token'ni qanday tanlashni belgilaymiz.
    //
    // Ikki xil sozlama (chain) bor va ular generatsiya davomida almashadi:
    //  - textChain: oddiy matn (suhbat javobi, buyruqdan oldingi qisqa gap).
    //    Eski sozlama: repeat_penalty 1.15 (kichik modellar sikllanib qolmasin),
    //    temp 0.7.
    //  - jsonChain: buyruq JSON'i. Bu yerda `"`, `{`, `:` kabi belgilar tabiiy
    //    ravishda takrorlanadi, shuning uchun takror jazosi YO'Q va temp 0.2.
    // Chat rejimida faqat textChain ishlaydi. Agent rejimida chiqishning
    // qatordan boshlangan '{' belgisi ko'ringandan keyin jsonChain'ga o'tiladi.
    // Grammatika (GBNF) alohida sampler: u har qadamda avval yaroqsiz
    // token'larni yopadi, keyin faol chain tanlaydi. Grammatikaning holati
    // bitta joyda (gram) yuritiladi, shuning uchun chain'lar orasida
    // almashish uni buzmaydi.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();

    llama_sampler *textChain = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(textChain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.15f, 0.0f, 0.0f));
    llama_sampler_chain_add(textChain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(textChain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(textChain, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(textChain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_sampler *jsonChain = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(jsonChain, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(jsonChain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(jsonChain, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(jsonChain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_sampler *gram = nullptr;
    if (!grammarStr.empty()) {
        gram = llama_sampler_init_grammar(vocab, grammarStr.c_str(), "root");
        if (!gram) {
            LOGE("Grammatika yaroqsiz — oddiy (grammatikasiz) rejimga qaytildi");
        } else {
            LOGI("Grammatika yoqildi (%zu bayt)", grammarStr.size());
        }
    }
    const bool agentMode = (gram != nullptr);
    bool inJson = false;
    std::string outText;

    const int32_t nVocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> candidates((size_t) nVocab);

    // Faqat KESH'DA YO'Q (yangi) qismini decode qilamiz — commonPrefix
    // tokengacha bo'lgan qism kesh'dan tekin olinadi (yuqoridagi izohga qarang).
    llama_batch batch = llama_batch_get_one(
        promptTokens.data() + commonPrefix,
        (int32_t) (promptTokens.size() - commonPrefix)
    );

    int32_t nPos = (int32_t) commonPrefix;
    int32_t nDecoded = 0;
    char pieceBuf[256];
    // Yaratilgan tokenlarni ham kuzatib boramiz — funksiya oxirida
    // KV kesh'da haqiqatda nima qolganini (engineCtx->lastSequence)
    // aniq belgilash uchun kerak (pastga qarang).
    std::vector<llama_token> generatedTokens;

    while (true) {
        if (engineCtx->cancelRequested.load()) {
            LOGI("Generatsiya foydalanuvchi tomonidan to'xtatildi (pos=%d, +%lld ms)", nPos, (long long) elapsedMs(t0));
            break;
        }

        // 3) DECODE: batch'ni modelga uzatamiz (prompt yoki oldingi token).
        // DIAGNOSTIKA: birinchi chaqiruv (agar kesh'dan qayta ishlatish kam
        // bo'lsa) ko'p tokenni bir yo'la qayta ishlaydi — sekin/eski
        // telefonlarda yoki mmap orqali diskdan (ayniqsa tashqi/umumiy
        // xotiradan, FUSE qatlami orqali) og'irlik o'qilayotgan bo'lsa,
        // aynan SHU chaqiruv bir necha o'nlab soniya olishi mumkin. Shu
        // sababli buni alohida o'lchaymiz.
        const bool isPromptDecode = (nDecoded == 0);
        auto decodeStart = clock::now();
        const int decodeRc = llama_decode(ctx, batch);
        if (isPromptDecode) {
            LOGI("Prompt decode tugadi: %d token (+%lld ms, jami +%lld ms)",
                 batch.n_tokens, (long long) elapsedMs(decodeStart), (long long) elapsedMs(t0));
        }
        if (decodeRc) {
            LOGE("llama_decode muvaffaqiyatsiz tugadi (pos=%d, +%lld ms)", nPos, (long long) elapsedMs(t0));
            break;
        }
        nPos += batch.n_tokens;

        // 4) SAMPLE: ehtimolliklar asosida keyingi token tanlanadi.
        llama_token newTokenId;
        {
            const float *logits = llama_get_logits_ith(ctx, -1);
            for (int32_t i = 0; i < nVocab; i++) {
                candidates[i] = llama_token_data{ i, logits[i], 0.0f };
            }
            llama_token_data_array arr = { candidates.data(), candidates.size(), -1, false };
            if (gram) llama_sampler_apply(gram, &arr);
            llama_sampler_apply(inJson ? jsonChain : textChain, &arr);
            newTokenId = arr.data[arr.selected].id;
            if (gram) llama_sampler_accept(gram, newTokenId);
            llama_sampler_accept(textChain, newTokenId);
            llama_sampler_accept(jsonChain, newTokenId);
        }

        if (llama_vocab_is_eog(vocab, newTokenId)) {
            break; // model javobni tugatdi (EOS/EOT)
        }

        // 5) DETOKENIZE: tanlangan token matn bo'lagiga aylantiriladi.
        int32_t n = llama_token_to_piece(vocab, newTokenId, pieceBuf, sizeof(pieceBuf), 0, true);
        if (n < 0) {
            LOGE("Token matnga aylantirilmadi");
            break;
        }

        std::string piece(pieceBuf, n);
        if (agentMode && !inJson) {
            const size_t base = outText.size();
            outText += piece;
            for (size_t k = 0; k < piece.size(); k++) {
                if (piece[k] == '{' && (base + k == 0 || outText[base + k - 1] == '\n')) {
                    inJson = true;
                    break;
                }
            }
        }
        jstring jtok = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, onToken, jtok);
        env->DeleteLocalRef(jtok);

        generatedTokens.push_back(newTokenId);
        nDecoded++;
        if (nDecoded % 20 == 0) {
            LOGI("...%d token yaratildi (+%lld ms)", nDecoded, (long long) elapsedMs(t0));
        }
        if (nDecoded >= MAX_NEW_TOKENS) {
            LOGI("MAX_NEW_TOKENS (%d) yetdi, generatsiya to'xtatildi", MAX_NEW_TOKENS);
            break;
        }
        if (nPos >= engineCtx->nCtx - 4) {
            LOGI("Kontekst deyarli to'ldi, generatsiya to'xtatildi");
            break;
        }

        // Navbatdagi decode uchun faqat yangi token yuboriladi (KV-cache saqlanadi).
        batch = llama_batch_get_one(&newTokenId, 1);
    }

    // KV kesh'da HAQIQATDA qolgan ketma-ketlikni saqlab qo'yamiz — nPos
    // aniq qancha token muvaffaqiyatli decode qilinganini bildiradi
    // (decode xatosi/bekor qilish/EOS qayerda bo'lishidan qat'iy nazar).
    // Keyingi chaqiruv shu bilan solishtiradi (yuqoridagi "KV-kesh qayta
    // ishlatish" izohiga qarang).
    if ((size_t) nPos <= promptTokens.size()) {
        engineCtx->lastSequence.assign(promptTokens.begin(), promptTokens.begin() + nPos);
    } else {
        engineCtx->lastSequence = promptTokens;
        const size_t genCount = (size_t) nPos - promptTokens.size();
        engineCtx->lastSequence.insert(
            engineCtx->lastSequence.end(),
            generatedTokens.begin(),
            generatedTokens.begin() + std::min(genCount, generatedTokens.size())
        );
    }

    if (gram) llama_sampler_free(gram);
    llama_sampler_free(textChain);
    llama_sampler_free(jsonChain);
    LOGI("Generatsiya tugadi: %d token yaratildi (+%lld ms)", nDecoded, (long long) elapsedMs(t0));
}

// MUHIM (tezlik): #3 — system promptning KV-kesh holatini oldindan
// hisoblab, diskka saqlash. Yuqoridagi "KV-kesh qayta ishlatish" mexanizmi
// (lastSequence bilan solishtirish) faqat BITTA suhbat sessiyasi ICHIDA
// ishlaydi — model yopilib qayta yuklansa (yoki yangi suhbat boshlansa),
// lastSequence bo'sh boshlanadi va system prompt yana noldan decode
// qilinadi. Bu ikkala funksiya llama.cpp'ning rasmiy
// llama_state_save_file/llama_state_load_file API'sidan foydalanib, shu
// bitta (deyarli hech qachon o'zgarmaydigan) qismni FAYLGA saqlab qo'yadi —
// keyingi HAR bir yangi suhbat shu holatdan boshlanadi, system promptni
// qayta decode qilish shart bo'lmaydi.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mobagent_app_engine_LlamaEngine_nativeWarmupAndSave(
        JNIEnv *env, jobject /* this */,
        jlong contextPtr, jstring prefixPrompt, jstring path) {

    auto *engineCtx = reinterpret_cast<EngineContext *>(contextPtr);
    if (!engineCtx || !engineCtx->ctx || !engineCtx->vocab) {
        LOGE("Warmup: context ishga tushmagan");
        return JNI_FALSE;
    }
    llama_context *ctx = engineCtx->ctx;
    const llama_vocab *vocab = engineCtx->vocab;

    const char *cPrefix = env->GetStringUTFChars(prefixPrompt, nullptr);
    const int32_t prefixLen = (int32_t) strlen(cPrefix);

    int32_t n_prefix = -llama_tokenize(vocab, cPrefix, prefixLen, nullptr, 0, true, true);
    if (n_prefix <= 0) {
        LOGE("Warmup: tokenize muvaffaqiyatsiz");
        env->ReleaseStringUTFChars(prefixPrompt, cPrefix);
        return JNI_FALSE;
    }
    std::vector<llama_token> prefixTokens(n_prefix);
    if (llama_tokenize(vocab, cPrefix, prefixLen, prefixTokens.data(), n_prefix, true, true) < 0) {
        LOGE("Warmup: tokenize (2-chaqiruv) muvaffaqiyatsiz");
        env->ReleaseStringUTFChars(prefixPrompt, cPrefix);
        return JNI_FALSE;
    }
    env->ReleaseStringUTFChars(prefixPrompt, cPrefix);

    // Warmup odatda model yuklangandan darhol keyin, HECH narsa decode
    // qilinmasdan chaqiriladi — lekin har ehtimolga qarshi tozalab
    // boshlaymiz: saqlanadigan holat FAQAT shu prefiksga tegishli bo'lishi
    // kerak, boshqa hech narsaga aralashmasligi shart.
    auto *memory = llama_get_memory(ctx);
    llama_memory_clear(memory, true);

    llama_batch batch = llama_batch_get_one(prefixTokens.data(), (int32_t) prefixTokens.size());
    if (llama_decode(ctx, batch)) {
        LOGE("Warmup: llama_decode muvaffaqiyatsiz");
        return JNI_FALSE;
    }

    engineCtx->lastSequence = prefixTokens;

    const char *cPath = env->GetStringUTFChars(path, nullptr);
    const bool saved = llama_state_save_file(ctx, cPath, prefixTokens.data(), prefixTokens.size());
    env->ReleaseStringUTFChars(path, cPath);

    if (saved) {
        LOGI("Warmup: system prompt keshi saqlandi (%zu token)", prefixTokens.size());
    } else {
        LOGE("Warmup: keshni faylga saqlash muvaffaqiyatsiz");
    }
    return saved ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mobagent_app_engine_LlamaEngine_nativeLoadState(
        JNIEnv *env, jobject /* this */,
        jlong contextPtr, jstring path) {

    auto *engineCtx = reinterpret_cast<EngineContext *>(contextPtr);
    if (!engineCtx || !engineCtx->ctx) {
        LOGE("LoadState: context ishga tushmagan");
        return JNI_FALSE;
    }
    llama_context *ctx = engineCtx->ctx;

    const char *cPath = env->GetStringUTFChars(path, nullptr);
    // Fayl mavjud emasligi ODATIY holat (masalan shu model/promptStrategy/nCtx
    // uchun warmup hali bir marta ham ishlamagan) — bu XATO emas, shuning
    // uchun ochishga urinishdan oldin jimgina tekshiramiz (keraksiz llama.cpp
    // ogohlantirish logini oldini olish uchun).
    FILE *probe = fopen(cPath, "rb");
    if (!probe) {
        env->ReleaseStringUTFChars(path, cPath);
        return JNI_FALSE;
    }
    fclose(probe);

    // Yuklangan holat nCtx tokengacha bo'lishi mumkin (undan katta bo'lishi
    // mumkin emas — warmup prefiksi har doim kontekstdan ancha kichik).
    std::vector<llama_token> restored(engineCtx->nCtx);
    size_t nRestored = 0;
    const bool ok = llama_state_load_file(ctx, cPath, restored.data(), restored.size(), &nRestored);
    env->ReleaseStringUTFChars(path, cPath);

    if (!ok || nRestored == 0) {
        LOGE("LoadState: kesh fayli o'qilmadi yoki bo'sh — model qayta yuklanganda mos kelmagan bo'lishi mumkin");
        return JNI_FALSE;
    }

    restored.resize(nRestored);
    engineCtx->lastSequence = restored;
    LOGI("LoadState: system prompt keshi tiklandi (%zu token)", nRestored);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mobagent_app_engine_LlamaEngine_nativeCancel(
        JNIEnv *env, jobject /* this */, jlong contextPtr) {
    auto *engineCtx = reinterpret_cast<EngineContext *>(contextPtr);
    if (engineCtx) {
        engineCtx->cancelRequested.store(true);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mobagent_app_engine_LlamaEngine_nativeClose(
        JNIEnv *env, jobject /* this */, jlong contextPtr) {

    auto *engineCtx = reinterpret_cast<EngineContext *>(contextPtr);
    if (engineCtx) {
        if (engineCtx->ctx) llama_free(engineCtx->ctx);
        if (engineCtx->model) llama_model_free(engineCtx->model);
        delete engineCtx;
    }
    // llama_backend_free() ATAYLAB chaqirilmaydi — yuqoridagi izohga qarang:
    // backend butun jarayon davomida umumiy (boshqa ChatScreen'lar ham
    // undan foydalanayotgan bo'lishi mumkin) va faqat ilova butunlay
    // yopilganda (process o'lganda) tizim tomonidan tabiiy ravishda
    // tozalanadi.
}
