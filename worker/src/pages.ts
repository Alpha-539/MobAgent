import type { Env } from "./types";
import { html } from "./util";

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
const DATE = "2026-10-04"; // siyosat matni o'zgarganda yangilang

type Lang = "en" | "uz" | "ru" | "ar";
const LANGS: Record<Lang, string> = { en: "English", uz: "O'zbekcha", ru: "Русский", ar: "العربية" };
const href = (path: string, l: Lang) => (l === "en" ? path : `${path}?lang=${l}`);

function layout(env: Env, lang: Lang, title: string, path: string, body: string): Response {
  const rtl = lang === "ar";
  const switcher = (Object.keys(LANGS) as Lang[])
    .filter((l) => l !== lang)
    .map((l) => `<a class="mut lg" href="${href(path, l)}" hreflang="${l}">${LANGS[l]}</a>`)
    .join("");
  return html(`<!doctype html><html lang="${lang}" dir="${rtl ? "rtl" : "ltr"}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600${rtl ? "&family=Noto+Sans+Arabic:wght@400;500;700" : ""}&display=swap">
<style>:root{color-scheme:dark light;--bg:#121118;--card:#1c1b25;--line:#32303f;--fg:#f3f2f8;--fg2:#b1afc2;--mut:#7c798e;--acc:#8b7cf6;--accT:#dcd6ff}
@media(prefers-color-scheme:light){:root{--bg:#faf9fd;--card:#fff;--line:#e4e1ef;--fg:#1e1b2e;--fg2:#5c586f;--mut:#928ea4;--acc:#5b3fd9;--accT:#4b31c9}}
body{margin:0;background:var(--bg);color:var(--fg2);font:16px/1.65 ${rtl ? "'Noto Sans Arabic'," : ""}'Space Grotesk',system-ui,sans-serif}
main{max-width:760px;margin:auto;padding:20px 16px 56px}
.top{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:20px}.sw{display:flex;gap:14px;flex-wrap:wrap}
.brand{display:flex;align-items:center;gap:10px;color:var(--fg);font-weight:600}
.logo{height:32px;padding:0 9px;border-radius:10px;background:linear-gradient(135deg,var(--acc),#6e5ee8);color:#fff;display:grid;place-items:center;font-size:13px;font-weight:700;white-space:nowrap}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:6px 22px 22px}
h1{font-size:24px;color:var(--fg);margin:22px 0 4px}h2{font-size:17px;color:var(--fg);margin-top:28px}
a{color:var(--accT);text-decoration:none}a:hover{text-decoration:underline}li{margin:4px 0}.mut{color:var(--mut);font-size:14px}
.dr{display:grid;gap:12px;margin-top:8px}.dr label{display:flex;flex-direction:column;gap:4px;font-size:13px;color:var(--mut)}.dr input{font:inherit;padding:10px;border:1px solid var(--line);border-radius:10px;background:transparent;color:var(--fg)}.dr button{font:inherit;padding:10px 16px;border:0;border-radius:10px;background:var(--acc);color:#fff;cursor:pointer;justify-self:start}
code{font-family:'JetBrains Mono',monospace;font-size:13px}${rtl ? "a[href^=mailto]{direction:ltr;unicode-bidi:isolate}" : ""}</style></head>
<body><main><div class="top"><div class="brand"><div class="logo">&gt;_&lt;</div>${esc(env.APP_NAME)}</div><div class="sw">${switcher}</div></div><div class="card">${body}</div></main></body></html>`);
}

/** Hisobni o'chirish formasi uchun skript (ru/ar). */
const drScript = (ok: string, err: string) => `<script>document.getElementById("dr").onsubmit=function(e){e.preventDefault();var m=document.getElementById("drm");m.textContent="…";
fetch("/delete-request",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:document.getElementById("dre").value,note:document.getElementById("drn").value})})
.then(function(r){return r.json().then(function(j){m.textContent=r.ok?${JSON.stringify(ok)}:(j.error||${JSON.stringify(err)})})}).catch(function(){m.textContent=${JSON.stringify(err)}})}</script>`;

const pick = (l: string | null): Lang => (l === "uz" || l === "ru" || l === "ar" ? l : "en");

export function privacyPage(env: Env, langParam: string | null): Response {
  const lang = pick(langParam);
  const app = esc(env.APP_NAME);
  const mail = esc(env.CONTACT_EMAIL);
  if (lang === "uz") {
    return layout(env, "uz", `${app} — Maxfiylik siyosati`, "/privacy", `
<h1>${app} — Maxfiylik siyosati</h1><p class="mut">Oxirgi yangilanish: ${DATE}</p>
<p>Bu siyosat ${app} Android ilovasi qanday ma'lumotlarni qayta ishlashini tushuntiradi.</p>
<h2>1. Offline rejim</h2>
<p>Offline modellar telefoningizda ishlaydi. Offline suhbatlaringiz, yozgan buyruqlaringiz va fayllaringiz telefondan tashqariga chiqmaydi.</p>
<h2>2. Qaysi ma'lumotlarni yig'amiz</h2>
<ul>
<li><b>Hisob:</b> email, ism (Google bilan kirsangiz — Google profilingizdagi ism va email) va Firebase foydalanuvchi identifikatori (Firebase Authentication, Google).</li>
<li><b>Onlayn suhbatlar tarixi:</b> onlayn rejimdagi suhbatlar matni hisobingizga bog'lab Firebase Firestore'da saqlanadi (qurilmalar orasida sinxronlash uchun). Rasmlar buluta yuklanmaydi.</li>
<li><b>API kalitlari:</b> o'zingiz kiritgan AI-provayder kalitlari telefonda saqlanadi, hisobga bog'lab sinxronlash yoqilgan bo'lsa shifrlangan nusxasi Firestore'da turadi. Shifrlash kaliti ham shu hisob doirasida saqlanadi, shuning uchun bu ma'lumotlar bazasi administratoridan himoya qilmaydi — faqat tasodifiy oshkor bo'lishdan saqlaydi.</li>
<li><b>Yulduzchalar:</b> qaysi modelga yulduzcha qo'ygansiz — foydalanuvchi identifikatoringiz bilan bog'lanadi.</li>
<li><b>Fikrlar:</b> ilovada o'zingiz yuborgan fikr matni, turi, ixtiyoriy aloqa ma'lumoti, ilova versiyasi va tili — foydalanuvchi identifikatoringiz bilan bog'lanadi.</li>
<li><b>Yig'ma statistika:</b> ilova ochilishi, model yuklashlari va xatolar sonlari. Bular shaxsga bog'lanmaydi. Kunlik faol foydalanuvchilar soni har kuni o'zgaradigan bir tomonlama xesh orqali sanaladi; identifikatoringiz o'zi saqlanmaydi va kunlar orasida bog'lab bo'lmaydi.</li>
<li><b>Firebase Analytics (Google):</b> ilova umumiy foydalanish ma'lumotlarini (qurilma modeli, OS versiyasi, ilova ichidagi hodisalar, ilova nusxasi identifikatori) yig'ishi mumkin.</li>
<li><b>Texnik jurnallar:</b> serverimiz (Cloudflare) so'rovlarning IP manzili kabi texnik ma'lumotlarini qisqa muddat qayd etishi mumkin. Biz IP manzillarni o'z bazamizda saqlamaymiz.</li>
</ul>
<h2>3. Onlayn rejimda suhbatlaringiz kimga boradi</h2>
<p>Onlayn rejimda yozganlaringiz (shu jumladan agent o'qigan fayl mazmuni) <b>siz tanlagan AI-provayderga</b> (masalan OpenAI, Anthropic, Google, DeepSeek va boshqalar) <b>sizning API kalitingiz bilan</b> to'g'ridan-to'g'ri qurilmangizdan yuboriladi. Ular ma'lumotni o'z siyosatiga ko'ra qayta ishlaydi — ularning siyosatini o'qing. Modellar katalogi uchun ilova bizning serverimizga murojaat qiladi; bu so'rovda suhbat matni bo'lmaydi.</p>
<p><b>Pullik tarif (MobAgent agents):</b> bu rejimda yozganlaringiz (rasmlar va agent o'qigan fayl mazmuni ham) bizning serverimiz orqali <b>AI-provayderga (Anthropic, OpenAI yoki Google)</b> uzatiladi; provayder uni o'z siyosatiga ko'ra qayta ishlaydi. Serverimiz suhbat matnini <b>saqlamaydi va log qilmaydi</b>; faqat so'rov narxini hisoblash uchun token sonini va foydalanish limitlarini (5 soatlik, haftalik, oylik) hisobingiz bo'yicha yuritadi. Obunani tekshirish uchun Google Play xarid tokeni, mahsulot va amal qilish muddati hisobingizga bog'lab saqlanadi. <b>Bepul model:</b> bu modelda yozganlaringiz bizning serverimiz orqali <b>uchinchi tomon AI xizmatiga</b> uzatiladi; u suhbatni o'z siyosatiga ko'ra saqlashi mumkin, shuning uchun unda maxfiy ma'lumot yozmang. Serverimiz matnni saqlamaydi, faqat 5 soatlik xabarlar sonini hisobingiz bo'yicha yuritadi.</p>
<h2>4. Model yuklash</h2>
<p>Model fayllari to'g'ridan-to'g'ri Hugging Face (yoki katalogda ko'rsatilgan boshqa manba) serveridan yuklanadi; ular sizning IP manzilingizni ko'radi.</p>
<h2>5. Ma'lumotni kimlar bilan ulashamiz</h2>
<p>Sotmaymiz va reklama uchun ulashmaymiz. Faqat xizmat ko'rsatuvchilar: Google (Firebase), Cloudflare (server va baza) va siz o'zingiz tanlagan AI-provayder.</p>
<h2>6. Saqlash va o'chirish</h2>
<p>Ma'lumotlar hisobingiz mavjud bo'lguncha saqlanadi. Ilovada <b>Profil → Hisobni o'chirish</b> orqali hisob, bulutdagi suhbatlar, sinxronlangan kalitlar, yulduzchalar va fikrlar o'chiriladi. Batafsil: <a href="/delete-account">hisobni o'chirish sahifasi</a>.</p>
<h2>7. Bolalar</h2><p>Ilova 13 yoshgacha bolalar uchun mo'ljallanmagan va ulardan ongli ravishda ma'lumot yig'maymiz.</p>
<h2>8. O'zgarishlar va aloqa</h2><p>Siyosat o'zgarsa, shu sahifa yangilanadi. Savollar: <a href="mailto:${mail}">${mail}</a></p>`);
  }
  if (lang === "ru") {
    return layout(env, "ru", `${app} — Политика конфиденциальности`, "/privacy", `
<h1>${app} — Политика конфиденциальности</h1><p class="mut">Последнее обновление: ${DATE}</p>
<p>Эта политика объясняет, какие данные обрабатывает Android-приложение ${app} и зачем.</p>
<h2>1. Офлайн-режим</h2>
<p>Офлайн-модели работают на вашем телефоне. Ваши офлайн-чаты, команды и файлы не покидают устройство.</p>
<h2>2. Какие данные мы собираем</h2>
<ul>
<li><b>Аккаунт:</b> email, имя (при входе через Google — имя и email из вашего профиля Google) и идентификатор пользователя Firebase (Firebase Authentication, Google).</li>
<li><b>История онлайн-чатов:</b> чаты в онлайн-режиме хранятся в Firebase Firestore и привязаны к вашему аккаунту, чтобы синхронизироваться между устройствами. Изображения в облако не загружаются.</li>
<li><b>API-ключи:</b> ключи ИИ-провайдеров, которые вы вводите, хранятся на устройстве; если включена синхронизация с аккаунтом, зашифрованная копия хранится в Firestore. Ключ шифрования хранится в том же пространстве аккаунта, поэтому это защищает от случайного раскрытия, но не от администратора базы данных.</li>
<li><b>Звёзды:</b> какие модели вы отметили звёздой — связано с вашим идентификатором пользователя.</li>
<li><b>Агрегированная статистика:</b> число запусков приложения, загрузок моделей и ошибок. Она не связана с вами. Ежедневные активные пользователи считаются с помощью односторонней хеш-функции, которая меняется каждый день; сам ваш идентификатор не хранится и не может быть связан между днями.</li>
<li><b>Firebase Analytics (Google):</b> может собирать общие данные об использовании (модель устройства, версия ОС, события в приложении, идентификатор экземпляра приложения).</li>
<li><b>Технические журналы:</b> наш серверный провайдер (Cloudflare) может кратковременно записывать технические данные запросов, например IP-адреса. Мы не храним IP-адреса в собственной базе данных.</li>
</ul>
<h2>3. Куда попадают ваши онлайн-чаты</h2>
<p>В онлайн-режиме то, что вы пишете (включая содержимое файлов, которые читает агент), отправляется <b>напрямую с вашего устройства выбранному вами ИИ-провайдеру</b> (например, OpenAI, Anthropic, Google, DeepSeek) <b>с вашим собственным API-ключом</b>. Провайдер обрабатывает эти данные по своей политике — рекомендуем её прочитать. За каталогом моделей приложение обращается к нашему серверу; в этом запросе нет текста чата.</p>
<p><b>Платный тариф (MobAgent agents):</b> в этом режиме то, что вы пишете (включая изображения и содержимое файлов, которые читает агент), передаётся через наш сервер <b>ИИ-провайдеру (Anthropic, OpenAI или Google)</b>, который обрабатывает это по своей политике. Наш сервер <b>не хранит и не логирует текст чатов</b>; он ведёт по вашему аккаунту только число токенов и лимиты использования (5-часовой, недельный, месячный) для учёта запросов. Для проверки подписки мы храним токен покупки Google Play, продукт и срок действия, привязанные к вашему аккаунту. <b>Бесплатная модель:</b> то, что вы пишете, передаётся через наш сервер <b>стороннему ИИ-сервису</b>, который может хранить переписку по своей политике — не отправляйте конфиденциальные данные. Наш сервер текст не хранит, а ведёт по аккаунту только число сообщений за 5 часов.</p>
<h2>4. Загрузка моделей</h2>
<p>Файлы моделей загружаются напрямую с Hugging Face (или другого источника, указанного в каталоге), который видит ваш IP-адрес.</p>
<h2>5. Передача данных</h2>
<p>Мы не продаём ваши данные и не передаём их для рекламы. Задействованы только поставщики услуг: Google (Firebase), Cloudflare (сервер и база данных) и выбранный вами ИИ-провайдер.</p>
<h2>6. Хранение и удаление</h2>
<p>Данные хранятся, пока существует ваш аккаунт. В приложении <b>Профиль → Удалить аккаунт</b> удаляет аккаунт, облачные чаты, синхронизированные ключи и звёзды. Подробнее: <a href="/delete-account?lang=ru">страница удаления аккаунта</a>.</p>
<h2>7. Дети</h2><p>Приложение не предназначено для детей младше 13 лет, и мы сознательно не собираем их данные.</p>
<h2>8. Изменения и контакты</h2><p>Если политика изменится, эта страница будет обновлена. Вопросы: <a href="mailto:${mail}">${mail}</a></p>`);
  }
  if (lang === "ar") {
    return layout(env, "ar", `${app} — سياسة الخصوصية`, "/privacy", `
<h1>${app} — سياسة الخصوصية</h1><p class="mut">آخر تحديث: ${DATE}</p>
<p>توضح هذه السياسة البيانات التي يعالجها تطبيق ${app} للأندرويد ولماذا.</p>
<h2>1. الوضع دون اتصال</h2>
<p>تعمل النماذج دون اتصال على هاتفك. لا تغادر محادثاتك وأوامرك وملفاتك دون اتصال جهازك.</p>
<h2>2. البيانات التي نجمعها</h2>
<ul>
<li><b>الحساب:</b> البريد الإلكتروني والاسم (عند تسجيل الدخول عبر Google: الاسم والبريد من ملفك الشخصي في Google) ومعرّف مستخدم Firebase (Firebase Authentication من Google).</li>
<li><b>سجل المحادثات عبر الإنترنت:</b> تُخزَّن محادثات الوضع الإلكتروني في Firebase Firestore وترتبط بحسابك لتتم مزامنتها بين أجهزتك. لا يتم رفع الصور إلى السحابة.</li>
<li><b>مفاتيح API:</b> تُخزَّن مفاتيح مزوّدي الذكاء الاصطناعي التي تدخلها على الجهاز؛ وإذا كانت مزامنة الحساب مفعّلة تُحفظ نسخة مشفّرة في Firestore. يُخزَّن مفتاح التشفير في مساحة الحساب نفسها، لذا فهو يحمي من الكشف العرضي لا من مسؤول قاعدة البيانات.</li>
<li><b>النجوم:</b> النماذج التي وضعت عليها نجمة، وهي مرتبطة بمعرّف المستخدم الخاص بك.</li>
<li><b>إحصاءات مجمّعة:</b> أعداد مرات فتح التطبيق وتنزيلات النماذج والأخطاء، وهي غير مرتبطة بك. يُحتسب المستخدمون النشطون يوميًا باستخدام تجزئة أحادية الاتجاه تتغير كل يوم؛ ولا يُخزَّن معرّفك نفسه ولا يمكن ربطه بين الأيام.</li>
<li><b>Firebase Analytics (من Google):</b> قد يجمع بيانات استخدام عامة (طراز الجهاز وإصدار النظام والأحداث داخل التطبيق ومعرّف نسخة التطبيق).</li>
<li><b>السجلات التقنية:</b> قد يسجّل مزوّد الخادم لدينا (Cloudflare) لفترة وجيزة بيانات تقنية للطلبات مثل عناوين IP. نحن لا نخزّن عناوين IP في قاعدة بياناتنا.</li>
</ul>
<h2>3. أين تذهب محادثاتك عبر الإنترنت</h2>
<p>في الوضع الإلكتروني، ما تكتبه (بما في ذلك محتوى الملفات التي يقرؤها الوكيل) يُرسَل <b>مباشرةً من جهازك إلى مزوّد الذكاء الاصطناعي الذي اخترته</b> (مثل OpenAI وAnthropic وGoogle وDeepSeek) <b>باستخدام مفتاح API الخاص بك</b>. يعالج المزوّد البيانات وفق سياسته الخاصة، لذا يُنصح بقراءتها. يتصل التطبيق بخادمنا للحصول على كتالوج النماذج؛ ولا يحتوي هذا الطلب على نص المحادثة.</p>
<p><b>الخطة المدفوعة (MobAgent agents):</b> في هذا الوضع يُمرَّر ما تكتبه (بما في ذلك الصور ومحتوى الملفات التي يقرؤها الوكيل) عبر خادمنا إلى <b>مزوّد ذكاء اصطناعي (Anthropic أو OpenAI أو Google)</b> يعالجه وفق سياسته الخاصة. <b>لا يخزّن خادمنا نص المحادثات ولا يسجّله</b>؛ بل يحتفظ فقط بعدد الرموز (tokens) وحدود الاستخدام (5 ساعات وأسبوعي وشهري) لكل حساب لاحتساب الطلبات. وللتحقق من اشتراكك نخزّن رمز الشراء من Google Play والمنتج وتاريخ الانتهاء مرتبطة بحسابك. <b>النموذج المجاني:</b> يُمرَّر ما تكتبه عبر خادمنا إلى <b>خدمة ذكاء اصطناعي تابعة لجهة خارجية</b> قد تحتفظ بالمحادثة وفق سياستها، فلا تكتب بيانات سرية. لا يخزّن خادمنا النص، بل يحتفظ فقط بعدد الرسائل خلال 5 ساعات لكل حساب.</p>
<h2>4. تنزيل النماذج</h2>
<p>تُنزَّل ملفات النماذج مباشرةً من Hugging Face (أو مصدر آخر مذكور في الكتالوج)، وهو يرى عنوان IP الخاص بك.</p>
<h2>5. المشاركة</h2>
<p>لا نبيع بياناتك ولا نشاركها لأغراض إعلانية. المعنيّون فقط مزوّدو الخدمات: Google (Firebase) وCloudflare (الخادم وقاعدة البيانات) ومزوّد الذكاء الاصطناعي الذي تختاره.</p>
<h2>6. الاحتفاظ والحذف</h2>
<p>تُحفظ البيانات ما دام حسابك موجودًا. في التطبيق، يؤدي <b>الملف الشخصي ← حذف الحساب</b> إلى حذف حسابك والمحادثات السحابية والمفاتيح المتزامنة والنجوم. التفاصيل: <a href="/delete-account?lang=ar">صفحة حذف الحساب</a>.</p>
<h2>7. الأطفال</h2><p>التطبيق غير موجّه للأطفال دون 13 عامًا، ولا نجمع بياناتهم عن علم.</p>
<h2>8. التغييرات والتواصل</h2><p>إذا تغيّرت هذه السياسة فسيتم تحديث هذه الصفحة. للاستفسارات: <a href="mailto:${mail}">${mail}</a></p>`);
  }
  return layout(env, "en", `${app} — Privacy Policy`, "/privacy", `
<h1>${app} — Privacy Policy</h1><p class="mut">Last updated: ${DATE}</p>
<p>This policy explains what data the ${app} Android app processes and why.</p>
<h2>1. Offline mode</h2>
<p>Offline models run on your phone. Your offline chats, commands and files do not leave the device.</p>
<h2>2. Data we collect</h2>
<ul>
<li><b>Account:</b> email, display name (for Google sign-in, the name and email of your Google profile) and a Firebase user ID (Firebase Authentication, Google).</li>
<li><b>Online chat history:</b> chats in online mode are stored in Firebase Firestore linked to your account so they sync across your devices. Images are not uploaded.</li>
<li><b>API keys:</b> AI-provider keys you enter are stored on the device; if account sync is on, an encrypted copy is kept in Firestore. The encryption key is stored within the same account space, so this protects against accidental exposure, not against the database administrator.</li>
<li><b>Stars:</b> which models you starred, linked to your user ID.</li>
<li><b>Feedback and AI-reply reports:</b> the feedback text you send from the app, its type, optional contact detail, app version and language — linked to your user ID. If you report an AI reply (flag icon), the reason you choose, your optional comment and that single AI reply are sent so we can review it; the rest of your chat is not sent.</li>
<li><b>Aggregate statistics:</b> counts of app opens, model downloads and errors, not linked to you. Daily active users are counted using a one-way hash that changes every day; your ID itself is not stored and cannot be linked across days.</li>
<li><b>Firebase Analytics (Google):</b> may collect general usage data (device model, OS version, in-app events, app-instance ID).</li>
<li><b>Technical logs:</b> our server provider (Cloudflare) may briefly record technical request data such as IP addresses. We do not store IP addresses in our own database.</li>
</ul>
<h2>3. Where your online chats go</h2>
<p>In online mode, what you write (including file contents the agent reads) is sent <b>directly from your device to the AI provider you chose</b> (e.g. OpenAI, Anthropic, Google, DeepSeek) <b>using your own API key</b>. The provider processes it under its own policy, so please read it. The app contacts our server for the model catalog; that request contains no chat text.</p>
<p><b>Paid plan (MobAgent agents):</b> in this mode what you write (including images and file contents the agent reads) is relayed through our server to an <b>AI provider (Anthropic, OpenAI or Google)</b>, which processes it under its own policy. Our server <b>does not store or log chat text</b>; it only keeps token counts and usage limits (5-hour, weekly, monthly) per account to meter requests. To verify your subscription we store the Google Play purchase token, product and expiry linked to your account.</p>
<h2>4. Model downloads</h2>
<p>Model files are downloaded directly from Hugging Face (or another source listed in the catalog), which can see your IP address.</p>
<h2>5. Sharing</h2>
<p>We do not sell your data or share it for advertising. Only service providers are involved: Google (Firebase), Cloudflare (server and database) and the AI provider you choose.</p>
<h2>6. Retention and deletion</h2>
<p>Data is kept while your account exists. In the app, <b>Profile → Delete account</b> removes your account, cloud chats, synced keys, stars and feedback. Details: <a href="/delete-account">account deletion page</a>.</p>
<h2>7. Children</h2><p>The app is not directed to children under 13 and we do not knowingly collect their data.</p>
<h2>8. Changes and contact</h2><p>If this policy changes, this page will be updated. Questions: <a href="mailto:${mail}">${mail}</a></p>`);
}

export function deleteAccountPage(env: Env, langParam: string | null): Response {
  const lang = pick(langParam);
  const app = esc(env.APP_NAME);
  const mail = esc(env.CONTACT_EMAIL);
  if (lang === "uz") {
    return layout(env, "uz", `${app} — Hisobni o'chirish`, "/delete-account", `
<h1>${app} — Hisobni va ma'lumotlarni o'chirish</h1>
<h2>Ilova ichida (eng tez usul)</h2>
<ol><li>${app} ilovasini oching va hisobingizga kiring.</li><li><b>Profil → Hisobni o'chirish</b> ni tanlang.</li><li>Tasdiqlang (parol yoki Google orqali).</li></ol>
<h2>Nimalar o'chiriladi</h2>
<ul><li>Hisobingiz (email, ism, Firebase identifikatori)</li><li>Bulutdagi onlayn suhbatlar tarixi</li><li>Sinxronlangan (shifrlangan) API kalitlari</li><li>Modellarga qo'ygan yulduzchalaringiz va yuborgan fikrlaringiz</li><li>Serverdagi obuna yozuvi va foydalanish hisobi (Google Play'dagi obunani esa o'zingiz bekor qilishingiz kerak)</li></ul>
<p>Telefondagi ma'lumotlar (yuklangan modellar, offline suhbatlar) ilovani o'chirganingizda yoki ilova sozlamalaridan xotirani tozalaganingizda o'chadi. Shaxsga bog'lanmagan yig'ma statistika saqlanib qoladi.</p>
<h2>Ilovaga kira olmasangiz</h2>
<p>Hisobga ulangan email manzildan <a href="mailto:${mail}?subject=Delete%20account">${mail}</a> ga "Delete account" mavzusi bilan yozing. So'rovni 30 kun ichida bajaramiz.</p>
<h2>Veb orqali so'rov yuborish</h2>
<form id="dr" class="dr"><label>Hisobingizga ulangan email<input id="dre" type="email" required maxlength="254" autocomplete="email"></label>
<label>Izoh (ixtiyoriy)<input id="drn" maxlength="500"></label><button type="submit">So'rov yuborish</button><p id="drm" class="mut"></p></form>
<script>document.getElementById("dr").onsubmit=function(e){e.preventDefault();var m=document.getElementById("drm");m.textContent="…";
fetch("/delete-request",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:document.getElementById("dre").value,note:document.getElementById("drn").value})})
.then(function(r){return r.json().then(function(j){m.textContent=r.ok?"So'rov qabul qilindi. Egalikni tasdiqlash uchun sizga shu email orqali yozamiz va 30 kun ichida hisobingiz hamda ma'lumotlaringiz o'chiriladi.":(j.error||"Xatolik. Qayta urinib ko'ring.")})}).catch(function(){m.textContent="Xatolik. Qayta urinib ko'ring."})}</script>`);
  }
  if (lang === "ru") {
    return layout(env, "ru", `${app} — Удаление аккаунта`, "/delete-account", `
<h1>${app} — Удаление аккаунта и данных</h1>
<h2>В приложении (быстрее всего)</h2>
<ol><li>Откройте ${app} и войдите в аккаунт.</li><li>Перейдите в <b>Профиль → Удалить аккаунт</b>.</li><li>Подтвердите действие (паролем или через Google).</li></ol>
<h2>Что удаляется</h2>
<ul><li>Ваш аккаунт (email, имя, идентификатор пользователя Firebase)</li><li>История онлайн-чатов в облаке</li><li>Синхронизированные (зашифрованные) API-ключи</li><li>Ваши звёзды на моделях</li><li>Запись о подписке на сервере и счётчики использования (подписку в Google Play нужно отменить самостоятельно)</li></ul>
<p>Данные на телефоне (загруженные модели, офлайн-чаты) удаляются при удалении приложения или очистке его хранилища. Агрегированная статистика, не связанная с вами, сохраняется.</p>
<h2>Если нет доступа к приложению</h2>
<p>Напишите на <a href="mailto:${mail}?subject=Delete%20account">${mail}</a> с адреса, привязанного к аккаунту, с темой «Delete account». Мы обработаем запрос в течение 30 дней.</p>
<h2>Запрос на удаление онлайн</h2>
<form id="dr" class="dr"><label>Email, привязанный к аккаунту<input id="dre" type="email" required maxlength="254" autocomplete="email"></label>
<label>Комментарий (необязательно)<input id="drn" maxlength="500"></label><button type="submit">Отправить запрос</button><p id="drm" class="mut"></p></form>
${drScript("Запрос получен. Мы напишем вам на этот email для подтверждения владения, и в течение 30 дней ваш аккаунт и данные будут удалены.", "Произошла ошибка. Попробуйте ещё раз.")}`);
  }
  if (lang === "ar") {
    return layout(env, "ar", `${app} — حذف الحساب`, "/delete-account", `
<h1>${app} — حذف حسابك وبياناتك</h1>
<h2>داخل التطبيق (الأسرع)</h2>
<ol><li>افتح ${app} وسجّل الدخول.</li><li>انتقل إلى <b>الملف الشخصي ← حذف الحساب</b>.</li><li>أكّد العملية (بكلمة المرور أو عبر Google).</li></ol>
<h2>ما الذي يُحذف</h2>
<ul><li>حسابك (البريد الإلكتروني والاسم ومعرّف مستخدم Firebase)</li><li>سجل المحادثات عبر الإنترنت المخزَّن في السحابة</li><li>مفاتيح API المتزامنة (المشفّرة)</li><li>النجوم التي وضعتها على النماذج</li><li>سجل الاشتراك على الخادم وعدّادات الاستخدام (ويجب عليك إلغاء الاشتراك في Google Play بنفسك)</li></ul>
<p>تُحذف البيانات الموجودة على هاتفك (النماذج المنزَّلة والمحادثات دون اتصال) عند إلغاء تثبيت التطبيق أو مسح بياناته. وتبقى الإحصاءات المجمّعة غير المرتبطة بك.</p>
<h2>إذا لم تتمكن من الدخول إلى التطبيق</h2>
<p>راسلنا على <a href="mailto:${mail}?subject=Delete%20account">${mail}</a> من العنوان المرتبط بحسابك بعنوان "Delete account". سنعالج الطلب خلال 30 يومًا.</p>
<h2>طلب الحذف عبر الإنترنت</h2>
<form id="dr" class="dr"><label>البريد الإلكتروني المرتبط بحسابك<input id="dre" type="email" required maxlength="254" autocomplete="email" dir="ltr"></label>
<label>ملاحظة (اختياري)<input id="drn" maxlength="500"></label><button type="submit">إرسال الطلب</button><p id="drm" class="mut"></p></form>
${drScript("تم استلام الطلب. سنراسلك على هذا البريد للتحقق من ملكية الحساب، وسيتم حذف حسابك وبياناتك خلال 30 يومًا.", "حدث خطأ. يرجى المحاولة مرة أخرى.")}`);
  }
  return layout(env, "en", `${app} — Delete account`, "/delete-account", `
<h1>${app} — Delete your account and data</h1>
<h2>In the app (fastest)</h2>
<ol><li>Open ${app} and sign in.</li><li>Go to <b>Profile → Delete account</b>.</li><li>Confirm (password or Google).</li></ol>
<h2>What is deleted</h2>
<ul><li>Your account (email, name, Firebase user ID)</li><li>Online chat history stored in the cloud</li><li>Synced (encrypted) API keys</li><li>Your model stars and the feedback you sent</li><li>Server-side subscription record and usage counters (you must still cancel the subscription in Google Play yourself)</li></ul>
<p>Data on your phone (downloaded models, offline chats) is removed when you uninstall the app or clear its storage. Aggregate statistics that are not linked to you are retained.</p>
<h2>If you cannot access the app</h2>
<p>Email <a href="mailto:${mail}?subject=Delete%20account">${mail}</a> from the address linked to your account with the subject "Delete account". We will process the request within 30 days.</p>
<h2>Request deletion online</h2>
<form id="dr" class="dr"><label>Email linked to your account<input id="dre" type="email" required maxlength="254" autocomplete="email"></label>
<label>Note (optional)<input id="drn" maxlength="500"></label><button type="submit">Send request</button><p id="drm" class="mut"></p></form>
<script>document.getElementById("dr").onsubmit=function(e){e.preventDefault();var m=document.getElementById("drm");m.textContent="…";
fetch("/delete-request",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:document.getElementById("dre").value,note:document.getElementById("drn").value})})
.then(function(r){return r.json().then(function(j){m.textContent=r.ok?"Request received. We will email you to confirm ownership, and your account and data will be deleted within 30 days.":(j.error||"Something went wrong. Please try again.")})}).catch(function(){m.textContent="Something went wrong. Please try again."})}</script>`);
}
