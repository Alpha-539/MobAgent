import type { Env } from "./types";
import { json } from "./util";
import { logAudit } from "./audit";
import { sendAnnouncementPush } from "./push";

/**
 * Yangilik / e'lon. Adminda yoziladi, ilovaning bosh sahifasida banner bo'lib chiqadi.
 * Bir vaqtda faqat ENG YANGI faol e'lon ko'rsatiladi (til mos kelsa). Ilova foydalanuvchi yopgan e'lon id'sini eslab qoladi.
 */

export const ANNOUNCE_LEVELS = ["info", "warn", "promo"] as const;
export const ANNOUNCE_LANGS = ["all", "uz", "ru", "en", "ar"] as const;

interface Row {
  id: number;
  title: string;
  body: string;
  level: string;
  lang: string;
  link_url: string;
  enabled: number;
  ends_at: number;
  created_at: number;
  updated_at: number;
}

function view(r: Row) {
  return {
    id: r.id, title: r.title, body: r.body, level: r.level, lang: r.lang, linkUrl: r.link_url,
    enabled: r.enabled === 1, endsAt: r.ends_at, createdAt: r.created_at, updatedAt: r.updated_at,
  };
}

/** GET /announcement?lang=uz — ilova uchun (ochiq, qisqa kesh). Hech narsa bo'lmasa { announcement: null }. */
export async function publicAnnouncement(env: Env, url: URL): Promise<Response> {
  const lang = url.searchParams.get("lang") ?? "";
  const l = (ANNOUNCE_LANGS as readonly string[]).includes(lang) ? lang : "all";
  const r = await env.DB.prepare(
    `SELECT * FROM announcements
     WHERE enabled = 1 AND (ends_at = 0 OR ends_at > ?1) AND (lang = 'all' OR lang = ?2)
     ORDER BY id DESC LIMIT 1`,
  ).bind(Date.now(), l).first<Row>();
  return json(
    { announcement: r ? { id: r.id, title: r.title, body: r.body, level: r.level, linkUrl: r.link_url || null } : null },
    200,
    { "cache-control": "public, max-age=60" },
  );
}

interface Input {
  title: string;
  body: string;
  level: string;
  lang: string;
  linkUrl: string;
  enabled: boolean;
  endsAt: number;
}

function validate(raw: unknown): { ok: true; v: Input } | { ok: false; error: string } {
  if (typeof raw !== "object" || raw === null) return { ok: false, error: "JSON obyekt kerak" };
  const r = raw as Record<string, unknown>;
  const title = typeof r.title === "string" ? r.title.trim() : "";
  const body = typeof r.body === "string" ? r.body.trim() : "";
  if (!title || title.length > 80) return { ok: false, error: "sarlavha 1–80 belgi bo'lsin" };
  if (body.length > 400) return { ok: false, error: "matn 400 belgidan oshmasin" };
  const level = typeof r.level === "string" ? r.level : "info";
  if (!(ANNOUNCE_LEVELS as readonly string[]).includes(level)) return { ok: false, error: "level noto'g'ri" };
  const lang = typeof r.lang === "string" ? r.lang : "all";
  if (!(ANNOUNCE_LANGS as readonly string[]).includes(lang)) return { ok: false, error: "til noto'g'ri" };
  let linkUrl = typeof r.linkUrl === "string" ? r.linkUrl.trim() : "";
  if (linkUrl) {
    try {
      const u = new URL(linkUrl);
      if (u.protocol !== "https:") throw new Error();
      linkUrl = u.toString();
    } catch {
      return { ok: false, error: "havola https:// bilan boshlansin" };
    }
    if (linkUrl.length > 500) return { ok: false, error: "havola juda uzun" };
  }
  const endsAt = typeof r.endsAt === "number" && Number.isFinite(r.endsAt) && r.endsAt >= 0 ? Math.floor(r.endsAt) : 0;
  const enabled = r.enabled !== false;
  return { ok: true, v: { title, body, level, lang, linkUrl, enabled, endsAt } };
}

/** Bildirishnoma yuboradi; xato bo'lsa saqlashni buzmaydi — natija matni qaytadi (admin ko'radi). */
async function pushNow(
  env: Env, actor: string, id: number,
  a: { title: string; body: string; lang: string; linkUrl: string },
): Promise<{ pushed: boolean; pushError?: string }> {
  try {
    await sendAnnouncementPush(env, { lang: a.lang, title: a.title, body: a.body, linkUrl: a.linkUrl, announcementId: id });
    await logAudit(env, { actor, action: "push", entity: "announcement", entityId: String(id), summary: `Bildirishnoma yuborildi (${a.lang}): ${a.title}`.slice(0, 300) });
    return { pushed: true };
  } catch (e) {
    return { pushed: false, pushError: e instanceof Error ? e.message : "noma'lum xato" };
  }
}

/** /admin/api/announcements ... — handleAdmin chaqiradi. null qaytsa, bu yo'l e'longa tegishli emas. */
export async function handleAnnouncements(env: Env, req: Request, path: string, actor: string): Promise<Response | null> {
  const m = req.method;
  if (m === "GET" && path === "/admin/api/announcements") {
    const { results } = await env.DB.prepare("SELECT * FROM announcements ORDER BY id DESC LIMIT 100").all<Row>();
    return json({ announcements: results.map(view) });
  }
  if (m === "POST" && path === "/admin/api/announcements") {
    const raw = await req.json().catch(() => null);
    const x = validate(raw);
    if (!x.ok) return json({ error: x.error }, 400);
    const wantPush = (raw as Record<string, unknown>).push === true;
    const now = Date.now();
    const res = await env.DB.prepare(
      `INSERT INTO announcements (title,body,level,lang,link_url,enabled,ends_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)`,
    ).bind(x.v.title, x.v.body, x.v.level, x.v.lang, x.v.linkUrl, x.v.enabled ? 1 : 0, x.v.endsAt, now, now).run();
    const id = String(res.meta.last_row_id ?? "");
    await logAudit(env, { actor, action: "create", entity: "announcement", entityId: id, summary: `Yangi e'lon: ${x.v.title}`.slice(0, 300) });
    const push = wantPush && x.v.enabled ? await pushNow(env, actor, Number(id), x.v) : { pushed: false };
    return json({ ok: true, id: Number(id), ...push }, 201);
  }
  const one = path.match(/^\/admin\/api\/announcements\/(\d{1,12})$/);
  if (one && m === "PUT") {
    const raw = await req.json().catch(() => null);
    const x = validate(raw);
    if (!x.ok) return json({ error: x.error }, 400);
    const wantPush = (raw as Record<string, unknown>).push === true;
    const r = await env.DB.prepare(
      `UPDATE announcements SET title=?, body=?, level=?, lang=?, link_url=?, enabled=?, ends_at=?, updated_at=? WHERE id=?`,
    ).bind(x.v.title, x.v.body, x.v.level, x.v.lang, x.v.linkUrl, x.v.enabled ? 1 : 0, x.v.endsAt, Date.now(), Number(one[1])).run();
    if (!r.meta.changes) return json({ error: "topilmadi" }, 404);
    await logAudit(env, { actor, action: "update", entity: "announcement", entityId: one[1], summary: `E'lon o'zgardi: ${x.v.title}${x.v.enabled ? "" : " (o'chirib qo'yildi)"}`.slice(0, 300) });
    const push = wantPush && x.v.enabled ? await pushNow(env, actor, Number(one[1]), x.v) : { pushed: false };
    return json({ ok: true, ...push });
  }
  // Mavjud e'lonni qayta bildirishnoma qilib yuborish
  const pu = path.match(/^\/admin\/api\/announcements\/(\d{1,12})\/push$/);
  if (pu && m === "POST") {
    const r = await env.DB.prepare("SELECT * FROM announcements WHERE id = ?").bind(Number(pu[1])).first<Row>();
    if (!r) return json({ error: "topilmadi" }, 404);
    if (r.enabled !== 1 || (r.ends_at > 0 && r.ends_at <= Date.now())) return json({ error: "e'lon faol emas — avval yoqing" }, 400);
    const res = await pushNow(env, actor, r.id, { title: r.title, body: r.body, lang: r.lang, linkUrl: r.link_url });
    if (!res.pushed) return json({ error: res.pushError ?? "yuborilmadi" }, 502);
    return json({ ok: true, ...res });
  }
  if (one && m === "DELETE") {
    const prev = await env.DB.prepare("SELECT title FROM announcements WHERE id = ?").bind(Number(one[1])).first<{ title: string }>();
    if (!prev) return json({ error: "topilmadi" }, 404);
    await env.DB.prepare("DELETE FROM announcements WHERE id = ?").bind(Number(one[1])).run();
    await logAudit(env, { actor, action: "delete", entity: "announcement", entityId: one[1], summary: `E'lon o'chirildi: ${prev.title}`.slice(0, 300) });
    return json({ ok: true });
  }
  return null;
}
