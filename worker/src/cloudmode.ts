import { logAudit } from "./audit";
import type { Env } from "./types";
import { json } from "./util";

/**
 * Butun pullik rejimni (MobAgent Cloud) admin paneldan yoqish/o'chirish.
 * O'chirilganda: ilovada "Tez orada" ko'rsatiladi, server esa chat/xarid so'rovlarini rad etadi.
 * Holat `meta` jadvalida (k = 'cloud_mode_off', v = '1'). Deploysiz o'zgaradi (~15 soniya kesh).
 * Play RTDN (obuna bildirishnomalari) bundan ta'sirlanmaydi — mavjud obunalar hisobi to'xtamaydi.
 */

const KEY = "cloud_mode_off";
const CACHE_MS = 15_000;
let cache: { at: number; v: boolean } | null = null;

/** true bo'lsa pullik rejim yopiq ("Tez orada"). DB o'qilmasa — yopilmagan deb hisoblanadi. */
export async function isCloudModeOff(env: Env, fresh = false): Promise<boolean> {
  if (!fresh && cache && Date.now() - cache.at < CACHE_MS) return cache.v;
  let v = false;
  try {
    const raw = (await env.DB.prepare("SELECT v FROM meta WHERE k = ?").bind(KEY).first<{ v: string }>())?.v;
    v = raw === "1";
  } catch { /* DB vaqtincha ishlamasa — pullik rejim yopilmaydi */ }
  cache = { at: Date.now(), v };
  return v;
}

/** GET /cloud/status (ochiq) → { comingSoon: boolean } — ilova shu orqali "Tez orada" ko'rsatadi. */
export async function publicCloudStatus(env: Env): Promise<Response> {
  return json({ comingSoon: await isCloudModeOff(env) }, 200, { "cache-control": "public, max-age=30" });
}

/** GET /admin/api/cloud-mode → { off };  PUT { enabled } — pullik rejimni yoqish/o'chirish. */
export async function handleCloudMode(env: Env, req: Request, path: string, actor: string): Promise<Response | null> {
  if (path !== "/admin/api/cloud-mode") return null;
  if (req.method === "GET") return json({ off: await isCloudModeOff(env, true) });
  if (req.method !== "PUT") return null;

  const b = (await req.json().catch(() => null)) as { enabled?: unknown } | null;
  if (typeof b?.enabled !== "boolean") return json({ error: "'enabled' (true/false) kerak" }, 400);

  const was = await isCloudModeOff(env, true);
  const off = !b.enabled;
  if (was === off) return json({ ok: true, off });
  await env.DB.prepare("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")
    .bind(KEY, off ? "1" : "0").run();
  cache = null;
  await logAudit(env, {
    actor, action: "update", entity: "cloud_mode", entityId: "cloud",
    summary: off ? "Pullik rejim O'CHIRILDI (ilovada «Tez orada»)" : "Pullik rejim YOQILDI",
  });
  return json({ ok: true, off });
}
