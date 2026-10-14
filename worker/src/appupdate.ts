import type { Env } from "./types";
import { json } from "./util";
import { logAudit } from "./audit";

/**
 * Ilovani yangilash eslatmasi (minimal). Adminda "eng yangi versionCode" yoziladi; ilova o'z versionCode'ini
 * solishtiradi va eskirgan bo'lsa bosh sahifada yopsa bo'ladigan banner ko'rsatadi (Google Play havolasi bilan).
 * Majburiy yangilash YO'Q — faqat eslatma. Sozlama `meta` jadvalida bitta qator (k = 'app_update') bo'lib turadi.
 */

const KEY = "app_update";
const MAX_CODE = 2_100_000_000; // Google Play versionCode chegarasi

interface Cfg {
  enabled: boolean;
  latestCode: number;
  note: string;
}

const EMPTY: Cfg = { enabled: false, latestCode: 0, note: "" };

async function read(env: Env): Promise<Cfg> {
  const r = await env.DB.prepare("SELECT v FROM meta WHERE k = ?").bind(KEY).first<{ v: string }>();
  if (!r) return EMPTY;
  try {
    const o = JSON.parse(r.v) as Partial<Cfg>;
    return {
      enabled: o.enabled === true,
      latestCode: Number.isInteger(o.latestCode) ? (o.latestCode as number) : 0,
      note: typeof o.note === "string" ? o.note : "",
    };
  } catch {
    return EMPTY;
  }
}

/** GET /app-update — ilova uchun (ochiq, qisqa kesh). O'chiq yoki sozlanmagan bo'lsa { update: null }. */
export async function publicAppUpdate(env: Env): Promise<Response> {
  const c = await read(env);
  return json(
    { update: c.enabled && c.latestCode > 0 ? { latestCode: c.latestCode, note: c.note || null } : null },
    200,
    { "cache-control": "public, max-age=60" },
  );
}

function validate(raw: unknown): { ok: true; v: Cfg } | { ok: false; error: string } {
  if (typeof raw !== "object" || raw === null) return { ok: false, error: "JSON obyekt kerak" };
  const r = raw as Record<string, unknown>;
  const enabled = r.enabled === true;
  const code = typeof r.latestCode === "number" ? r.latestCode : Number(r.latestCode);
  if (!Number.isInteger(code) || code < 0 || code > MAX_CODE) return { ok: false, error: "versionCode butun son bo'lsin (1 dan 2 100 000 000 gacha)" };
  if (enabled && code < 1) return { ok: false, error: "yoqish uchun versionCode kiriting" };
  const note = typeof r.note === "string" ? r.note.trim() : "";
  if (note.length > 200) return { ok: false, error: "izoh 200 belgidan oshmasin" };
  return { ok: true, v: { enabled, latestCode: code, note } };
}

/** /admin/api/app-update — handleAdmin chaqiradi. null qaytsa, bu yo'l unga tegishli emas. */
export async function handleAppUpdate(env: Env, req: Request, path: string, actor: string): Promise<Response | null> {
  if (path !== "/admin/api/app-update") return null;
  if (req.method === "GET") return json(await read(env));
  if (req.method === "PUT") {
    const x = validate(await req.json().catch(() => null));
    if (!x.ok) return json({ error: x.error }, 400);
    await env.DB.prepare("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")
      .bind(KEY, JSON.stringify(x.v)).run();
    await logAudit(env, {
      actor, action: "update", entity: "app_update", entityId: KEY,
      summary: (x.v.enabled ? `Yangilash eslatmasi yoqildi: versionCode ${x.v.latestCode}` : "Yangilash eslatmasi o'chirildi").slice(0, 300),
    });
    return json({ ok: true });
  }
  return null;
}
