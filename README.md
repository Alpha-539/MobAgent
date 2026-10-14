# MobAgent (Android)

Offline/online rejimda ishlaydigan, terminal buyruqlarini bajara oladigan AI agent.
Faqat GitHub orqali (Android Studio'siz) build qilish uchun sozlangan.

## GitHub'ga push qilish

llama.cpp manba kodini qo'lda submodule qilib qo'shish SHART EMAS —
`.github/workflows/build.yml` uni har build'da avtomatik `git clone` qiladi
(`app/src/main/cpp/llama.cpp` papkasi bo'sh bo'lsa). Shunchaki:

```bash
cd mobagent
git init
git add .
git commit -m "MobAgent - boshlang'ich skeleton"
git branch -M main
git remote add origin https://github.com/<username>/<repo>.git
git push -u origin main
```

Push qilinishi bilan `.github/workflows/build.yml` avtomatik ishga tushadi va
**Actions** bo'limida `mobagent-debug-apk` nomli artifact sifatida APK paydo bo'ladi
(3-5 daqiqa davomida, chunki llama.cpp'ni compile qilish vaqt oladi).

> Eslatma: llama.cpp'ning `main` branch'i tez-tez o'zgaradi, shuning uchun
> vaqti-vaqti bilan API mos kelmasligi bois build xato berishi mumkin. Shunday
> holatda GitHub Actions log'idagi **aynan qaysi fayl/qator xato berayotganini**
> (odatda "error: ..." matni bilan) menga yuboring — shunga qarab moslashtiraman.

## Loyiha holati — nima tayyor, nima TODO

| Qism | Holat |
|---|---|
| Gradle/CMake/NDK build zanjiri | ✅ Tayyor |
| GitHub Actions (build.yml) | ✅ Tayyor |
| Model Store UI + RAM tekshiruvi | ✅ Tayyor (ModelStoreScreen.kt) |
| Agent Orchestrator (tool-call parsing) | ✅ Tayyor (AgentOrchestrator.kt) |
| CommandExecutor (shell bajarish) | ✅ Tayyor |
| Chat UI + tasdiqlash dialogi | ✅ Tayyor (ChatScreen.kt) |
| JNI ko'prik (Kotlin tomoni) | ✅ Tayyor (LlamaEngine.kt) |
| Native generatsiya sikli (C++) | ✅ Tayyor — haqiqiy tokenize→decode→sample sikli (abort_callback bilan darhol to'xtatish, backend bir marta ishga tushirilishi) |
| Model faylni qurilmaga yuklab olish (download manager) | ✅ Tayyor — fon rejimida ham davom etadi (ModelDownloadService.kt) |
| Model URL'lari (ModelItem.kt) | ⚠️ Namuna sifatida yozilgan, push qilishdan oldin haqiqiy Hugging Face havolalarini tekshirib/yangilab oling |

## Offline agent terminal imkoniyatlari

| Imkoniyat | Holat |
|---|---|
| Doimiy interaktiv sessiya (`cd`, `export` keyingi buyruqqa o'tadi) | ✅ Tayyor (PersistentShellSession.kt) |
| `pkg install python/node/git/clang/...` | ✅ Termux o'rnatilgan bo'lsa ishlaydi (TermuxBridge.kt) — pastga qarang |
| Python/Node/Ruby skript ishga tushirish | ✅ Termux o'rnatilgan bo'lsa ishlaydi (TermuxBridge.kt) |
| `pkg install`/interpretatorlarni Termuxsiz, to'liq mustaqil ishlatish | ❌ Qasddan qilinmagan — sababi pastda |
| To'liq vim/nano | ⚠️ TODO (toybox'ning soddalashtirilgan "vi"si CI'da hali build qilinmaydi) |
| termux-api (batareya, kamera va h.k.) | ✅ Termux o'rnatilgan bo'lsa ishlaydi (TermuxBridge.kt/CommandExecutor.kt) |
| Fonda uzoq ishlaydigan jarayonlar (ssh-server, tmux) | ⚠️ TODO |
| Bitta so'rovga ko'p bosqichli buyruq zanjiri | ✅ Tayyor — MAX_CHAIN_STEPS=6 gacha, har qadam baribir tasdiqlash dialogidan o'tadi |

**Nega "pkg install"ni to'liq mustaqil (Termuxsiz) qila olmaymiz:**
Termux'ning tayyor python/node/git/clang paketlari `/data/data/com.termux/files/usr`
yo'liga qattiq bog'langan holda tayyorlangan (dynamic linker interpreter yo'li,
shebang qatorlari, `sys.prefix` va h.k.). Ularni shunchaki yuklab olib
MobAgent'ning shaxsiy papkasiga ko'chirib qo'ysak, o'sha yo'l mavjud
bo'lmagani uchun ishga tushmaydi. Shu sabab bunday buyruqlar (agar Termux
o'rnatilgan bo'lsa) haqiqiy Termux ilovasi ichida, uning rasman
hujjatlashtirilgan `RUN_COMMAND` xizmati orqali bajariladi.

**Foydalanuvchi tomonidan bir martalik sozlash kerak:**
1. Termux o'rnatilgan bo'lishi kerak (F-Droid yoki GitHub Release — Play
   Store versiyasi eskirgan).
2. Termux ichida bir marta `termux-setup-storage` bajarilgan bo'lishi kerak.
3. MobAgent'da "Barcha fayllarga kirish" ruxsati yoqilgan bo'lishi kerak
   (ilova buni allaqachon so'raydi).
4. Tizim Sozlamalar > Ilovalar > MobAgent > Ruxsatlar sahifasida Termux
   buyruqlarini bajarish ruxsati berilgan bo'lishi kerak.

## Papka strukturasi

```
mobagent/
├── .github/workflows/build.yml   ← CI: APK yig'ish
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                  ← CMakeLists.txt + JNI kod (llama.cpp build vaqtida avtomatik klonlanadi)
│       ├── java/com/mobagent/app/
│       │   ├── MainActivity.kt
│       │   ├── model/ModelItem.kt
│       │   ├── engine/LlamaEngine.kt
│       │   ├── agent/AgentOrchestrator.kt, CommandExecutor.kt
│       │   └── ui/ModelStoreScreen.kt, ChatScreen.kt
│       └── res/
├── build.gradle.kts
└── settings.gradle.kts
```

## Eslatma: gradlew yo'q

Repo'da `gradlew` skripti va wrapper jar fayli yo'q (binary fayl, qo'lda
yaratib bo'lmaydi). Shu sabab `build.yml` gradle'ni to'g'ridan-to'g'ri
`gradle/actions/setup-gradle` orqali o'rnatib ishlatadi — sizga hech narsa
qilish shart emas, faqat push qiling.

## Onlayn suhbatlar tarixi (hisobga bog'langan) — Firestore sozlamasi

Onlayn suhbatlar `users/{uid}/chats/{chatId}` ga yoziladi. Ishlashi uchun Firebase Console'da bir martalik sozlash kerak:

1. **Build → Firestore Database → Create database** (production mode).
2. **Rules** bo'limiga quyidagini qo'yib **Publish** qiling (har kim faqat o'z suhbatlarini ko'radi):

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/chats/{chatId} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // API kalitlar sinxronlashi (faqat shifrlangan ma'lumot saqlanadi)
    match /users/{uid}/keys/{keyId} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

**API kalitlarni boshqa telefonda tiklash** ham shu qoidalarni talab qiladi — yuqoridagi `keys` qoidasini ham qo'shing. Sinxronlash parolsiz, avtomatik; kalitlar buluta shifrlanib ketadi (shifrlash kaliti shu hisobning `_meta` hujjatida).

Firestore yoqilmagan bo'lsa ilova buzilmaydi — tarix faqat shu telefonda (hisob bo'yicha alohida papkada) saqlanadi.
Rasmlar buluta yuklanmaydi, ular faqat yuborilgan telefonda qoladi.

## Linux muhiti (UserLAnd uslubi, prototip)

Sozlamalar > **Linux muhiti**. Ubuntu 24.04 rootfs yuklanadi va `proot` orqali ishga tushadi;
yoqilganda agentning `execute_bash` buyruqlari Ubuntu ichida bajariladi (apt, python3, git...).
O'chiq/o'rnatilmagan bo'lsa agent avvalgi toybox + Termux yo'lida ishlayveradi.

- `proot`, `loader`, `libtalloc` CI'da `scripts/fetch-proot.sh` bilan jniLibs'ga qo'shiladi
  (Termux repositoriyasidan). Skript xato bersa APK baribir quriladi, ekran "proot yo'q" deydi.
- Kod: `linux/LinuxEnvironment.kt` (proot ishga tushirish), `linux/LinuxInstaller.kt`
  (yuklash + SHA-256 + ochish), `ui/LinuxSettingsScreen.kt`.
- **Avtomatik o'rnatish**: kirgandan keyin ilova o'zi, fonda (foreground service + bildirishnoma) Ubuntu'ni
  yuklaydi — sozlamaga kirish shart emas. Wi-Fi'da so'ramaydi; mobil internetda bir marta so'raydi.
  Yuklashdan oldin proot shu telefonda ishlashi tekshiriladi (ishlamasa yuklanmaydi). 2 marta xato bo'lsa,
  yoki foydalanuvchi bekor qilsa/o'chirsa, o'zi qayta urinmaydi (qo'lda o'rnatish ochiq qoladi).
  Tugagach Linux avtomatik yoqiladi; sababi Sozlamalar > Linux muhiti'da ko'rinadi.
- Telefon xotirasini ulash: foydalanuvchi tanlamagan bo'lsa, "Barcha fayllarga kirish" ruxsati bor-yo'qligiga ergashadi. proot ulangan papkani
  faqat-o'qish qila olmaydi — ulansa Linux ichidagi buyruqlar fayllarni o'chira oladi.
- **Terminal tugmasi**: Linux faol bo'lsa (o'rnatilgan + yoqilgan) chat sarlavhasida terminal belgisi chiqadi. Bosilsa
  Ubuntu terminali (root) chat USTIDA ochiladi (navigatsiya emas — model yuklanib turadi, yozilayotgan matn yo'qolmaydi).
  Kod: `linux/LinuxTerminal.kt` (jarayon darajasidagi bash, chiqish qatorma-qator oqadi, `cd`/`export` saqlanadi,
  To'xtatish shell'ni o'ldiradi) va `ui/LinuxTerminalScreen.kt`. Agentning shell sessiyasidan ALOHIDA jarayon: `cd`/`export`
  unga o'tmaydi, Ubuntu fayllari umumiy. PTY yo'q: interaktiv dasturlar (nano, REPL) ishlamaydi, stdin `/dev/null`.
- **Hali sinalmagan**: qurilmada "Sinov" tugmasi birinchi tekshiruv (proot ishga tushadimi,
  `apt --version` ishlaydimi).
- Ma'lum cheklov: `write_file/read_file/list_dir` vositalari hozircha Android fayl tizimida
  ishlaydi, `execute_bash` esa Ubuntu ichida — ular faqat /sdcard ulanganda umumiy yo'lga ega.
- proot GPL-2.0: tarqatishda manba kodiga havola berish kerak.


## Linux muhitini avtomatik o'rnatish va onlayn chiqish limiti

- Ilova birinchi ochilganda Ubuntu **dialogsiz, fonda** o'rnatiladi (UserLAnd kabi). Sozlamalardan
  o'chirilsa qayta o'rnatilmaydi.
- **Ubuntu APK ichida** (Google Play tashqaridan fayl yuklashni yoqtirmaydi): `scripts/build-rootfs.sh`
  Ubuntu Base arm64 ni yuklaydi, SHA-256 tekshiradi, hujjat/man/til/apt-keshdan tozalaydi va
  `xz -9e` bilan `app/src/main/assets/linux/ubuntu-base-arm64.tar.xz` ga siqadi. CI buni avtomatik bajaradi.
  Rootfs APK ichida bo'lsa o'rnatish internetsiz va tez bo'ladi. Qo'lda: `scripts/build-rootfs.sh`.
  APK ichida rootfs bo'lmasa (masalan lokal build), eski yo'l ishlaydi: internetdan yuklanadi
  (`LinuxInstaller.ALLOW_NETWORK_FALLBACK = false` qilsangiz u ham o'chadi).
- Onlayn modellarda chiqish limiti 32K token (Anthropic uchun 32000); xizmat past limit talab qilsa (HTTP 400)
  avtomatik 16K/8K/4K ga tushadi va shu model uchun eslab qolinadi. Tarix 120 000 belgigacha yuboriladi.
- Sozlamalardagi "Kontekst" faqat oflayn modellarga tegishli.

## Server katalogi, yulduzchalar (worker/)

- `ApiConfig.BASE_URL` (`app/.../model/RemoteCatalog.kt`) ga Worker manzilini yozing. Yozilmaguncha ilova faqat o'rnatilgan ro'yxatdan foydalanadi.
- Do'kon ochilganda `GET /catalog` olinadi, `filesDir/catalog_cache.json` ga keshlanadi; server ishlamasa kesh, u ham bo'lmasa `ModelCatalog.builtIn`.
- Katalogda `sha256` bo'lsa, yuklab olingan fayl shu bilan tekshiriladi (mos kelmasa o'chiriladi).
- Yulduzcha: faqat kirgan, emaili tasdiqlangan va modelni yuklab olgan foydalanuvchi qo'ya oladi.
- Statistika (`app_open`, `download_start/ok/fail`) faqat yig'ma son; chat matni serverga ketmaydi.
- Hisob o'chirilganda serverdagi yulduzchalar ham o'chiriladi (`DELETE /me`).

## Pullik rejim (MobAgent agents)

Ilova -> Worker `/v1/chat/completions` (OpenAI formatida) -> Anthropic / OpenAI / Gemini. Provayder kalitlari faqat Worker'da.
Byudjet har foydalanuvchiga bitta Durable Object'da (`worker/src/budget.ts`): 5 soatlik oyna = oylik byudjet/6, haftalik = /4,
narx provayder qaytargan haqiqiy token sonidan hisoblanadi. Tariflar va narxlar: `worker/src/plans.ts`.
Play Console'da `mobagent_basic`, `mobagent_standard`, `mobagent_pro` obuna mahsulotlari (har birida P1M va P1Y base plan) kerak.
Sozlash: `worker/README.md` -> "Pullik rejim".
