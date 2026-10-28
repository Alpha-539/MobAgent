import { SignJWT, importPKCS8 } from "jose";
import type { Env } from "./types";
import { sanitizePrivateKey } from "./util";

/**
 * Push-bildirishnoma (Firebase Cloud Messaging HTTP v1).
 * E'lon topic'ga yuboriladi: `announce_all` (hamma foydalanuvchi) yoki `announce_<til>` (uz|ru|en|ar).
 * Ilova ishga tushganda shu topic'larga o'zi obuna bo'ladi (Push.kt).
 * Kerak: FIREBASE_SERVICE_ACCOUNT secret (e'lon emailidagi kabi) — Firebase Admin SDK service account'i FCM'ga ruxsatli.
 */

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
export const CHANNEL_ID = "announcements";

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

export function pushConfigured(env: Env): boolean {
  return !!env.FIREBASE_SERVICE_ACCOUNT && !!env.FIREBASE_PROJECT_ID;
}

export interface PushInput {
  /** "all" | "uz" | "ru" | "en" | "ar" */
  lang: string;
  title: string;
  body: string;
  linkUrl: string;
  announcementId: number;
}

/** Xato bo'lsa Error tashlaydi (xabari adminga ko'rsatiladi). */
export async function sendAnnouncementPush(env: Env, p: PushInput): Promise<void> {
  if (!pushConfigured(env)) throw new Error("FIREBASE_SERVICE_ACCOUNT sozlanmagan — bildirishnoma yuborib bo'lmaydi");
  const t = await accessToken(env);
  const topic = `announce_${p.lang}`;
  const data: Record<string, string> = { type: "announcement", id: String(p.announcementId) };
  if (p.linkUrl) data.link = p.linkUrl;
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${t}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: {
        topic,
        notification: { title: p.title, body: p.body || p.title },
        data,
        android: {
          priority: "HIGH",
          notification: { channel_id: CHANNEL_ID },
        },
      },
    }),
  });
  if (!r.ok) {
    const msg = ((await r.json().catch(() => null)) as { error?: { message?: string } } | null)?.error?.message ?? "";
    throw new Error(`FCM xatosi ${r.status}${msg ? ": " + msg.slice(0, 160) : ""}`);
  }
}
