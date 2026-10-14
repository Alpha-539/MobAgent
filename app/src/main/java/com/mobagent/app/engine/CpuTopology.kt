package com.mobagent.app.engine

import java.io.File

/**
 * Ilgari `LlamaEngine`ga tred soni sifatida har doim "barcha yadrolar - 1"
 * berilardi. Bu ko'p zamonaviy telefonlarda (big.LITTLE yoki uch klasterli
 * protsessorlar — masalan 1 "prime" + 3 kuchli + 4 kuchsiz yadro) noto'g'ri:
 * llama.cpp'ning parallel hisoblash qadamida barcha tredlar HAR safar
 * bir-birini kutib turadi — eng SEKIN tred (kuchsiz/samaradorlik yadrosi)
 * butun qadamni sekinlashtiradi, garchi qolgan tredlar tezkor bo'lsa ham.
 *
 * Shuning uchun eng past chastotali (samaradorlik) klasterni tashlab,
 * faqat tezkor yadrolarni ishlatish odatda TEZROQ bo'ladi — hisoblash
 * kamroq "tred"da ketsa ham, hech kim bir-birini kutmaydi.
 */
object CpuTopology {

    /**
     * Qurilmaning eng tezkor CPU klasteridagi yadrolar sonini qaytaradi.
     *
     * `/sys/devices/system/cpu/cpu<index>/cpufreq/cpuinfo_max_freq` — har bir
     * yadroning eng yuqori mumkin bo'lgan chastotasi (kHz). Bu fayllarni
     * o'qish uchun maxsus ruxsat kerak emas (oddiy o'qiladigan tizim fayli).
     *
     * Barcha yadro bir xil chastotada bo'lsa (bitta klasterli, eski/arzon
     * qurilmalar) yoki fayllar o'qib bo'lmasa (ba'zi ROM/qurilmalarda
     * cheklangan bo'lishi mumkin), oddiy "hammasi - 1" usuliga qaytamiz —
     * bu holatda farqlashning ma'nosi yo'q yoki imkoni yo'q.
     */
    fun performantThreadCount(): Int {
        val fallback = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
        return try {
            val freqs = mutableListOf<Long>()
            var i = 0
            while (true) {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (!f.exists()) break
                f.readText().trim().toLongOrNull()?.let { freqs.add(it) }
                i++
            }

            // Kamida 2 yadro topilmasa yoki fayllar mavjud bo'lmasa — eski usul.
            if (freqs.size < 2) return fallback

            val distinctSorted = freqs.distinct().sortedDescending()
            // Hammasi bitta chastotada — bitta klaster, farqlashga hojat yo'q.
            if (distinctSorted.size < 2) return fallback

            // Eng past klasterni (samaradorlik yadrolari) chetlab, qolganini sanaymiz.
            val lowestTier = distinctSorted.last()
            val performantCount = freqs.count { it > lowestTier }

            // Aqlga muvofiqlik tekshiruvi: 0 yoki barcha yadro chiqsa (kutilmagan
            // holat), xavfsiz tomonga — eski usulga qaytamiz.
            if (performantCount in 2 until freqs.size) performantCount else fallback
        } catch (e: Exception) {
            fallback
        }
    }
}
