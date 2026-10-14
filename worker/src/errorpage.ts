import type { Env } from "./types";
import { html } from "./util";

/**
 * Brauzer ochganda ko'rinadigan chiroyli xato sahifalari (404, 403, 429, 500 ...).
 * Faqat brauzer (Accept: text/html) uchun; ilova va API JSON javobini oladi.
 */
type Lang = "en" | "uz" | "ru" | "ar";
const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

interface Msg { title: string; text: string }
interface Pack { msgs: Record<string, Msg>; c4: Msg; c5: Msg; back: string; retry: string; more: string; home: string; res: string }

const P: Record<Lang, Pack> = {
  uz: {
    msgs: {
      "400": { title: "Noto'g'ri so'rov", text: "So'rovni tushuna olmadik. Havolani tekshirib, qayta urinib ko'ring." },
      "401": { title: "Kirish kerak", text: "Bu sahifani ko'rish uchun hisobingizga kirishingiz kerak." },
      "403": { title: "Ruxsat yo'q", text: "Bu sahifaga kirish huquqingiz yo'q." },
      "404": { title: "Sahifa topilmadi", text: "Siz izlagan sahifa mavjud emas yoki ko'chirilgan bo'lishi mumkin." },
      "405": { title: "Usul ruxsat etilmagan", text: "Bu manzilga bunday so'rov yuborib bo'lmaydi." },
      "429": { title: "Juda ko'p urinish", text: "Qisqa vaqtda juda ko'p so'rov yuborildi. Birozdan so'ng qayta urinib ko'ring." },
      "500": { title: "Server xatosi", text: "Bizning tomonimizda nimadir noto'g'ri ketdi. Bir ozdan keyin qayta urinib ko'ring." },
      "502": { title: "Xizmat javob bermadi", text: "Xizmat vaqtincha ishlamayapti. Birozdan so'ng qayta urinib ko'ring." },
      "503": { title: "Xizmat band", text: "Xizmat vaqtincha mavjud emas. Birozdan so'ng qayta urinib ko'ring." },
      "504": { title: "Javob kechikdi", text: "Server vaqtida javob bera olmadi. Qayta urinib ko'ring." },
    },
    c4: { title: "So'rovda xatolik", text: "So'rovni bajarib bo'lmadi." },
    c5: { title: "Server xatosi", text: "Bizning tomonimizda nimadir noto'g'ri ketdi. Birozdan so'ng qayta urinib ko'ring." },
    back: "Orqaga", retry: "Qayta urinish", more: "Xato kodi", home: "Bosh sahifa", res: "Natija",
  },
  ru: {
    msgs: {
      "400": { title: "Неверный запрос", text: "Мы не смогли понять запрос. Проверьте ссылку и попробуйте снова." },
      "401": { title: "Нужен вход", text: "Чтобы открыть эту страницу, войдите в аккаунт." },
      "403": { title: "Доступ запрещён", text: "У вас нет прав для просмотра этой страницы." },
      "404": { title: "Страница не найдена", text: "Такой страницы нет, либо она была перемещена." },
      "405": { title: "Метод не разрешён", text: "На этот адрес нельзя отправить такой запрос." },
      "429": { title: "Слишком много попыток", text: "За короткое время отправлено слишком много запросов. Попробуйте чуть позже." },
      "500": { title: "Ошибка сервера", text: "С нашей стороны что-то пошло не так. Попробуйте чуть позже." },
      "502": { title: "Сервис не ответил", text: "Сервис временно недоступен. Попробуйте чуть позже." },
      "503": { title: "Сервис занят", text: "Сервис временно недоступен. Попробуйте чуть позже." },
      "504": { title: "Ответ задержался", text: "Сервер не успел ответить. Попробуйте ещё раз." },
    },
    c4: { title: "Ошибка запроса", text: "Не удалось выполнить запрос." },
    c5: { title: "Ошибка сервера", text: "С нашей стороны что-то пошло не так. Попробуйте чуть позже." },
    back: "Назад", retry: "Повторить", more: "Код ошибки", home: "На главную", res: "Результат",
  },
  en: {
    msgs: {
      "400": { title: "Bad request", text: "We couldn't understand that request. Check the link and try again." },
      "401": { title: "Sign-in required", text: "Please sign in to view this page." },
      "403": { title: "Access denied", text: "You don't have permission to view this page." },
      "404": { title: "Page not found", text: "The page you're looking for doesn't exist or may have been moved." },
      "405": { title: "Method not allowed", text: "This address doesn't accept that kind of request." },
      "429": { title: "Too many attempts", text: "Too many requests in a short time. Please try again in a moment." },
      "500": { title: "Server error", text: "Something went wrong on our side. Please try again shortly." },
      "502": { title: "Service didn't respond", text: "The service is temporarily unavailable. Please try again shortly." },
      "503": { title: "Service busy", text: "The service is temporarily unavailable. Please try again shortly." },
      "504": { title: "Response timed out", text: "The server took too long to respond. Please try again." },
    },
    c4: { title: "Request error", text: "We couldn't complete that request." },
    c5: { title: "Server error", text: "Something went wrong on our side. Please try again shortly." },
    back: "Go back", retry: "Try again", more: "Error code", home: "Home", res: "Result",
  },
  ar: {
    msgs: {
      "400": { title: "طلب غير صالح", text: "لم نتمكن من فهم الطلب. تحقق من الرابط وحاول مرة أخرى." },
      "401": { title: "تسجيل الدخول مطلوب", text: "يرجى تسجيل الدخول لعرض هذه الصفحة." },
      "403": { title: "الوصول مرفوض", text: "ليست لديك صلاحية لعرض هذه الصفحة." },
      "404": { title: "الصفحة غير موجودة", text: "الصفحة التي تبحث عنها غير موجودة أو ربما تم نقلها." },
      "405": { title: "الطريقة غير مسموحة", text: "لا يقبل هذا العنوان هذا النوع من الطلبات." },
      "429": { title: "محاولات كثيرة", text: "تم إرسال طلبات كثيرة في وقت قصير. حاول بعد قليل." },
      "500": { title: "خطأ في الخادم", text: "حدث خطأ من جانبنا. حاول مرة أخرى بعد قليل." },
      "502": { title: "الخدمة لم تستجب", text: "الخدمة غير متاحة مؤقتًا. حاول بعد قليل." },
      "503": { title: "الخدمة مشغولة", text: "الخدمة غير متاحة مؤقتًا. حاول بعد قليل." },
      "504": { title: "انتهت مهلة الاستجابة", text: "تأخر الخادم في الرد. حاول مرة أخرى." },
    },
    c4: { title: "خطأ في الطلب", text: "تعذر إكمال الطلب." },
    c5: { title: "خطأ في الخادم", text: "حدث خطأ من جانبنا. حاول مرة أخرى بعد قليل." },
    back: "رجوع", retry: "إعادة المحاولة", more: "رمز الخطأ", home: "الرئيسية", res: "النتيجة",
  },
};

function pickLang(param: string | null, accept: string | null): Lang {
  const ok = (v: string | null | undefined): Lang | null => (v === "uz" || v === "ru" || v === "ar" || v === "en" ? v : null);
  const p = ok(param);
  if (p) return p;
  // Standart til — ingliz tili (Accept-Language e'tiborga olinmaydi); ?lang=uz|ru|ar bilan almashtirish mumkin.
  void accept;
  return "en";
}

/** Brauzer sahifa so'rayaptimi (ilova/API emas)? */
export function wantsHtml(req: Request): boolean {
  if (req.method !== "GET" && req.method !== "HEAD") return false;
  return (req.headers.get("accept") ?? "").includes("text/html");
}

/** Yuzlar: ×_× (muvaffaqiyatsiz/topilmadi), •_• (ruxsat yo'q), >_< (juda ko'p urinish). */
function moodFor(status: number): { face: string; cls: string } {
  if (status === 429) return { face: ">_<", cls: "work" };
  if (status === 401 || status === 403) return { face: "\u2022_\u2022", cls: "idle" };
  if (status >= 500) return { face: "\u00d7_\u00d7", cls: "dead err" };
  return { face: "\u00d7_\u00d7", cls: "dead" };
}

export function errorPage(env: Env, req: Request, status: number): Response {
  const url = new URL(req.url);
  const lang = pickLang(url.searchParams.get("lang"), req.headers.get("accept-language"));
  const pack = P[lang];
  const m = pack.msgs[String(status)] ?? (status >= 500 ? pack.c5 : pack.c4);
  const rtl = lang === "ar";
  const retryable = status === 429 || status >= 500;
  const app = esc(env.APP_NAME || "App");
  const mood = moodFor(status);
  // Faqat yo'l ko'rsatiladi (query'siz) — maxfiy parametrlar sahifaga chiqmasligi uchun.
  const path = esc(url.pathname.length > 64 ? url.pathname.slice(0, 63) + "\u2026" : url.pathname);
  const method = esc(req.method);

  const page = `<!doctype html><html lang="${lang}" dir="${rtl ? "rtl" : "ltr"}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>${status} \u00b7 ${esc(m.title)} \u00b7 ${app}</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&family=Newsreader:opsz,wght@6..72,500;6..72,600&family=Space+Grotesk:wght@400;500;700${rtl ? "&family=Noto+Sans+Arabic:wght@400;500;700" : ""}&display=swap">
<style>:root{color-scheme:dark light;--bg:#121118;--card:#1c1b25;--alt:#17161e;--line:#32303f;--lineS:#494660;--fg:#f3f2f8;--fg2:#b1afc2;--mut:#7c798e;--acc:#8b7cf6;--accD:#6e5ee8;--accT:#dcd6ff;--accBg:#2b2657;--dng:#f16a69;--code:#0e0d14;--codeT:#e0e0e6;--glow:rgba(139,124,246,.28)}
@media(prefers-color-scheme:light){:root{--bg:#faf9fd;--card:#fff;--alt:#f3f1fa;--line:#e4e1ef;--lineS:#d0cce3;--fg:#1e1b2e;--fg2:#5c586f;--mut:#928ea4;--acc:#5b3fd9;--accD:#4b31c9;--accT:#4b31c9;--accBg:#e9e4fb;--dng:#d8433f;--code:#1d1d22;--codeT:#e6e6ec;--glow:rgba(91,63,217,.16)}}
*{box-sizing:border-box}html,body{height:100%}
body{margin:0;background:radial-gradient(900px 480px at 50% -10%,var(--glow),transparent 70%),var(--bg);color:var(--fg2);font:16px/1.6 ${rtl ? "'Noto Sans Arabic'," : ""}'Space Grotesk',system-ui,sans-serif;display:grid;place-items:center;padding:24px}
main{width:100%;max-width:440px;text-align:center}
.brand{display:inline-flex;align-items:center;gap:10px;color:var(--fg);font-weight:600;margin-bottom:26px;text-decoration:none}
.logo{height:32px;padding:0 9px;border-radius:10px;background:linear-gradient(135deg,var(--acc),var(--accD));color:#fff;display:grid;place-items:center;font:700 13px 'JetBrains Mono',monospace;white-space:nowrap;direction:ltr}
.card{background:var(--card);border:1px solid var(--line);border-radius:22px;padding:30px 22px 24px;box-shadow:0 18px 50px -24px var(--glow)}
.face{display:inline-grid;place-items:center;min-width:104px;height:64px;padding:0 18px;border-radius:18px;background:var(--accBg);border:1px solid var(--line);font:700 34px/1 'JetBrains Mono',monospace;letter-spacing:1px;color:var(--acc);direction:ltr;unicode-bidi:isolate}
.face.dead{animation:br 1.2s ease-in-out infinite alternate}
.face.err{color:var(--dng)}
.face.work{color:var(--dng);animation:br .26s linear infinite alternate}
.face.idle{color:var(--acc)}
.code{margin:18px 0 0;font:700 72px/1 'JetBrains Mono',monospace;letter-spacing:-3px;background:linear-gradient(135deg,var(--acc),var(--accD) 60%,var(--accT));-webkit-background-clip:text;background-clip:text;color:transparent;direction:ltr}
h1{margin:10px 0 8px;font:600 28px/1.2 ${rtl ? "'Noto Sans Arabic'," : ""}'Newsreader',Georgia,serif;color:var(--fg)}
p{margin:0 auto 20px;max-width:34ch;color:var(--fg2)}
.term{margin:0 0 22px;padding:12px 14px;border-radius:12px;background:var(--code);border:1px solid var(--lineS);text-align:left;direction:ltr;font:400 12.5px/1.7 'JetBrains Mono',monospace;color:var(--codeT);overflow:hidden}
.term b{color:var(--acc);font-weight:500}.term i{font-style:normal;color:var(--mut)}.term .e{color:var(--dng)}
.term span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.row{display:flex;gap:10px;justify-content:center;flex-wrap:wrap}
.btn{font:inherit;font-weight:600;padding:11px 20px;border-radius:12px;border:1px solid var(--line);background:transparent;color:var(--fg);cursor:pointer;text-decoration:none}
.btn.p{border-color:transparent;background:var(--acc);color:#fff}.btn:hover{filter:brightness(1.08)}
.mut{margin-top:18px;font:400 12px 'JetBrains Mono',monospace;color:var(--mut)}
@keyframes br{from{transform:scale(.88);opacity:.55}to{transform:scale(1);opacity:1}}
@media(prefers-reduced-motion:reduce){.face{animation:none!important}}</style></head>
<body><main>
<a class="brand" href="/"><div class="logo">&gt;_&lt;</div>${app}</a>
<div class="card">
<div class="face ${mood.cls}">${mood.face.replace(/>/g, "&gt;").replace(/</g, "&lt;")}</div>
<div class="code">${status}</div>
<h1>${esc(m.title)}</h1>
<p>${esc(m.text)}</p>
<div class="term"><span><b>$</b> ${method} ${path}</span><span><i>${esc(pack.res)} (${esc(pack.more.toLowerCase())} ${status}):</i></span><span class="e">${status} ${esc(m.title)}</span></div>
<div class="row">${retryable ? `<button class="btn p" id="r" type="button">${esc(pack.retry)}</button>` : `<a class="btn p" href="/">${esc(pack.home)}</a>`}<button class="btn" id="b" type="button">${esc(pack.back)}</button></div>
<div class="mut">${esc(pack.more)}: ${status}</div>
</div></main>
<script>var b=document.getElementById("b");b.onclick=function(){if(history.length>1)history.back();else location.href="/"};var r=document.getElementById("r");if(r)r.onclick=function(){location.reload()}</script>
</body></html>`;
  return html(page, status);
}
