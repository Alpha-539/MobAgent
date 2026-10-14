import { logAudit } from "./audit";
import { CLOUD_MODELS } from "./plans";
import type { Env } from "./types";
import { json } from "./util";

/**
 * Pullik (provayder API'li) modellarni admin paneldan yoqish/o'chirish.
 * Faqat "o'chirilganlar" ro'yxati `meta` jadvalida saqlanadi (k = 'cloud_models_off', JSON massiv).
 * Provayder KALITLARI bu yerda saqlanmaydi — ular Cloudflare secret (wrangler secret put ...).
 * plans.ts dagi `enabled: false` ustun: panel uni yoqa olmaydi. Deploysiz o'zgaradi (~15 soniya kesh).
 */

const KEY = "cloud_models_off";
const CACHE_MS = 15_000;
let cache: { at: number; v: Set<string> } | null = null;

/** Admin o'chirgan pullik modellar id'lari. DB o'qilmasa — bo'sh (hech narsa o'chirilmaydi). */
export async function loadOffModels(env: Env, fresh = false): Promise<Set<string>> {
  if (!fresh && cache && Date.now() - cache.at < CACHE_MS) return cache.v;
  let v = new Set<string>();
  try {
    const raw = (await env.DB.prepare("SELECT v FROM meta WHERE k = ?").bind(KEY).first<{ v: string }>())?.v;
    const arr: unknown = raw ? JSON.parse(raw) : [];
    if (Array.isArray(arr)) v = new Set(arr.filter((x): x is string => typeof x === "string"));
  } catch { /* DB vaqtincha ishlamasa — hech narsa o'chirilmagan deb hisoblanadi */ }
  cache = { at: Date.now(), v };
  return v;
}

/** GET /admin/api/cloud-models → { off: [...] };  PUT { id, enabled } — bitta modelni yoqish/o'chirish. */
export async function handleCloudModels(env: Env, req: Request, path: string, actor: string): Promise<Response | null> {
  if (path !== "/admin/api/cloud-models") return null;
  if (req.method === "GET") return json({ off: [...(await loadOffModels(env, true))] });
  if (req.method !== "PUT") return null;

  const b = (await req.json().catch(() => null)) as { id?: unknown; enabled?: unknown } | null;
  const cm = CLOUD_MODELS.find((m) => m.id === b?.id);
  if (!cm || typeof b?.enabled !== "boolean") return json({ error: "Model topilmadi yoki 'enabled' (true/false) yo'q" }, 400);

  const off = await loadOffModels(env, true);
  const was = off.has(cm.id);
  if (b.enabled) off.delete(cm.id); else off.add(cm.id);
  if (was === off.has(cm.id)) return json({ ok: true, off: [...off] });
  await env.DB.prepare("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")
    .bind(KEY, JSON.stringify([...off])).run();
  cache = null;
  await logAudit(env, {
    actor, action: "update", entity: "cloud_model", entityId: cm.id,
    summary: `Pullik model ${b.enabled ? "yoqildi" : "o'chirildi"}: ${cm.name}`,
  });
  return json({ ok: true, off: [...off] });
}
