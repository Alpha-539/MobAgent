# MobAgent API (Cloudflare Worker)

Model katalogi (D1), GitHub kabi yulduzchalar, yig'ma statistika, admin panel, `/privacy` va `/delete-account`.
Chat matni bu serverga **hech qachon** kelmaydi.

## Ishga tushirish (brauzerdan, CLI shart emas)

1. Cloudflare → D1 → `mobagent` bazasini yarating, `database_id` ni `wrangler.toml` ga yozing.
2. Workers & Pages → Import a repository → GitHub repo. Root directory: `worker`, deploy command: `npx wrangler deploy`,
   Build watch paths: `worker/*`.
3. Settings → Variables and Secrets → Secret: `STATS_SALT` va `ADMIN_TOKEN` (>= 24 belgi).
4. Tayyor. **Jadvallar va 14 ta boshlang'ich model Worker'ning birinchi so'rovida o'zi yaratiladi** (SQL fayllar kerak emas).
   Boshlang'ich modellar faqat bir marta yoziladi; keyin admin o'chirsa, qayta tiklanmaydi.

Ilova katalogi o'zgarsa, boshlang'ich ro'yxatni yangilash: `python3 tools/build_seed.py` (repo ichida ishlaydi).

## Admin panel

`https://<worker>/alpha/maxfiy_sahifa` — modellarni qo'shish, tahrirlash, o'chirish, statistikani ko'rish.
"HuggingFace'dan sha256/hajmni olish" tugmasi havoladan sha256 va hajmni to'ldiradi (ilova yuklangan faylni shu bilan tekshiradi).

**Admin manzili:** panel `/admin` da emas, yashirin manzilda — `https://<worker>/alpha/maxfiy_sahifa`. `/admin` va `/admin/api/*` 404 beradi (panel borligi ham bilinmaydi). Manzilni almashtirish uchun `wrangler secret put ADMIN_PATH` (masalan `alpha/boshqa_yol`; 1-4 bo'lak, harf/raqam/`_`/`-`). ADMIN_PATH noto'g'ri bo'lsa — admin butunlay yopiladi.
`ADMIN_TOKEN` (>= 24 belgi) baribir kerak. Eng kuchli himoya — quyidagi Cloudflare Access bilan birga ishlatish.

**Cloudflare Access (tavsiya):** Zero Trust → Access → Applications → Self-hosted, domen `<worker>`, yo'l `alpha/maxfiy_sahifa*`, faqat o'z emailingizga ruxsat. So'ng `ACCESS_TEAM_DOMAIN` va `ACCESS_AUD` ni `wrangler.toml`'ga yozib qayta deploy qiling.
Access sozlangach `ADMIN_TOKEN` ishlamaydi — faqat Access JWT qabul qilinadi.

**Modellar bo'limi (yangilandi):** qidiruv va filtr, har model uchun *manba* (qaysi sayt/fayl), **System prompt** maydoni (tayyor namunalar: qisqa javob, kod, matn, mantiq, tarjima, bo'sh andoza; belgi/token hisoblagich va ogohlantirishlar), ichki izoh, ↑↓ tartib, yoqish/o'chirib qo'yish, nusxa olish, "RAM ni taxmin qilish", qo'llanma va JSON zaxira (eksport/import).
System prompt katalog orqali ilovaga boradi va **Chat rejimidagi** (≤1B, R1) modellarda standart rol promptining o'rniga ishlatiladi; bo'sh bo'lsa ilovadagi standart ishlaydi. **Agent ko'rsatmasi** (`agent_prompt`) esa Agent (1.5–2B) va Kuchli (3B+) modellar uchun: ilovaning standart agent promptini almashtirmaydi, uning oxiriga qo'shiladi (asbob formati va xavfsizlik qoidalari o'zgarmaydi). Server 1000 belgigacha qabul qiladi; ixcham Agent (1.5–2B) modelda ilova faqat dastlabki 400 belgini ishlatadi; Chat (≤1B) modellarda ishlatilmaydi. Yangi ustunlar (`system_prompt`, `agent_prompt`, `note`) mavjud bazaga ham o'zi qo'shiladi.

**Model belgisi (yangi):** model formasidagi *Belgi* maydoni — «Tavsiya etilgan» (`RECOMMENDED`) yoki «Yangi» (`NEW`) yoki belgisiz. Katalog orqali (`badge`) ilovadagi Model do'konida va bosh sahifadagi (landing) model kartalarida ko'rinadi (5 daqiqagacha kesh kechikishi bo'lishi mumkin). O'zgarish «Tarix»da yoziladi va bekor qilinadi; eksport/importga kiradi. Yangi ustun (`badge`) mavjud bazaga ham o'zi qo'shiladi.

**Sinash oynasi (yangi):** model tahrirlash formasida System prompt ostida "Sinash oynasi" bor — namuna savol (va xohlasangiz oldingi suhbat) bilan ilova modelga yuboradigan to'liq matnni model oilasi shabloni bo'yicha ko'rsatadi, belgi/token hajmini, kontekstdagi ulushini va ogohlantirishlarni (masalan, Agent modelda prompt ishlatilmasligi) chiqaradi. Hech narsa serverga yuborilmaydi. `tools/admin.html` o'zgargach `python3 tools/build_admin.py` ni ishga tushiring.

**Fikrlar:** ilovadagi *Sozlamalar → Fikr bildirish* orqali kelgan fikrlar (taklif / xato / boshqa) ro'yxati, tur va "o'qilmagan" filtri, o'qildi belgisi, o'chirish. Umumiy bo'limida o'qilmagan fikrlar soni ko'rinadi. Jadval (`feedback`) o'zi yaratiladi.

Admin panel bo'limlari: **Umumiy** (faol obunalar, tarif bo'yicha soni, 7 kunda tugaydiganlar, sozlash holati), **Modellar**,
**Obunalar** (UID bo'yicha qidirish, foydalanish foizlari, Play bilan qayta sinxronlash, limitni nolga tushirish,
qo'lda tarif berish/qaytarib olish), **Tariflar** (narxlar va qaysi provayder kaliti ulangani), **Statistika**.
Dizayn ilova bilan bir xil (Space Grotesk, binafsha aksent, qorong'i/yorug' rejim).

## Endpointlar

| Yo'l | Kim | Vazifa |
|---|---|---|
| `GET /` | hamma | bosh sahifa (landing), `src/landing.ts`; `/health` — JSON holat |
| `GET /catalog` | hamma | modellar ro'yxati (5 daqiqa kesh) |
| `PUT/DELETE /models/:id/star` | Firebase token, email tasdiqlangan | yulduzcha qo'yish/olish |
| `GET /me/stars` | Firebase token | o'zim yulduzcha qo'ygan modellar |
| `GET /app-update` | hamma | yangi versiya eslatmasi `{update: {latestCode, note}\|null}` (1 daqiqa kesh); adminda «Yangilanish» bo'limidan boshqariladi |
| `POST /feedback` | Firebase token (email tasdiqlangan) | foydalanuvchi fikri `{kind: idea\|bug\|other, message, contact?, appVersion?, lang?}`; soatiga 5, kuniga 15 ta |
| `DELETE /me` | Firebase token | serverdagi shaxsiy ma'lumotni o'chirish |
| `POST /events` | hamma (token bo'lsa DAU ham sanaladi) | yig'ma hodisa |
| `/alpha/maxfiy_sahifa`, `/alpha/maxfiy_sahifa/api/*` | admin | boshqaruv (shu jumladan `/admin/api/cloud/overview`, `/cloud/user`, `/cloud/grant|revoke|resync|reset-usage`) |
| `/privacy`, `/delete-account` | hamma | Play Console uchun havolalar (`?lang=uz`) |

## Play Console

- Privacy policy URL: `https://<worker>/privacy`
- Account deletion URL: `https://<worker>/delete-account`
- Data safety: email/ism/ID (hisob), suhbat matni (Firestore, onlayn rejim), foydalanuvchi kontenti, yulduzchalar, yig'ma statistika, Firebase Analytics.

## Pullik rejim (obuna + AI proksi)

**Sirlar** (Cloudflare -> Worker -> Settings -> Variables and Secrets; `.env` fayl Cloudflare'ga o'qilmaydi va uni GitHub'ga yuklamang):

| Nomi | Nima |
|---|---|
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, `MOONSHOT_API_KEY` (Kimi), `DEEPSEEK_API_KEY` | kamida bittasi; kaliti yo'q provayderning modellari ko'rinmaydi |
| `PLAY_SERVICE_ACCOUNT` | Google Cloud service account JSON (to'liq matn) |
| `RTDN_SECRET` | tasodifiy uzun qator (Pub/Sub manziliga qo'yiladi) |

**Google Play tomoni**
1. Play Console -> Monetize -> Subscriptions: `mobagent_basic`, `mobagent_standard`, `mobagent_pro`; har birida oylik (P1M) va yillik (P1Y) base plan.
2. Google Cloud'da service account yarating, Play Console -> Users and permissions orqali unga ruxsat bering (View financial data va Manage orders and subscriptions), Google Play Android Developer API'ni yoqing.
3. Real-time developer notifications: Cloud Pub/Sub topic yarating, `google-play-developer-notifications@system.gserviceaccount.com` ga Publisher huquqi bering, **Push** subscription manzili: `https://<worker>/billing/rtdn?secret=<RTDN_SECRET>`. Topic nomini Play Console -> Monetization setup'ga yozing.
4. Narxlarni `src/plans.ts` da provayder konsoli bilan solishtirib tekshiring.

**Endpointlar:** `POST /v1/chat/completions`, `GET /v1/models`, `GET /me/plan`, `POST /billing/verify`, `POST /billing/rtdn`.
Chat matni saqlanmaydi va log qilinmaydi; faqat token soni va limit hisoblagichlari saqlanadi.


## O'zgarishlar tarixi (admin → «Tarix»)
Model qo'shish/tahrirlash/o'chirish/import va obuna amallari `audit_log` jadvaliga yoziladi (kim, qachon, nima). Modellar uchun oldingi holat saqlanadi — «Bekor qilish» tugmasi uni qaytaradi. Jadval Worker birinchi so'rovida o'zi yaratiladi (`db-init.ts`); eski yozuvlar (180 kun) har kuni cron bilan tozalanadi. Faqat tartibni (↑ ↓) o'zgartirish yozilmaydi. Kod: `src/audit.ts`.


## E'lonni bildirishnoma (push) sifatida yuborish
Admin → **E'lonlar** → yangi e'lon formasida «Bildirishnoma (push)» = *Ha* bo'lsa, saqlanganda FCM orqali telefonlarga boradi; mavjud e'londa «🔔 Bildirishnoma» tugmasi qayta yuboradi.
- Til «Hamma til» bo'lsa — topic `announce_all`, aks holda `announce_<uz|ru|en|ar>` (ilova o'z tiliga avtomatik obuna bo'ladi).
- Kerak: `FIREBASE_SERVICE_ACCOUNT` secret (xat yuborish uchun qo'yilgan o'sha) va Google Cloud'da **Firebase Cloud Messaging API** yoqilgan bo'lishi (Firebase loyihalarida odatda yoqilgan).
- Foydalanuvchi yangi ilovani bir marta ochib, bildirishnomaga ruxsat berishi kerak (Android 13+). Eski versiyadagilarga bormaydi.

