import type { Env } from "./types";

export interface Mail { to: string; subject: string; html: string; text: string }

/**
 * Xat yuboradi. Avval Cloudflare Email Service (env.EMAIL binding), bo'lmasa Resend (RESEND_API_KEY).
 * Xato bo'lsa throw qiladi.
 */
export async function sendMail(env: Env, m: Mail): Promise<void> {
  const addr = env.MAIL_FROM;
  if (!addr) throw new Error("MAIL_FROM sozlanmagan");
  const from = { email: addr, name: env.APP_NAME || "App" };
  if (env.EMAIL) {
    await env.EMAIL.send({ to: m.to, from, subject: m.subject, html: m.html, text: m.text });
    return;
  }
  if (env.RESEND_API_KEY) {
    const r = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({ from: `${from.name} <${from.email}>`, to: [m.to], subject: m.subject, html: m.html, text: m.text }),
    });
    if (!r.ok) throw new Error(`Resend xatosi: ${r.status}`);
    return;
  }
  throw new Error("Xat yuboruvchi (EMAIL binding yoki RESEND_API_KEY) topilmadi");
}

export const mailEnabled = (env: Env): boolean =>
  !!env.MAIL_FROM && !!env.FIREBASE_SERVICE_ACCOUNT && (!!env.EMAIL || !!env.RESEND_API_KEY);
