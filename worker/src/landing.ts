import type { Env } from "./types";
import { html } from "./util";

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

// Bosh sahifa (GET /). Dizayn ilova bilan bir xil: Space Grotesk, binafsha aksent, qorong'i/yorug' rejim, ">_<" belgisi.
// Modellar ro'yxati /catalog'dan jonli olinadi (admin panelda o'zgarsa, shu yerda ham o'zgaradi).
// Til: ?lang=en (default), uz, ru yoki ar (ar — o'ngdan chapga, RTL). Yangi til qo'shish uchun T ga yangi blok qo'shing va LANGS ga kodini yozing.

type Lang = "en" | "uz" | "ru" | "ar";
const LANGS: Record<Lang, string> = { en: "English", uz: "O'zbekcha", ru: "Русский", ar: "العربية" };
const langHref = (l: Lang) => (l === "en" ? "/" : "/?lang=" + l); // default (en) uchun toza URL

const T = {
  en: {
    navHow: "How", howH: "How it works", howSub: "Three steps from install to your first chat.", steps: [["Install and open","Get MobAgent from Google Play. No sign-up is needed for offline mode."],["Pick a model","Open the model store, choose one that fits your phone and tap download. It is a one-time download; the app checks the file for you."],["Start chatting","Ask questions, or switch to agent mode to work with files and the terminal. Risky actions always ask for your approval."]] as [string, string][],
    ramH: "Which model fits my phone?", ramSub: "Choose your phone's RAM and see the strongest models that should run comfortably.", ramLabel: "RAM", ramBest: "Best fit", badgeRec: "Recommended", badgeNew: "New", ramNone: "No model in the catalog fits this much RAM yet.", ramTip: "Rough guide: a model should use about half of your RAM or less, so the phone stays responsive.",
    title: (app: string) => `${app} — the AI agent on your phone`,
    desc: (app: string) => `${app}: AI models that run offline, plus an agent that works with files and the terminal. Available in Uzbek, Russian, English and Arabic.`,
    navFeatures: "Features", navModels: "Models", navPlans: "Plans", navDownload: "Get the app",
    lead: "Your personal AI agent on your phone: models that run without internet and an agent that can work with files and the terminal.",
    ctaPlay: "Get it on Google Play", ctaModels: "Browse models",
    featH: "What it can do", featSub: "Built for your phone: simple and fast.",
    f1h: "Offline models", f1p: "Download a model once, then use it without internet. Your chats never leave your phone.",
    f2h: "Agent mode", f2p: "Create, read and edit files and run commands. Every risky action needs your approval.",
    f3h: "Linux terminal", f3p: "An Ubuntu environment inside the app: it installs itself, and you and the agent share the same terminal.",
    f4h: "Online mode", f4p: "Connect popular AI services with your own API key, or use powerful models through a subscription.",
    f5h: "Fits your phone", f5p: "Lightweight models run on any phone; heavier ones come with a RAM warning.",
    catH: "Model catalog", catSub: "The list updates live — same as in the app.", loading: "Loading…",
    plansH: "Online plans", plansSub: "Offline mode is free. Monthly subscription for powerful online models.",
    p1h: "Starter", p1p: "Mid-size models for everyday questions and writing.",
    p2h: "Standard", p2p: "Higher usage limits and stronger models.",
    p3h: "Pro", p3p: "The highest limits and the most powerful models.",
    priceFallback: "Price on Google Play",
    plansNote: "Prices and terms are shown on Google Play (monthly and yearly). You can cancel anytime.",
    navFaq: "FAQ", faqH: "Frequently asked questions",
    faq: [["Which phones does it work on?", "It is an Android app. Lightweight models run on almost any phone; heavier ones need more RAM, and the app warns you before you download a model your phone may struggle with."], ["Do I need the internet?", "Only to download a model (once) and to use online mode. Offline models work without any connection."], ["Where is my data stored?", "Offline chats, commands and files stay on your phone. In online mode with your own API key, messages go straight from your phone to the AI provider you chose. If you sign in, online chats sync to your account. Details are in the Privacy Policy."], ["Is it free?", "Offline mode is free. Online mode with your own API key is billed by your AI provider. A monthly subscription is optional if you want to use powerful models through MobAgent."], ["Can the agent damage my files?", "Every risky action, such as running a command or changing files, needs your approval first."], ["How do I delete my account?", "Open Profile → Delete account in the app, or use the Delete account link at the bottom of this page."]] as [string, string][],
    privacy: "Privacy Policy", del: "Delete account",
    typed: ["AI that works without internet — right on your phone.", "Create files, write code, give commands.", "A Linux terminal — inside the app.", "Use any model with your own API key.", "Your chats never leave your phone.", "Download it now and give it a try."],
    tiers: [["Fast", "Chat only, tuned for one clear task — the lightest, runs on every phone"], ["Agent", "Compact agent: works with files"], ["Powerful", "Full agent — needs more RAM"]],
    cats: { CODING: "Coding", CHAT: "Chat", TRANSLATION: "Translation", REASONING: "Reasoning" },
    none: "No models yet", fail: "Couldn't load the catalog. Please try again later.",
  },
  uz: {
    navHow: "Qanday ishlaydi", howH: "Qanday ishlaydi", howSub: "O'rnatishdan birinchi suhbatgacha uch qadam.", steps: [["Yuklang va oching","MobAgent'ni Google Play'dan yuklang. Offline rejim uchun ro'yxatdan o'tish shart emas."],["Model tanlang","Model do'konini oching, telefoningizga mos modelni tanlab yuklab oling. Bu bir martalik yuklash; faylni ilova o'zi tekshiradi."],["Suhbatni boshlang","Savol bering yoki agent rejimiga o'tib fayl va terminal bilan ishlang. Xavfli amallar doim sizning tasdig'ingizni so'raydi."]] as [string, string][],
    ramH: "Telefonimga qaysi model mos?", ramSub: "Telefoningiz RAM hajmini tanlang: qiynalmasdan ishlashi kutiladigan eng kuchli modellar ko'rsatiladi.", ramLabel: "RAM", ramBest: "Eng mos", badgeRec: "Tavsiya etilgan", badgeNew: "Yangi", ramNone: "Katalogda bu RAM'ga mos model hozircha yo'q.", ramTip: "Taxminiy qoida: model RAM'ingizning yarmi yoki undan kamini egallasin, shunda telefon sekinlashmaydi.",
    title: (app: string) => `${app} — telefoningizdagi AI agent`,
    desc: (app: string) => `${app}: internetsiz ishlaydigan AI modellar, fayl va terminal bilan ishlovchi agent. O'zbek, rus, ingliz va arab tillarida.`,
    navFeatures: "Imkoniyatlar", navModels: "Modellar", navPlans: "Tariflar", navDownload: "Yuklash",
    lead: "Telefoningizdagi shaxsiy AI agent: internetsiz ishlaydigan modellar, fayl va terminal bilan ishlay oladigan agent — o'zbek tilida.",
    ctaPlay: "Google Play'dan yuklash", ctaModels: "Modellarni ko'rish",
    featH: "Nimalar qila oladi", featSub: "Telefoningiz uchun mo'ljallangan, sodda va tez.",
    f1h: "Offline modellar", f1p: "Modelni bir marta yuklab oling — keyin internetsiz ishlaydi. Suhbat telefoningizdan chiqmaydi.",
    f2h: "Agent rejimi", f2p: "Fayl yaratish, o'qish va tahrirlash, buyruqlarni bajarish. Har bir xavfli amal sizning ruxsatingiz bilan.",
    f3h: "Linux terminal", f3p: "Ilova ichida Ubuntu muhiti: o'zi o'rnatiladi, agent va siz bir xil terminaldan foydalanasiz.",
    f4h: "Onlayn rejim", f4p: "O'z API kalitingiz bilan mashhur xizmatlarga ulaning yoki obuna orqali kuchli modellardan foydalaning.",
    f5h: "Telefoningizga mos", f5p: "Yengil modellar hamma telefonda ishlaydi, kuchlilari esa RAM ogohlantirishi bilan beriladi.",
    catH: "Modellar katalogi", catSub: "Ro'yxat jonli yangilanadi — ilovadagi bilan bir xil.", loading: "Yuklanmoqda…",
    plansH: "Onlayn tariflar", plansSub: "Offline rejim bepul. Onlayn kuchli modellar uchun oylik obuna.",
    p1h: "Boshlang'ich", p1p: "O'rtacha modellar, kundalik savollar va yozuv uchun.",
    p2h: "O'rta", p2p: "Ko'proq foydalanish limiti va kuchliroq modellar.",
    p3h: "Pro", p3p: "Eng yuqori limit va eng kuchli modellar.",
    priceFallback: "Narxi Google Play'da",
    plansNote: "Narx va shartlar Google Play'da ko'rsatiladi (oylik va yillik). Obunani istalgan payt bekor qilishingiz mumkin.",
    navFaq: "Savollar", faqH: "Ko'p beriladigan savollar",
    faq: [["Qaysi telefonlarda ishlaydi?", "Bu Android ilovasi. Yengil modellar deyarli har qanday telefonda ishlaydi; og'irlari ko'proq RAM talab qiladi, telefoningiz qiynalishi mumkin bo'lgan modelni yuklashdan oldin ilova sizni ogohlantiradi."], ["Internet kerakmi?", "Faqat modelni (bir marta) yuklash va onlayn rejim uchun. Offline modellar internetsiz ishlaydi."], ["Ma'lumotlarim qayerda saqlanadi?", "Offline suhbatlar, buyruqlar va fayllar telefoningizda qoladi. Onlayn rejimda o'z API kalitingiz bilan yozganlaringiz telefondan to'g'ridan-to'g'ri siz tanlagan AI-provayderga ketadi. Hisobga kirsangiz, onlayn suhbatlar hisobingizga sinxronlanadi. Batafsil — Maxfiylik siyosatida."], ["Bepulmi?", "Offline rejim bepul. O'z API kalitingiz bilan onlayn rejim uchun to'lovni AI-provayderingiz oladi. Kuchli modellardan MobAgent orqali foydalanmoqchi bo'lsangiz, oylik obuna ixtiyoriy."], ["Agent fayllarimga zarar yetkazmaydimi?", "Buyruq ishga tushirish yoki fayllarni o'zgartirish kabi har bir xavfli amal avval sizning tasdig'ingizni talab qiladi."], ["Hisobni qanday o'chiraman?", "Ilovada Profil → Hisobni o'chirish bo'limiga kiring yoki shu sahifa pastidagi «Hisobni o'chirish» havolasidan foydalaning."]] as [string, string][],
    privacy: "Maxfiylik siyosati", del: "Hisobni o'chirish",
    typed: ["Internetsiz ishlaydigan AI — telefoningizning o'zida.", "Fayl yarating, kod yozing, buyruq bering.", "Linux terminal — ilova ichida.", "O'z API kalitingiz bilan istalgan model.", "Suhbatingiz telefoningizdan chiqmaydi.", "Hoziroq yuklab oling va sinab ko'ring."],
    tiers: [["Tezkor", "Faqat suhbat, bitta aniq vazifaga sozlangan — eng yengil, hamma telefonda"], ["Agent", "Ixcham agent: fayllar bilan ishlaydi"], ["Kuchli", "To'liq agent — ko'proq RAM talab qiladi"]],
    cats: { CODING: "Kodlash", CHAT: "Suhbat", TRANSLATION: "Tarjima", REASONING: "Fikrlash" },
    none: "Hozircha modellar yo'q", fail: "Katalogni yuklab bo'lmadi. Keyinroq urinib ko'ring.",
  },
  ru: {
    navHow: "Как это работает", howH: "Как это работает", howSub: "Три шага от установки до первого чата.", steps: [["Установите и откройте","Скачайте MobAgent из Google Play. Для офлайн-режима регистрация не нужна."],["Выберите модель","Откройте магазин моделей, выберите подходящую телефону и нажмите «Скачать». Загрузка разовая, файл приложение проверит само."],["Начните чат","Задавайте вопросы или включите режим агента для работы с файлами и терминалом. Рискованные действия всегда требуют вашего подтверждения."]] as [string, string][],
    ramH: "Какая модель подойдёт моему телефону?", ramSub: "Выберите объём оперативной памяти — покажем самые сильные модели, которые должны работать без проблем.", ramLabel: "ОЗУ", ramBest: "Лучший выбор", badgeRec: "Рекомендуем", badgeNew: "Новое", ramNone: "В каталоге пока нет модели для такого объёма памяти.", ramTip: "Ориентир: модель должна занимать примерно половину ОЗУ или меньше, чтобы телефон не тормозил.",
    title: (app: string) => `${app} — ИИ-агент на вашем телефоне`,
    desc: (app: string) => `${app}: ИИ-модели, которые работают без интернета, и агент для работы с файлами и терминалом. На узбекском, русском, английском и арабском языках.`,
    navFeatures: "Возможности", navModels: "Модели", navPlans: "Тарифы", navDownload: "Скачать",
    lead: "Личный ИИ-агент на вашем телефоне: модели, которые работают без интернета, и агент, умеющий работать с файлами и терминалом.",
    ctaPlay: "Скачать в Google Play", ctaModels: "Смотреть модели",
    featH: "Что он умеет", featSub: "Создан для телефона: просто и быстро.",
    f1h: "Офлайн-модели", f1p: "Скачайте модель один раз — дальше она работает без интернета. Ваши чаты не покидают телефон.",
    f2h: "Режим агента", f2p: "Создание, чтение и редактирование файлов, выполнение команд. Каждое рискованное действие — только с вашего разрешения.",
    f3h: "Терминал Linux", f3p: "Среда Ubuntu прямо в приложении: устанавливается сама, а вы и агент работаете в одном терминале.",
    f4h: "Онлайн-режим", f4p: "Подключайте популярные сервисы со своим API-ключом или пользуйтесь мощными моделями по подписке.",
    f5h: "Подходит вашему телефону", f5p: "Лёгкие модели работают на любом телефоне, а для мощных показывается предупреждение об объёме ОЗУ.",
    catH: "Каталог моделей", catSub: "Список обновляется в реальном времени — как и в приложении.", loading: "Загрузка…",
    plansH: "Онлайн-тарифы", plansSub: "Офлайн-режим бесплатный. Мощные онлайн-модели — по ежемесячной подписке.",
    p1h: "Базовый", p1p: "Модели среднего уровня для повседневных вопросов и текстов.",
    p2h: "Стандарт", p2p: "Больше лимитов и более мощные модели.",
    p3h: "Pro", p3p: "Максимальные лимиты и самые мощные модели.",
    priceFallback: "Цена в Google Play",
    plansNote: "Цены и условия указаны в Google Play (на месяц и на год). Подписку можно отменить в любой момент.",
    navFaq: "Вопросы", faqH: "Частые вопросы",
    faq: [["На каких телефонах работает?", "Это приложение для Android. Лёгкие модели работают почти на любом телефоне; тяжёлым нужно больше оперативной памяти, и перед загрузкой модели, с которой телефон может не справиться, приложение предупредит вас."], ["Нужен ли интернет?", "Только чтобы один раз скачать модель и для онлайн-режима. Офлайн-модели работают без подключения."], ["Где хранятся мои данные?", "Офлайн-чаты, команды и файлы остаются на вашем телефоне. В онлайн-режиме с вашим API-ключом сообщения уходят с телефона напрямую выбранному вами ИИ-провайдеру. Если вы вошли в аккаунт, онлайн-чаты синхронизируются с ним. Подробности — в Политике конфиденциальности."], ["Это бесплатно?", "Офлайн-режим бесплатный. За онлайн-режим с вашим API-ключом платите своему ИИ-провайдеру. Ежемесячная подписка нужна по желанию, если вы хотите пользоваться мощными моделями через MobAgent."], ["Не навредит ли агент моим файлам?", "Любое рискованное действие, например запуск команды или изменение файлов, сначала требует вашего подтверждения."], ["Как удалить аккаунт?", "Откройте в приложении Профиль → Удалить аккаунт или воспользуйтесь ссылкой «Удаление аккаунта» внизу этой страницы."]] as [string, string][],
    privacy: "Политика конфиденциальности", del: "Удаление аккаунта",
    typed: ["ИИ, который работает без интернета — прямо на вашем телефоне.", "Создавайте файлы, пишите код, давайте команды.", "Терминал Linux — внутри приложения.", "Любая модель с вашим API-ключом.", "Ваши чаты не покидают телефон.", "Скачайте прямо сейчас и попробуйте."],
    tiers: [["Быстрые", "Только чат, настроены на одну задачу — самые лёгкие, работают на любом телефоне"], ["Агент", "Компактный агент: работает с файлами"], ["Мощные", "Полноценный агент — требует больше ОЗУ"]],
    cats: { CODING: "Код", CHAT: "Чат", TRANSLATION: "Перевод", REASONING: "Рассуждение" },
    none: "Моделей пока нет", fail: "Не удалось загрузить каталог. Попробуйте позже.",
  },
  ar: {
    navHow: "كيف يعمل", howH: "كيف يعمل", howSub: "ثلاث خطوات من التثبيت إلى أول محادثة.", steps: [["ثبّت وافتح","نزّل MobAgent من Google Play. لا حاجة إلى تسجيل للوضع دون اتصال."],["اختر نموذجًا","افتح متجر النماذج، واختر ما يناسب هاتفك ثم اضغط تنزيل. التنزيل يتم مرة واحدة ويتحقق التطبيق من الملف بنفسه."],["ابدأ المحادثة","اطرح أسئلتك أو انتقل إلى وضع الوكيل للعمل مع الملفات والطرفية. الإجراءات الخطرة تتطلب موافقتك دائمًا."]] as [string, string][],
    ramH: "أي نموذج يناسب هاتفي؟", ramSub: "اختر ذاكرة RAM لهاتفك لتظهر لك أقوى النماذج التي يُتوقع أن تعمل بسلاسة.", ramLabel: "RAM", ramBest: "الأنسب", badgeRec: "موصى به", badgeNew: "جديد", ramNone: "لا يوجد في الكتالوج نموذج يناسب هذه الذاكرة حاليًا.", ramTip: "قاعدة تقريبية: يجب أن يستهلك النموذج نصف ذاكرتك أو أقل ليبقى الهاتف سريع الاستجابة.",
    title: (app: string) => `${app} — وكيل الذكاء الاصطناعي على هاتفك`,
    desc: (app: string) => `${app}: نماذج ذكاء اصطناعي تعمل بدون إنترنت، ووكيل يتعامل مع الملفات والطرفية. بالأوزبكية والروسية والإنجليزية والعربية.`,
    navFeatures: "المزايا", navModels: "النماذج", navPlans: "الباقات", navDownload: "تحميل التطبيق",
    lead: "وكيلك الشخصي للذكاء الاصطناعي على هاتفك: نماذج تعمل بدون إنترنت ووكيل يستطيع العمل مع الملفات والطرفية.",
    ctaPlay: "احصل عليه من Google Play", ctaModels: "تصفّح النماذج",
    featH: "ماذا يستطيع أن يفعل", featSub: "مصمَّم للهاتف: بسيط وسريع.",
    f1h: "نماذج بدون إنترنت", f1p: "حمّل النموذج مرة واحدة ثم استخدمه بدون إنترنت. محادثاتك لا تغادر هاتفك.",
    f2h: "وضع الوكيل", f2p: "إنشاء الملفات وقراءتها وتعديلها وتنفيذ الأوامر. كل إجراء خطِر يتطلب موافقتك.",
    f3h: "طرفية Linux", f3p: "بيئة Ubuntu داخل التطبيق: تُثبَّت تلقائيًا، وتستخدم أنت والوكيل الطرفية نفسها.",
    f4h: "الوضع المتصل", f4p: "اربط الخدمات الشائعة بمفتاح API الخاص بك، أو استخدم نماذج قوية عبر الاشتراك.",
    f5h: "مناسب لهاتفك", f5p: "النماذج الخفيفة تعمل على أي هاتف، أما النماذج الأقوى فتظهر معها تنبيهات حول الذاكرة (RAM).",
    catH: "كتالوج النماذج", catSub: "تتحدّث القائمة مباشرة — مثل التطبيق تمامًا.", loading: "جارٍ التحميل…",
    plansH: "الباقات المتصلة", plansSub: "الوضع بدون إنترنت مجاني. اشتراك شهري للنماذج القوية المتصلة.",
    p1h: "الأساسية", p1p: "نماذج متوسطة للأسئلة اليومية والكتابة.",
    p2h: "القياسية", p2p: "حدود استخدام أعلى ونماذج أقوى.",
    p3h: "Pro", p3p: "أعلى الحدود وأقوى النماذج.",
    priceFallback: "السعر في Google Play",
    plansNote: "تظهر الأسعار والشروط في Google Play (شهريًا وسنويًا). يمكنك إلغاء الاشتراك في أي وقت.",
    navFaq: "الأسئلة", faqH: "الأسئلة الشائعة",
    faq: [["على أي الهواتف يعمل؟", "إنه تطبيق أندرويد. تعمل النماذج الخفيفة على أي هاتف تقريبًا؛ أما الأثقل فتحتاج ذاكرة RAM أكبر، وينبّهك التطبيق قبل تنزيل نموذج قد يصعب على هاتفك تشغيله."], ["هل أحتاج إلى الإنترنت؟", "فقط لتنزيل النموذج (مرة واحدة) ولاستخدام الوضع الإلكتروني. تعمل النماذج دون اتصال بلا أي اتصال بالإنترنت."], ["أين تُخزَّن بياناتي؟", "تبقى المحادثات والأوامر والملفات دون اتصال على هاتفك. في الوضع الإلكتروني بمفتاح API الخاص بك تذهب رسائلك مباشرةً من هاتفك إلى مزوّد الذكاء الاصطناعي الذي اخترته. وإذا سجّلت الدخول فتتم مزامنة المحادثات الإلكترونية مع حسابك. التفاصيل في سياسة الخصوصية."], ["هل هو مجاني؟", "الوضع دون اتصال مجاني. أما الوضع الإلكتروني بمفتاح API الخاص بك فيحاسبك عليه مزوّد الذكاء الاصطناعي. والاشتراك الشهري اختياري إذا أردت استخدام نماذج قوية عبر MobAgent."], ["هل قد يضرّ الوكيل بملفاتي؟", "كل إجراء ينطوي على خطورة، مثل تشغيل أمر أو تعديل ملفات، يتطلب موافقتك أولًا."], ["كيف أحذف حسابي؟", "افتح في التطبيق الملف الشخصي ← حذف الحساب، أو استخدم رابط «حذف الحساب» أسفل هذه الصفحة."]] as [string, string][],
    privacy: "سياسة الخصوصية", del: "حذف الحساب",
    typed: ["ذكاء اصطناعي يعمل بدون إنترنت — على هاتفك مباشرة.", "أنشئ الملفات، اكتب الشيفرة، أصدر الأوامر.", "طرفية Linux — داخل التطبيق.", "أي نموذج بمفتاح API الخاص بك.", "محادثاتك لا تغادر هاتفك.", "حمّله الآن وجرّبه."],
    tiers: [["سريعة", "محادثة فقط، مضبوطة لمهمة واحدة واضحة — الأخف، تعمل على كل الهواتف"], ["وكيل", "وكيل مدمج: يعمل مع الملفات"], ["قوية", "وكيل كامل — يحتاج ذاكرة أكبر"]],
    cats: { CODING: "البرمجة", CHAT: "محادثة", TRANSLATION: "ترجمة", REASONING: "استدلال" },
    none: "لا توجد نماذج بعد", fail: "تعذّر تحميل الكتالوج. حاول لاحقًا.",
  },
};

const pickLang = (l: string | null): Lang => (l === "uz" || l === "ru" || l === "ar" ? l : "en");

export function landingPage(env: Env, langParam: string | null = null): Response {
  const lang = pickLang(langParam);
  const t = T[lang];
  const q = lang === "en" ? "" : "?lang=" + lang; // default (en) uchun toza URL
  const lq = q; // maxfiylik va hisobni o'chirish sahifalari ham en/uz/ru/ar
  const langs = (Object.keys(LANGS) as Lang[]).filter((l) => l !== lang);
  const switcher = langs.map((l) => `<a class="lg" href="${langHref(l)}" hreflang="${l}">${LANGS[l]}</a>`).join("");
  const alts = (Object.keys(LANGS) as Lang[]).map((l) => `<link rel="alternate" hreflang="${l}" href="${langHref(l)}">`).join("") + `<link rel="alternate" hreflang="x-default" href="/">`;
  const app = esc(env.APP_NAME || "MobAgent");
  const mail = esc(env.CONTACT_EMAIL || "");
  const play = "https://play.google.com/store/apps/details?id=" + encodeURIComponent(env.PLAY_PACKAGE || "com.mobagent.app");
  const faqHtml = t.faq.map(([qq, aa]) => `<details class="qa"><summary>${qq}</summary><p>${aa}</p></details>`).join("");
  const faqLd = JSON.stringify({ "@context": "https://schema.org", "@type": "FAQPage", mainEntity: t.faq.map(([qq, aa]) => ({ "@type": "Question", name: qq, acceptedAnswer: { "@type": "Answer", text: aa } })) }).replace(/</g, "\\u003c");
  const price = (v?: string) => (v && v.trim() ? `<div class="price">${esc(v.trim())}</div>` : `<div class="price sm">${t.priceFallback}</div>`);
  // JS ichiga xavfsiz uzatish: "<" belgisini qochiramiz (</script> buzmasligi uchun)
  const clientData = JSON.stringify({ P: t.typed, T: t.tiers, CAT: t.cats, none: t.none, fail: t.fail, ramBest: t.ramBest, BADGE: { RECOMMENDED: t.badgeRec, NEW: t.badgeNew }, ramNone: t.ramNone, ramLabel: t.ramLabel }).replace(/</g, "\\u003c");
  return html(`<!doctype html><html lang="${lang}" dir="${lang === "ar" ? "rtl" : "ltr"}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${t.title(app)}</title>
<meta name="description" content="${t.desc(app)}">
${alts}
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500&family=Space+Grotesk:wght@400;500;600;700${lang === "ar" ? "&family=Noto+Sans+Arabic:wght@400;500;700" : ""}&display=swap">
<style>
:root{color-scheme:dark light;--bg:#121118;--card:#1c1b25;--alt:#17161e;--line:#32303f;--fg:#f3f2f8;--fg2:#b1afc2;--mut:#7c798e;--acc:#8b7cf6;--accD:#6e5ee8;--accBg:#2b2657;--accT:#dcd6ff;--ok:#3dd68c;--okBg:#163c2c;--okT:#7eeab4;--warnBg:#4a2e06;--warnT:#ffc876;--plain:0}
@media(prefers-color-scheme:light){:root{--bg:#faf9fd;--card:#fff;--alt:#f3f1fa;--line:#e4e1ef;--fg:#1e1b2e;--fg2:#5c586f;--mut:#928ea4;--acc:#5b3fd9;--accD:#4b31c9;--accBg:#e9e4fb;--accT:#4b31c9;--ok:#1fae6b;--okBg:#dff7ea;--okT:#14824e;--warnBg:#ffebcf;--warnT:#9c6100}}
*{box-sizing:border-box}html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 'Space Grotesk',system-ui,sans-serif}
a{color:inherit;text-decoration:none}.wrap{max-width:1040px;margin:auto;padding:0 18px}
header{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--bg) 88%,transparent);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
header .wrap{display:flex;align-items:center;gap:14px;height:62px}
.brand{display:flex;align-items:center;gap:10px;font-weight:500;font-size:18px;color:var(--acc)}.brand b{color:var(--fg);font-weight:700;font-size:20px;letter-spacing:-.02em}
nav{margin-inline-start:auto;display:flex;gap:4px;align-items:center}nav a{padding:7px 12px;border-radius:999px;color:var(--fg2);font-size:14px}nav a:hover{background:var(--accBg);color:var(--accT)}
.btn{display:inline-flex;align-items:center;gap:8px;padding:12px 22px;border-radius:999px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-weight:500;cursor:pointer}
.btn.p{background:var(--acc);border-color:var(--acc);color:#fff}.btn:hover{border-color:var(--acc)}.btn.p:hover{background:var(--accD)}
.hero{text-align:center;padding:56px 0 44px}.hero .face{font-weight:700;font-size:clamp(64px,15vw,96px);line-height:1;letter-spacing:-.02em;color:var(--fg)}
h1{font-size:clamp(32px,7vw,46px);line-height:1.15;margin:20px 0 14px;font-weight:500;color:var(--acc)}
.typed{min-height:3.3em;max-width:520px;margin:0 auto 14px;color:var(--mut);font-size:clamp(15px,2.4vw,18px)}.typed::after{content:"▍";animation:bl .52s linear infinite alternate}@keyframes bl{from{opacity:1}to{opacity:0}}
@media(prefers-reduced-motion:reduce){.typed::after{animation:none}}
.lead{max-width:640px;margin:0 auto 28px;color:var(--fg2);font-size:clamp(16px,2.4vw,19px)}.cta{display:flex;gap:10px;justify-content:center;flex-wrap:wrap}
section{padding:40px 0}h2{font-size:clamp(22px,4vw,30px);margin:0 0 6px;letter-spacing:-.01em}.sub{color:var(--mut);margin:0 0 22px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:18px}.card h3{margin:0 0 6px;font-size:16px}.card p{margin:0;color:var(--fg2);font-size:14px}
.ic{width:38px;height:38px;border-radius:12px;background:var(--accBg);color:var(--accT);display:grid;place-items:center;font:500 14px 'JetBrains Mono',monospace;margin-bottom:12px}
.tier{margin:26px 0 10px;display:flex;align-items:baseline;gap:10px;flex-wrap:wrap}.tier b{font-size:17px}.tier span{color:var(--mut);font-size:13px}
.m{display:flex;flex-direction:column;gap:8px}.m .top{display:flex;justify-content:space-between;gap:8px;align-items:start}.m .n{font-weight:600}
.pill{display:inline-block;padding:2px 10px;border-radius:999px;font-size:11px;background:var(--accBg);color:var(--accT);white-space:nowrap}.pill.ok{background:var(--okBg);color:var(--okT)}.pill.w{background:var(--warnBg);color:var(--warnT)}
.meta{display:flex;gap:6px;flex-wrap:wrap;margin-top:auto}.star{color:var(--warnT);font-size:13px;white-space:nowrap}
.plans .card{text-align:center}.price{font-size:30px;font-weight:700;margin:6px 0;color:var(--acc)}.price.sm{font-size:16px;font-weight:500;color:var(--mut)}
.plans .card.hot{border-color:var(--acc);box-shadow:0 0 0 1px var(--acc) inset}
.note{color:var(--mut);font-size:13px;margin-top:14px}
footer{border-top:1px solid var(--line);margin-top:30px;padding:26px 0 40px;color:var(--mut);font-size:14px}footer .wrap{display:flex;gap:18px;flex-wrap:wrap;align-items:center}footer a:hover{color:var(--acc)}
.err{color:var(--mut)}
.qa{background:var(--card);border:1px solid var(--line);border-radius:14px;margin-bottom:8px}.qa summary{cursor:pointer;padding:14px 18px;font-weight:500;list-style:none;display:flex;justify-content:space-between;gap:12px}.qa summary::-webkit-details-marker{display:none}.qa summary::after{content:"+";color:var(--acc);font-size:20px;line-height:1}.qa[open] summary::after{content:"−"}.qa p{margin:0;padding:0 18px 16px;color:var(--fg2);font-size:15px}
.steps{counter-reset:s;display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px}.step{position:relative;padding-top:50px}.step::before{counter-increment:s;content:counter(s);position:absolute;top:16px;inset-inline-start:18px;width:28px;height:28px;border-radius:50%;background:var(--acc);color:#fff;display:grid;place-items:center;font:600 14px 'Space Grotesk',sans-serif}
.chips{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 16px}.chip{padding:8px 18px;border-radius:999px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-weight:500;cursor:pointer}.chip[aria-pressed=true]{background:var(--acc);border-color:var(--acc);color:#fff}
nav a.lg{border:1px solid var(--line);font-size:13px;padding:5px 11px}
@media(max-width:640px){nav a.h,nav a.btn{display:none}nav a.lg{padding:4px 9px;font-size:12px}.hero{padding:40px 0 30px}}
${lang === "ar" ? `body{font-family:'Noto Sans Arabic','Space Grotesk',system-ui,sans-serif}.hero .face,.brand b,.ic,.price,.pill{direction:ltr;unicode-bidi:isolate}h1{line-height:1.3}` : ""}</style></head><body>
<header><div class="wrap"><a class="brand" href="${q || "/"}"><b>&gt;_&lt;</b>${app}</a>
<nav><a class="h" href="#imkon">${t.navFeatures}</a><a class="h" href="#qanday">${t.navHow}</a><a class="h" href="#modellar">${t.navModels}</a><a class="h" href="#tarif">${t.navPlans}</a><a class="h" href="#faq">${t.navFaq}</a>${switcher}<a class="btn p" style="padding:8px 16px;font-size:14px" href="${play}">${t.navDownload}</a></nav></div></header>

<main class="wrap">
<div class="hero"><div class="face">&gt;_&lt;</div>
<h1>${app}</h1>
<div class="typed" id="ty" aria-live="off"></div>
<p class="lead">${t.lead}</p>
<div class="cta"><a class="btn p" href="${play}">${t.ctaPlay}</a><a class="btn" href="#modellar">${t.ctaModels}</a></div></div>

<section id="imkon"><h2>${t.featH}</h2><p class="sub">${t.featSub}</p>
<div class="grid">
<div class="card"><div class="ic">AI</div><h3>${t.f1h}</h3><p>${t.f1p}</p></div>
<div class="card"><div class="ic">&gt;_</div><h3>${t.f2h}</h3><p>${t.f2p}</p></div>
<div class="card"><div class="ic">$_</div><h3>${t.f3h}</h3><p>${t.f3p}</p></div>
<div class="card"><div class="ic">API</div><h3>${t.f4h}</h3><p>${t.f4p}</p></div>
<div class="card"><div class="ic">1B</div><h3>${t.f5h}</h3><p>${t.f5p}</p></div>
</div></section>

<section id="qanday"><h2>${t.howH}</h2><p class="sub">${t.howSub}</p>
<div class="steps">${t.steps.map(([a, b]) => `<div class="card step"><h3>${a}</h3><p>${b}</p></div>`).join("")}</div></section>

<section id="ram"><h2>${t.ramH}</h2><p class="sub">${t.ramSub}</p>
<div class="chips" id="chips" role="group" aria-label="${t.ramLabel}"></div><div id="rec" class="grid"></div><p class="note">${t.ramTip}</p></section>

<section id="modellar"><h2>${t.catH}</h2><p class="sub">${t.catSub}</p><div id="cat" class="err">${t.loading}</div></section>

<section id="tarif" class="plans"><h2>${t.plansH}</h2><p class="sub">${t.plansSub}</p>
<div class="grid">
<div class="card"><h3>${t.p1h}</h3>${price(env.PRICE_BASIC)}<p>${t.p1p}</p></div>
<div class="card hot"><h3>${t.p2h}</h3>${price(env.PRICE_STANDARD)}<p>${t.p2p}</p></div>
<div class="card"><h3>${t.p3h}</h3>${price(env.PRICE_PRO)}<p>${t.p3p}</p></div>
</div><p class="note">${t.plansNote}</p></section>

<section id="faq"><h2>${t.faqH}</h2><div style="margin-top:18px">${faqHtml}</div></section>
<script type="application/ld+json">${faqLd}</script>
</main>

<footer><div class="wrap"><span>© ${app}</span><a href="/privacy${lq}">${t.privacy}</a><a href="/delete-account${lq}">${t.del}</a>${mail ? `<a href="mailto:${mail}">${mail}</a>` : ""}</div></footer>

<script>
var D=${clientData};
function h(t,c,k){var e=document.createElement(t);if(c)e.className=c;(k||[]).forEach(function(x){if(x!=null)e.appendChild(typeof x=="string"?document.createTextNode(x):x)});return e}
function fsize(mb){return mb>=1024?(mb/1024).toFixed(1)+" GB":mb+" MB"}
function tierOf(m){var cap=m.paramsBillion>=3||(m.paramsBillion>=1.5&&m.family!="DEEPSEEK_R1");return !cap?0:m.paramsBillion<3?1:2}
var P=D.P,T=D.T,CAT=D.CAT,BG=D.BADGE||{},ALL=[],RAMSEL=4;
function bdg(m){return m.badge&&BG[m.badge]?h("span","pill "+(m.badge=="NEW"?"w":"ok"),[BG[m.badge]]):null}
function rec(gb){RAMSEL=gb;var c=document.getElementById("chips");c.textContent="";
 [2,3,4,6,8,12].forEach(function(g){var b=h("button","chip",[g+" GB"]);b.type="button";b.setAttribute("aria-pressed",g==gb?"true":"false");b.onclick=function(){rec(g)};c.appendChild(b)});
 var box=document.getElementById("rec");box.textContent="";
 var fit=ALL.filter(function(m){return m.baseRamMb<=gb*1024*0.5}).sort(function(a,b){return b.paramsBillion-a.paramsBillion||(b.stars||0)-(a.stars||0)}).slice(0,3);
 if(!fit.length){if(ALL.length)box.appendChild(h("p","err",[D.ramNone]));return}
 fit.forEach(function(m,i){box.appendChild(h("div","card m",[h("div","top",[h("span","n",[m.name]),i==0?h("span","pill ok",[D.ramBest]):null,bdg(m)]),h("p",0,[m.direction||""]),h("div","meta",[h("span","pill",[CAT[m.category]||m.category]),h("span","pill ok",[fsize(m.baseSizeMb)]),h("span","pill w",["RAM ~"+fsize(m.baseRamMb)])])]))})}
rec(4);
function sl(ms){return new Promise(function(r){setTimeout(r,ms)})}
(async function(){var ty=document.getElementById("ty"),i=0,k;if(window.matchMedia&&matchMedia("(prefers-reduced-motion: reduce)").matches){ty.textContent=P[0];return}
 while(true){var p=P[i%P.length];for(k=1;k<=p.length;k++){ty.textContent=p.slice(0,k);await sl(38)}await sl(2200);for(k=p.length-1;k>=0;k--){ty.textContent=p.slice(0,k);await sl(16)}await sl(350);i++}})();
fetch("/catalog").then(function(r){if(!r.ok)throw 0;return r.json()}).then(function(j){var box=document.getElementById("cat");box.textContent="";box.className="";
 var ms=j.models||[];ALL=ms;rec(RAMSEL);if(!ms.length){box.textContent=D.none;return}
 T.forEach(function(t,i){var l=ms.filter(function(m){return tierOf(m)==i});if(!l.length)return;
  box.appendChild(h("div","tier",[h("b",0,[t[0]]),h("span",0,[t[1]])]));var g=h("div","grid");
  l.forEach(function(m){g.appendChild(h("div","card m",[h("div","top",[h("span","n",[m.name]),bdg(m),m.stars?h("span","star",["★ "+m.stars]):null]),
   h("p",0,[m.direction||""]),h("div","meta",[h("span","pill",[CAT[m.category]||m.category]),h("span","pill ok",[fsize(m.baseSizeMb)]),h("span","pill w",["RAM ~"+fsize(m.baseRamMb)])])]))});
  box.appendChild(g)})}).catch(function(){var b=document.getElementById("cat");b.textContent=D.fail;b.className="err"});
</script></body></html>`);
}
