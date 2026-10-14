import type { Env } from "./types";
import { json } from "./util";
import { verifyFirebase } from "./auth";

/**
 * Foydalanuvchi fikrlari. Ilova yuboradi (POST /feedback, Firebase token kerak), adminda ko'rinadi.
 * Faqat foydalanuvchi o'zi yozgan matn, turi, ixtiyoriy aloqa, ilova versiyasi va tili saqlanadi.
 * Chat matni hech qachon yuborilmaydi. Hisob o'chirilganda uning fikrlari ham o'chadi (wipeFeedback).
 */

export const FEEDBACK_KINDS = ["idea", "bug", "other"] as const;
const LANGS = ["uz", "ru", "en", "ar"];
const MAX_MSG = 1000;
const MIN_MSG = 5;
const PER_HOUR = 5;
const PER_DAY = 15;
const GLOBAL_PER_HOUR = 300;

interface Row {
  id: number;
  uid: string;
  kind: string;
  message: string;
  contact: string;
  app_version: string;
  lang: string;
  created_at: number;
  read_at: number;
}

const view = (r: Row) => ({
  id: r.id, uid: r.uid, kind: r.kind, message: r.message, contact: r.contact,
  appVersion: r.app_version, lang: r.lang, createdAt: r.created_at, readAt: r.read_at,
});

/** POST /feedback — ilovadan. { kind, message, contact?, appVersion?, lang? } */
export async function submitFeedback(env: Env, req: Request): Promise<Response> {
  const user = await verifyFirebase(req, env);
  if (!user) return json({ error: "kirish kerak" }, 401);
  if (!user.emailVerified) return json({ error: "email tasdiqlanmagan" }, 403);

  const b = (await req.json().catch(() => null)) as Record<string, unknown> | null;
  if (!b || typeof b !== "object") return json({ error: "JSON kerak" }, 400);
  const kind = typeof b.kind === "string" ? b.kind : "other";
  if (!(FEEDBACK_KINDS as readonly string[]).includes(kind)) return json({ error: "tur noto'g'ri" }, 400);
  const message = typeof b.message === "string" ? b.message.trim() : "";
  if (message.length < MIN_MSG) return json({ error: "fikr juda qisqa" }, 400);
  if (message.length > MAX_MSG) return json({ error: `fikr ${MAX_MSG} belgidan oshmasin` }, 400);
  const contact = typeof b.contact === "string" ? b.contact.trim().slice(0, 120) : "";
  const appVersion = typeof b.appVersion === "string" ? b.appVersion.trim().slice(0, 30) : "";
  const lang = typeof b.lang === "string" && LANGS.includes(b.lang) ? b.lang : "";

  const now = Date.now();
  const cnt = async (since: number, uid?: string) =>
    (uid
      ? await env.DB.prepare("SELECT COUNT(*) AS n FROM feedback WHERE uid = ? AND created_at > ?").bind(uid, since).first<{ n: number }>()
      : await env.DB.prepare("SELECT COUNT(*) AS n FROM feedback WHERE created_at > ?").bind(since).first<{ n: number }>()
    )?.n ?? 0;
  if ((await cnt(now - 3600_000, user.uid)) >= PER_HOUR || (await cnt(now - 86_400_000, user.uid)) >= PER_DAY)
    return json({ error: "juda ko'p fikr yuborildi, keyinroq urinib ko'ring" }, 429);
  if ((await cnt(now - 3600_000)) >= GLOBAL_PER_HOUR) return json({ error: "server band, keyinroq urinib ko'ring" }, 429);

  await env.DB.prepare(
    "INSERT INTO feedback (uid,kind,message,contact,app_version,lang,created_at,read_at) VALUES (?,?,?,?,?,?,?,0)",
  ).bind(user.uid, kind, message, contact, appVersion, lang, now).run();
  return json({ ok: true }, 201);
}

/** Hisob o'chirilganda chaqiriladi. */
export async function wipeFeedback(env: Env, uid: string): Promise<void> {
  await env.DB.prepare("DELETE FROM feedback WHERE uid = ?").bind(uid).run();
}

/** /admin/api/feedback ... — handleAdmin chaqiradi. null qaytsa, bu yo'l fikrga tegishli emas. */
export async function handleAdminFeedback(env: Env, req: Request, path: string): Promise<Response | null> {
  const m = req.method;
  if (m === "GET" && path === "/admin/api/feedback") {
    const url = new URL(req.url);
    const where: string[] = [];
    const args: (string | number)[] = [];
    if (url.searchParams.get("unread") === "1") where.push("read_at = 0");
    const kind = url.searchParams.get("kind") ?? "";
    if ((FEEDBACK_KINDS as readonly string[]).includes(kind)) { where.push("kind = ?"); args.push(kind); }
    const { results } = await env.DB.prepare(
      `SELECT * FROM feedback ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY id DESC LIMIT 200`,
    ).bind(...args).all<Row>();
    const unread = (await env.DB.prepare("SELECT COUNT(*) AS n FROM feedback WHERE read_at = 0").first<{ n: number }>())?.n ?? 0;
    const total = (await env.DB.prepare("SELECT COUNT(*) AS n FROM feedback").first<{ n: number }>())?.n ?? 0;
    return json({ feedback: results.map(view), unread, total });
  }
  const one = path.match(/^\/admin\/api\/feedback\/(\d{1,12})$/);
  if (one && m === "POST") {
    const b = (await req.json().catch(() => null)) as { read?: unknown } | null;
    const read = b?.read !== false;
    const r = await env.DB.prepare("UPDATE feedback SET read_at = ? WHERE id = ?").bind(read ? Date.now() : 0, Number(one[1])).run();
    if (!r.meta.changes) return json({ error: "topilmadi" }, 404);
    return json({ ok: true });
  }
  if (one && m === "DELETE") {
    await env.DB.prepare("DELETE FROM feedback WHERE id = ?").bind(Number(one[1])).run();
    return json({ ok: true });
  }
  if (m === "POST" && path === "/admin/api/feedback/read-all") {
    await env.DB.prepare("UPDATE feedback SET read_at = ? WHERE read_at = 0").bind(Date.now()).run();
    return json({ ok: true });
  }
  return null;
}
