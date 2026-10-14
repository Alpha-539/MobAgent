import type { Env } from "./types";
import { json } from "./util";
import { validateModel, type ModelInput } from "./validate";

/**
 * O'zgarishlar tarixi (audit log). Admin paneldagi har bir o'zgarish shu yerga yoziladi:
 * kim (Access emaili yoki "admin-token"), qachon, nima. Modellar uchun oldingi/keyingi holat saqlanadi,
 * shuning uchun o'zgarishni bekor qilish mumkin. Obuna amallari (tarif berish va h.k.) faqat yoziladi, bekor qilinmaydi.
 */

export const MODEL_KEYS = [
  "name", "downloadUrl", "direction", "category", "baseSizeMb", "baseRamMb", "minPhoneClass", "family",
  "paramsBillion", "maxContext", "role", "sha256", "sizeBytes", "enabled", "sortOrder", "systemPrompt", "agentPrompt", "note", "badge",
] as const;

export type Snap = Record<string, unknown> & { id: string };

export function snapOfRow(r: Record<string, unknown>): Snap {
  return {
    id: r.id as string, name: r.name, downloadUrl: r.download_url, direction: r.direction, category: r.category,
    baseSizeMb: r.base_size_mb, baseRamMb: r.base_ram_mb, minPhoneClass: r.min_phone_class, family: r.family,
    paramsBillion: r.params_billion, maxContext: r.max_context, role: r.role ?? null, sha256: r.sha256 ?? null,
    sizeBytes: r.size_bytes ?? null, enabled: r.enabled === 1, sortOrder: r.sort_order,
    systemPrompt: r.system_prompt ?? null, agentPrompt: r.agent_prompt ?? null, note: r.note ?? null, badge: r.badge ?? null,
  };
}

export function snapOfInput(x: ModelInput): Snap {
  const s: Snap = { id: x.id };
  for (const k of MODEL_KEYS) s[k] = (x as unknown as Record<string, unknown>)[k] ?? null;
  return s;
}

const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/** Qaysi maydonlar farq qiladi. Tartib (sortOrder) odatda e'tiborga olinmaydi: ↑↓ tugmalari hamma modelning tartibini siljitadi. */
export function changedKeys(a: Snap | null, b: Snap | null, withSort = false): string[] {
  if (!a || !b) return [];
  return MODEL_KEYS.filter((k) => (withSort || k !== "sortOrder") && !same(a[k], b[k]));
}

/** Admin kimligi. Bu funksiya faqat isAdmin() tekshiruvidan keyin chaqiriladi, shuning uchun JWT imzosi allaqachon tasdiqlangan. */
export function adminActor(req: Request): string {
  const jwt = req.headers.get("Cf-Access-Jwt-Assertion");
  if (!jwt) return "admin-token";
  try {
    const p = JSON.parse(atob(jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    if (typeof p.email === "string" && p.email) return p.email.slice(0, 120);
  } catch { /* quyida */ }
  return "access";
}

interface Entry {
  actor: string;
  action: string;
  entity: "model" | "user" | "announcement" | "app_update" | "cloud_model";
  entityId: string;
  summary: string;
  before?: unknown;
  after?: unknown;
  revertable?: boolean;
}

const INSERT_SQL =
  "INSERT INTO audit_log (ts,actor,action,entity,entity_id,summary,before_json,after_json,revertable,reverted_at) VALUES (?,?,?,?,?,?,?,?,?,0)";

function bindEntry(env: Env, e: Entry, ts: number) {
  return env.DB.prepare(INSERT_SQL).bind(
    ts, e.actor, e.action, e.entity, e.entityId.slice(0, 200), e.summary.slice(0, 300),
    e.before == null ? null : JSON.stringify(e.before), e.after == null ? null : JSON.stringify(e.after), e.revertable ? 1 : 0,
  );
}

/** Tarix yozuvi asosiy amalni hech qachon buzmasligi kerak. */
export async function logAudit(env: Env, e: Entry): Promise<void> {
  try {
    await bindEntry(env, e, Date.now()).run();
  } catch (err) {
    console.error("audit", err instanceof Error ? err.name : "error");
  }
}

function modelEntry(actor: string, before: Snap | null, after: Snap | null, prefix = ""): Entry | null {
  if (!before && !after) return null;
  const id = (after ?? before)!.id;
  if (!before) return { actor, action: "create", entity: "model", entityId: id, summary: `${prefix}Yangi model: ${after!.name}`, after, revertable: true };
  if (!after) return { actor, action: "delete", entity: "model", entityId: id, summary: `${prefix}O'chirildi: ${before.name}`, before, revertable: true };
  const ch = changedKeys(before, after);
  if (ch.length === 0) return null; // hech narsa o'zgarmagan yoki faqat tartib
  return { actor, action: "update", entity: "model", entityId: id, summary: `${prefix}O'zgargan: ${ch.join(", ")}`, before, after, revertable: true };
}

export async function logModelChange(env: Env, actor: string, before: Snap | null, after: Snap | null): Promise<void> {
  const e = modelEntry(actor, before, after);
  if (e) await logAudit(env, e);
}

export async function logModelChanges(env: Env, actor: string, pairs: [Snap | null, Snap | null][], prefix: string): Promise<void> {
  try {
    const now = Date.now();
    const st = pairs.map(([b, a]) => modelEntry(actor, b, a, prefix)).filter((e): e is Entry => !!e).map((e) => bindEntry(env, e, now));
    if (st.length) await env.DB.batch(st);
  } catch (err) {
    console.error("audit", err instanceof Error ? err.name : "error");
  }
}

/** Ilova bilan bir xil upsert (import va bekor qilish ishlatadi). star_count'ga tegmaydi. */
export const UPSERT_SQL = `INSERT INTO models (id,name,download_url,direction,category,base_size_mb,base_ram_mb,min_phone_class,family,
    params_billion,max_context,role,sha256,size_bytes,enabled,sort_order,star_count,created_at,updated_at,system_prompt,agent_prompt,note,badge)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)
   ON CONFLICT(id) DO UPDATE SET name=excluded.name, download_url=excluded.download_url, direction=excluded.direction,
    category=excluded.category, base_size_mb=excluded.base_size_mb, base_ram_mb=excluded.base_ram_mb,
    min_phone_class=excluded.min_phone_class, family=excluded.family, params_billion=excluded.params_billion,
    max_context=excluded.max_context, role=excluded.role, sha256=excluded.sha256, size_bytes=excluded.size_bytes,
    enabled=excluded.enabled, sort_order=excluded.sort_order, updated_at=excluded.updated_at,
    system_prompt=excluded.system_prompt, agent_prompt=excluded.agent_prompt, note=excluded.note, badge=excluded.badge`;

export function bindUpsert(env: Env, x: ModelInput, now: number) {
  return env.DB.prepare(UPSERT_SQL).bind(
    x.id, x.name, x.downloadUrl, x.direction, x.category, x.baseSizeMb, x.baseRamMb, x.minPhoneClass, x.family,
    x.paramsBillion, x.maxContext, x.role, x.sha256, x.sizeBytes, x.enabled ? 1 : 0, x.sortOrder, now, now, x.systemPrompt, x.agentPrompt, x.note, x.badge,
  );
}

const parse = (s: unknown): Snap | null => {
  if (typeof s !== "string") return null;
  try { return JSON.parse(s) as Snap; } catch { return null; }
};

const PAGE = 40;

/** GET /admin/api/history?before=<id>&id=<model id> */
export async function adminHistory(env: Env, url: URL): Promise<Response> {
  const beforeId = Number(url.searchParams.get("before")) || 0;
  const eid = (url.searchParams.get("id") ?? "").trim().slice(0, 200);
  const where: string[] = [];
  const args: unknown[] = [];
  if (beforeId > 0) { where.push("id < ?"); args.push(beforeId); }
  if (eid) { where.push("entity_id = ?"); args.push(eid); }
  const { results } = await env.DB.prepare(
    `SELECT * FROM audit_log ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY id DESC LIMIT ${PAGE + 1}`,
  ).bind(...args).all<Record<string, unknown>>();
  const rows = results.slice(0, PAGE);
  return json({
    hasMore: results.length > PAGE,
    entries: rows.map((r) => {
      const before = parse(r.before_json), after = parse(r.after_json);
      return {
        id: r.id, ts: r.ts, actor: r.actor, action: r.action, entity: r.entity, entityId: r.entity_id, summary: r.summary,
        before, after, changed: changedKeys(before, after),
        revertable: r.revertable === 1 && !r.reverted_at, revertedAt: r.reverted_at || 0,
      };
    }),
  });
}

/** POST /admin/api/history/:id/revert  { force?: boolean } — modelni shu yozuvdan OLDINGI holatiga qaytaradi. */
export async function adminRevert(env: Env, actor: string, entryId: number, raw: unknown): Promise<Response> {
  const force = typeof raw === "object" && raw !== null && (raw as { force?: unknown }).force === true;
  const e = await env.DB.prepare("SELECT * FROM audit_log WHERE id = ?").bind(entryId).first<Record<string, unknown>>();
  if (!e) return json({ error: "yozuv topilmadi" }, 404);
  if (e.entity !== "model" || e.revertable !== 1) return json({ error: "bu yozuvni bekor qilib bo'lmaydi" }, 400);
  if (e.reverted_at) return json({ error: "bu yozuv allaqachon bekor qilingan" }, 400);

  const before = parse(e.before_json), after = parse(e.after_json);
  const id = String(e.entity_id);
  const curRow = await env.DB.prepare("SELECT * FROM models WHERE id = ?").bind(id).first<Record<string, unknown>>();
  const cur = curRow ? snapOfRow(curRow) : null;

  // Shu yozuvdan keyin model yana o'zgargan bo'lsa, bekor qilish keyingi o'zgarishlarni ham ezadi — avval so'raymiz.
  const drifted = after ? !cur || changedKeys(cur, after).length > 0 : !!cur;
  if (drifted && !force)
    return json({ error: "Model shu yozuvdan keyin yana o'zgargan (yoki holati boshqacha). Baribir qaytarilsinmi?", conflict: true }, 409);

  const now = Date.now();
  if (!before) {
    // Yaratilgan model — o'chiriladi.
    if (!cur) return json({ error: "model allaqachon yo'q" }, 400);
    await env.DB.batch([
      env.DB.prepare("DELETE FROM stars WHERE model_id = ?").bind(id),
      env.DB.prepare("DELETE FROM stats_daily WHERE model_id = ?").bind(id),
      env.DB.prepare("DELETE FROM models WHERE id = ?").bind(id),
    ]);
  } else {
    const v = validateModel(before, id);
    if (!v.ok) return json({ error: `saqlangan holat yaroqsiz: ${v.error}` }, 400);
    const x = v.value;
    if (cur) x.sortOrder = cur.sortOrder as number; // joriy joyida qoladi
    await bindUpsert(env, x, now).run();
  }

  const nowRow = await env.DB.prepare("SELECT * FROM models WHERE id = ?").bind(id).first<Record<string, unknown>>();
  await env.DB.prepare("UPDATE audit_log SET reverted_at = ? WHERE id = ?").bind(now, entryId).run();
  await logAudit(env, {
    actor, action: "revert", entity: "model", entityId: id, summary: `Bekor qilindi: #${entryId} (${e.summary})`,
    before: cur, after: nowRow ? snapOfRow(nowRow) : null, revertable: true,
  });
  return json({ ok: true, note: before && !cur ? "Model qayta tiklandi; yulduzchalar va statistika qaytmaydi." : undefined });
}

export async function auditCleanup(env: Env): Promise<void> {
  await env.DB.prepare("DELETE FROM audit_log WHERE ts < ?").bind(Date.now() - 180 * 86400_000).run();
}
