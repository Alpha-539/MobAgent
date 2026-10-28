import { SignJWT, importPKCS8 } from "jose";
import type { Env } from "./types";
import { json, sanitizePrivateKey } from "./util";
import { verifyFirebase } from "./auth";
import { mailEnabled, sendMail } from "./mail";

/**
 * Email tasdiqlash va parol tiklash xatlarini O'ZIMIZ yuboramiz (Firebase'ning standart xati spamga tushgani uchun).
 * Havolani Firebase Admin (Identity Toolkit) yaratadi — tasdiqlash/parol almashtirishning o'zi Firebase'da qoladi.
 *   POST /auth/verify-email   (Firebase token)  { lang? }
 *   POST /auth/reset-password (ochiq)            { email, lang? }
 * Sozlanmagan bo'lsa 503 qaytadi — ilova avtomatik Firebase'ning o'z xatiga qaytadi.
 */

const LANGS = ["uz", "ru", "en", "ar"] as const;
type Lang = (typeof LANGS)[number];
const SCOPE = "https://www.googleapis.com/auth/identitytoolkit";

const HOUR = 3600_000;
const DAY = 86_400_000;
const GLOBAL_PER_HOUR = 400;

let cached: { token: string; exp: number } | null = null;

async function accessToken(env: Env): Promise<string> {
  if (cached && Date.now() < cached.exp - 60_000) return cached.token;
  const sa = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT ?? "") as { client_email: string; private_key: string };
  const key = await importPKCS8(sanitizePrivateKey(sa.private_key), "RS256");
  const assertion = await new SignJWT({ scope: SCOPE })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt()
    .setExpirationTime("55m")
    .sign(key);
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!r.ok) throw new Error(`Google OAuth xatosi: ${r.status}`);
  const j = (await r.json()) as { access_token: string; expires_in: number };
  cached = { token: j.access_token, exp: Date.now() + j.expires_in * 1000 };
  return cached.token;
}

/** Firebase action havolasini yaratadi (xat yubormaydi). Foydalanuvchi topilmasa null. */
async function actionLink(env: Env, type: "VERIFY_EMAIL" | "PASSWORD_RESET", email: string, lang: Lang): Promise<string | null> {
  const t = await accessToken(env);
  const r = await fetch(`https://identitytoolkit.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/accounts:sendOobCode`, {
    method: "POST",
    headers: { authorization: `Bearer ${t}`, "content-type": "application/json" },
    body: JSON.stringify({ requestType: type, email, returnOobLink: true }),
  });
  if (!r.ok) {
    const msg = ((await r.json().catch(() => null)) as { error?: { message?: string } } | null)?.error?.message ?? "";
    if (msg.startsWith("EMAIL_NOT_FOUND") || msg.startsWith("USER_NOT_FOUND")) return null;
    throw new Error(`Identity Toolkit xatosi: ${r.status} ${msg}`);
  }
  const link = ((await r.json()) as { oobLink?: string }).oobLink;
  if (!link) throw new Error("oobLink qaytmadi");
  return link + (link.includes("?") ? "&" : "?") + `lang=${lang}`;
}

// ---------- Shablonlar ----------
interface Tpl { subject: string; hello: string; body: string; button: string; ignore: string; fallback: string }
const T: Record<"verify" | "reset", Record<Lang, Tpl>> = {
  verify: {
    uz: { subject: "{app}: emailingizni tasdiqlang", hello: "Salom!", body: "{app} hisobingizni faollashtirish uchun email manzilingizni tasdiqlang.", button: "Emailni tasdiqlash", ignore: "Agar siz ro'yxatdan o'tmagan bo'lsangiz, bu xatni e'tiborsiz qoldiring.", fallback: "Tugma ishlamasa, havolani brauzerga ko'chiring:" },
    ru: { subject: "{app}: подтвердите email", hello: "Здравствуйте!", body: "Подтвердите адрес электронной почты, чтобы активировать аккаунт {app}.", button: "Подтвердить email", ignore: "Если вы не регистрировались, просто проигнорируйте это письмо.", fallback: "Если кнопка не работает, скопируйте ссылку в браузер:" },
    en: { subject: "{app}: verify your email", hello: "Hello!", body: "Confirm your email address to activate your {app} account.", button: "Verify email", ignore: "If you didn't sign up, you can ignore this email.", fallback: "If the button doesn't work, copy this link into your browser:" },
    ar: { subject: "{app}: تأكيد بريدك الإلكتروني", hello: "مرحبًا!", body: "أكّد عنوان بريدك الإلكتروني لتفعيل حسابك في {app}.", button: "تأكيد البريد", ignore: "إذا لم تنشئ حسابًا، تجاهل هذه الرسالة.", fallback: "إذا لم يعمل الزر، انسخ الرابط إلى المتصفح:" },
  },
  reset: {
    uz: { subject: "{app}: parolni tiklash", hello: "Salom!", body: "{app} hisobingiz parolini tiklash uchun quyidagi tugmani bosing.", button: "Parolni tiklash", ignore: "Agar siz so'ramagan bo'lsangiz, bu xatni e'tiborsiz qoldiring — parolingiz o'zgarmaydi.", fallback: "Tugma ishlamasa, havolani brauzerga ko'chiring:" },
    ru: { subject: "{app}: сброс пароля", hello: "Здравствуйте!", body: "Нажмите кнопку ниже, чтобы сбросить пароль аккаунта {app}.", button: "Сбросить пароль", ignore: "Если вы не запрашивали сброс, проигнорируйте это письмо — пароль не изменится.", fallback: "Если кнопка не работает, скопируйте ссылку в браузер:" },
    en: { subject: "{app}: reset your password", hello: "Hello!", body: "Tap the button below to reset the password for your {app} account.", button: "Reset password", ignore: "If you didn't request this, ignore this email — your password won't change.", fallback: "If the button doesn't work, copy this link into your browser:" },
    ar: { subject: "{app}: إعادة تعيين كلمة المرور", hello: "مرحبًا!", body: "اضغط على الزر أدناه لإعادة تعيين كلمة مرور حسابك في {app}.", button: "إعادة التعيين", ignore: "إذا لم تطلب ذلك، تجاهل هذه الرسالة — لن تتغير كلمة مرورك.", fallback: "إذا لم يعمل الزر، انسخ الرابط إلى المتصفح:" },
  },
};

// "Spamni tekshiring" eslatmasi (qizil rangda ko'rsatiladi)
const SPAM: Record<Lang, string> = {
  uz: "Xat kelmasa, «Spam» papkasini tekshiring.",
  ru: "Если письма нет, проверьте папку «Спам».",
  en: "If you don't see the email, check your Spam folder.",
  ar: "إذا لم تجد الرسالة، تحقق من مجلد الرسائل غير المرغوب فيها (Spam).",
};

const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

function render(kind: "verify" | "reset", lang: Lang, app: string, link: string) {
  const t = T[kind][lang];
  const f = (s: string) => s.replace(/\{app\}/g, app);
  const rtl = lang === "ar";
  const html = `<!doctype html><html lang="${lang}" dir="${rtl ? "rtl" : "ltr"}"><body style="margin:0;padding:24px;background:#f4f4f8;font-family:Arial,Helvetica,sans-serif;color:#1b1b22">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
<table role="presentation" width="100%" style="max-width:480px;background:#ffffff;border-radius:16px;padding:28px" cellpadding="0" cellspacing="0"><tr><td style="text-align:${rtl ? "right" : "left"}">
<div style="font-size:26px;font-weight:700;letter-spacing:1px;margin-bottom:18px">&gt;_&lt;</div>
<p style="font-size:16px;margin:0 0 8px">${esc(f(t.hello))}</p>
<p style="font-size:15px;line-height:1.5;margin:0 0 22px">${esc(f(t.body))}</p>
<p style="margin:0 0 22px"><a href="${esc(link)}" style="display:inline-block;background:#6e5ee8;color:#ffffff;text-decoration:none;font-weight:700;padding:12px 22px;border-radius:12px">${esc(t.button)}</a></p>
<p style="font-size:14px;font-weight:700;color:#d32f2f;line-height:1.5;margin:0 0 18px">${esc(SPAM[lang])}</p>
<p style="font-size:13px;color:#555;line-height:1.5;margin:0 0 14px">${esc(t.ignore)}</p>
<p style="font-size:12px;color:#888;line-height:1.5;margin:0;word-break:break-all">${esc(t.fallback)}<br>${esc(link)}</p>
</td></tr></table></td></tr></table></body></html>`;
  const text = `${f(t.hello)}\n\n${f(t.body)}\n\n${link}\n\n${SPAM[lang]}\n${t.ignore}\n`;
  return { subject: f(t.subject), html, text };
}

// ---------- Cheklov (spamga qarshi) ----------
async function hash(env: Env, s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode((env.STATS_SALT ?? "") + "|" + s));
  return [...new Uint8Array(d)].slice(0, 16).map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function count(env: Env, k: string | null, since: number): Promise<number> {
  const q = k
    ? env.DB.prepare("SELECT COUNT(*) AS n FROM mail_log WHERE k = ? AND created_at > ?").bind(k, since)
    : env.DB.prepare("SELECT COUNT(*) AS n FROM mail_log WHERE created_at > ?").bind(since);
  return (await q.first<{ n: number }>())?.n ?? 0;
}
async function record(env: Env, keys: string[], kind: string): Promise<void> {
  const now = Date.now();
  await env.DB.batch(keys.map((k) => env.DB.prepare("INSERT INTO mail_log (k, kind, created_at) VALUES (?,?,?)").bind(k, kind, now)));
  if (Math.random() < 0.05) await env.DB.prepare("DELETE FROM mail_log WHERE created_at < ?").bind(now - 2 * DAY).run();
}

const pickLang = (v: unknown): Lang => (typeof v === "string" && (LANGS as readonly string[]).includes(v) ? (v as Lang) : "en");
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** index.ts chaqiradi. null qaytsa — bu yo'l xatlarga tegishli emas. */
export async function handleAuthMail(env: Env, req: Request, path: string): Promise<Response | null> {
  // Tekshiruv: brauzerda /auth/mail-status ni oching. Faqat "bor/yo'q" qaytaradi, qiymatlarni hech qachon ko'rsatmaydi.
  if (req.method === "GET" && path === "/auth/mail-status") {
    let saValid = false;
    try {
      const sa = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT ?? "") as { client_email?: string; private_key?: string };
      saValid = !!sa.client_email && !!sanitizePrivateKey(sa.private_key ?? "").includes("BEGIN PRIVATE KEY");
    } catch { /* noto'g'ri JSON */ }
    return json({
      enabled: mailEnabled(env),
      mail_from: !!env.MAIL_FROM,
      sender: env.EMAIL ? "cloudflare" : env.RESEND_API_KEY ? "resend" : null,
      firebase_service_account: !!env.FIREBASE_SERVICE_ACCOUNT,
      firebase_service_account_valid_json: saValid,
      firebase_project_id: !!env.FIREBASE_PROJECT_ID,
    });
  }
  if (req.method !== "POST") return null;

  if (path === "/auth/verify-email") {
    const user = await verifyFirebase(req, env);
    if (!user) return json({ error: "kirish kerak" }, 401);
    if (!mailEnabled(env)) return json({ error: "mail_disabled" }, 503);
    if (user.emailVerified) return json({ ok: true, already: true });
    if (!user.email || !EMAIL_RE.test(user.email)) return json({ error: "email topilmadi" }, 400);
    const b = (await req.json().catch(() => null)) as { lang?: unknown } | null;
    const lang = pickLang(b?.lang);

    const ku = "u:" + user.uid;
    const now = Date.now();
    if ((await count(env, ku, now - HOUR)) >= 3 || (await count(env, ku, now - DAY)) >= 8)
      return json({ error: "juda ko'p so'rov" }, 429);
    if ((await count(env, null, now - HOUR)) >= GLOBAL_PER_HOUR) return json({ error: "server band" }, 429);

    try {
      const link = await actionLink(env, "VERIFY_EMAIL", user.email, lang);
      if (!link) return json({ error: "hisob topilmadi" }, 404);
      const m = render("verify", lang, env.APP_NAME || "App", link);
      await sendMail(env, { to: user.email, ...m });
    } catch (e) {
      console.error("verify-email xatosi:", e);
      return json({ error: "xat yuborilmadi" }, 502);
    }
    await record(env, [ku, "g"], "verify");
    return json({ ok: true });
  }

  if (path === "/auth/reset-password") {
    if (!mailEnabled(env)) return json({ error: "mail_disabled" }, 503);
    const b = (await req.json().catch(() => null)) as { email?: unknown; lang?: unknown } | null;
    const email = typeof b?.email === "string" ? b.email.trim().toLowerCase() : "";
    if (email.length > 254 || !EMAIL_RE.test(email)) return json({ error: "Email noto'g'ri" }, 400);
    const lang = pickLang(b?.lang);

    const ke = "e:" + (await hash(env, email));
    const ki = "i:" + (await hash(env, req.headers.get("cf-connecting-ip") ?? "?"));
    const now = Date.now();
    if ((await count(env, ke, now - HOUR)) >= 3 || (await count(env, ki, now - HOUR)) >= 10)
      return json({ error: "juda ko'p so'rov" }, 429);
    if ((await count(env, null, now - HOUR)) >= GLOBAL_PER_HOUR) return json({ error: "server band" }, 429);

    try {
      const link = await actionLink(env, "PASSWORD_RESET", email, lang);
      if (link) {
        const m = render("reset", lang, env.APP_NAME || "App", link);
        await sendMail(env, { to: email, ...m });
      }
    } catch (e) {
      console.error("reset-password xatosi:", e);
      return json({ error: "xat yuborilmadi" }, 502);
    }
    await record(env, [ke, ki, "g"], "reset");
    return json({ ok: true }); // hisob bor-yo'qligidan qat'i nazar bir xil javob
  }
  return null;
}
