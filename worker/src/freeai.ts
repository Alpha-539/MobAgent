import type { Env } from "./types";
import { json } from "./util";
import { logAudit } from "./audit";
import { FREE_DEFAULT_LIMIT, FREE_MODELS, type FreeModel, type PlanId } from "./plans";

/**
 * Bepul (uchinchi tomon) AI API'larni admin paneldan boshqarish.
 * Sozlama `meta` jadvalida bitta qator (k = 'free_ai') bo'lib turadi; hech narsa sozlanmagan bo'lsa plans.ts dagi
 * FREE_MODELS va standart limitlar ishlaydi. Deploy qilmasdan o'zgartiriladi (bir isolate ichida ~15 soniya kesh).
 *
 * Limit — "5 soatda nechta TOKEN" (kirish + chiqish; xabar soni emas). Tarifga qarab har xil:
 *   free (tarifsiz) < basic < standard < pro.
 */

const KEY = "free_ai";
const CACHE_MS = 15_000;
const DEFAULT_BASE = "https://prexzyapis.com";

export type Tier = "free" | PlanId;
export const TIERS: Tier[] = ["free", "basic", "standard", "pro"];
/** Tarifsiz foydalanuvchi uchun standart FREE_AI_LIMIT (yoki 15); pullik tariflar — undan ko'p. */
export const DEFAULT_TIER_LIMITS: Record<Tier, number> = { free: FREE_DEFAULT_LIMIT, basic: 60_000, standard: 120_000, pro: 300_000 };
const MAX_LIMIT = 100_000_000;

export interface CustomModel {
  id: string;
  name: string;
  /** /ai/aiwriter-chat dagi model kodi (masalan "gpt-4o") */
  upstream: string;
  tier: "medium" | "strong";
}

export interface FreeAiCfg {
  /** Hamma bepul modellarni birdan yoqish/o'chirish (FREE_AI_DISABLED=1 baribir ustun). */
  enabled: boolean;
  /** Servis manzili; bo'sh = FREE_AI_BASE yoki standart. */
  base: string;
  /** Saqlangan limitlar birligi. Eski (xabar sonidagi) sozlamalarda bu maydon yo'q — ular e'tiborsiz qoldiriladi. */
  unit: "tokens";
  /** Tarif bo'yicha umumiy limit (5 soatda TOKEN soni). */
  limits: Record<Tier, number>;
  /** O'chirilgan model id'lari. */
  off: string[];
  /** Ilovada ko'rinadigan nomni almashtirish: id -> nom. */
  names: Record<string, string>;
  /** Alohida modelga alohida limit (ixtiyoriy): id -> { tarif: limit }. */
  modelLimits: Record<string, Partial<Record<Tier, number>>>;
  /** Admin qo'shgan qo'shimcha aiwriter modellari. */
  custom: CustomModel[];
}

export interface ResolvedFreeModel extends FreeModel {
  builtin: boolean;
  on: boolean;
}

export interface FreeAiState {
  enabled: boolean;
  base: string;
  cfg: FreeAiCfg;
  /** Hamma modellar (o'chirilganlari ham), nomi almashtirilgan holda. */
  all: ResolvedFreeModel[];
  /** Faqat faol modellar (ilovaga ko'rinadigan). */
  active: ResolvedFreeModel[];
}

const envLimit = (env: Env): number => {
  const n = Math.floor(Number(env.FREE_AI_LIMIT));
  return n > 0 && n <= MAX_LIMIT ? n : FREE_DEFAULT_LIMIT;
};

const defaults = (env: Env): FreeAiCfg => ({
  enabled: true,
  base: "",
  unit: "tokens",
  limits: { ...DEFAULT_TIER_LIMITS, free: envLimit(env) },
  off: [],
  names: {},
  modelLimits: {},
  custom: [],
});

const isLimit = (v: unknown): v is number => typeof v === "number" && Number.isInteger(v) && v >= 1 && v <= MAX_LIMIT;
const ID_RE = /^[a-z0-9][a-z0-9-]{1,60}$/;
const CODE_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/;

/** Saqlangan JSON'ni xavfsiz o'qiydi: noto'g'ri qiymatlar standartga qaytadi. */
function parse(env: Env, raw: string | null): FreeAiCfg {
  const d = defaults(env);
  if (!raw) return d;
  let o: Record<string, unknown>;
  try { o = JSON.parse(raw) as Record<string, unknown>; } catch { return d; }
  if (typeof o !== "object" || o === null) return d;
  const out = d;
  if (o.enabled === false) out.enabled = false;
  if (typeof o.base === "string") out.base = o.base;
  // Eski sozlamalar (unit yo'q) xabar sonida edi (15/30/60...) — token deb o'qilsa limit bir zumda tugardi: standartga qaytamiz.
  const tokenUnit = o.unit === "tokens";
  if (tokenUnit && typeof o.limits === "object" && o.limits !== null) {
    for (const t of TIERS) {
      const v = (o.limits as Record<string, unknown>)[t];
      if (isLimit(v)) out.limits[t] = v;
    }
  }
  if (Array.isArray(o.off)) out.off = o.off.filter((x): x is string => typeof x === "string");
  if (typeof o.names === "object" && o.names !== null) {
    for (const [k, v] of Object.entries(o.names)) if (typeof v === "string" && v.trim()) out.names[k] = v.trim().slice(0, 60);
  }
  if (tokenUnit && typeof o.modelLimits === "object" && o.modelLimits !== null) {
    for (const [k, v] of Object.entries(o.modelLimits)) {
      if (typeof v !== "object" || v === null) continue;
      const e: Partial<Record<Tier, number>> = {};
      for (const t of TIERS) { const x = (v as Record<string, unknown>)[t]; if (isLimit(x)) e[t] = x; }
      if (Object.keys(e).length) out.modelLimits[k] = e;
    }
  }
  if (Array.isArray(o.custom)) {
    for (const c of o.custom) {
      const r = c as Partial<CustomModel> | null;
      if (r && typeof r.id === "string" && ID_RE.test(r.id) && typeof r.name === "string" && typeof r.upstream === "string" && CODE_RE.test(r.upstream)) {
        out.custom.push({ id: r.id, name: r.name.slice(0, 60), upstream: r.upstream, tier: r.tier === "strong" ? "strong" : "medium" });
      }
    }
  }
  return out;
}

function resolve(env: Env, cfg: FreeAiCfg): FreeAiState {
  const builtinIds = new Set(FREE_MODELS.map((m) => m.id));
  const mk = (m: FreeModel, builtin: boolean): ResolvedFreeModel => ({
    ...m, name: cfg.names[m.id] || m.name, builtin, on: !cfg.off.includes(m.id),
  });
  const all: ResolvedFreeModel[] = [
    ...FREE_MODELS.map((m) => mk(m, true)),
    ...cfg.custom.filter((c) => !builtinIds.has(c.id)).map((c) =>
      mk({ id: c.id, name: c.name, tier: c.tier, path: "/ai/aiwriter-chat", kind: "aiwriter", upstream: c.upstream, showFree: false }, false)),
  ];
  const base = (cfg.base || env.FREE_AI_BASE || DEFAULT_BASE).replace(/\/+$/, "");
  return { enabled: env.FREE_AI_DISABLED !== "1" && cfg.enabled, base, cfg, all, active: all.filter((m) => m.on) };
}

let cache: { at: number; v: FreeAiState } | null = null;

export async function loadFreeAi(env: Env, fresh = false): Promise<FreeAiState> {
  if (!fresh && cache && Date.now() - cache.at < CACHE_MS) return cache.v;
  let raw: string | null = null;
  try {
    raw = (await env.DB.prepare("SELECT v FROM meta WHERE k = ?").bind(KEY).first<{ v: string }>())?.v ?? null;
  } catch { /* DB vaqtincha ishlamasa — standart sozlama bilan davom etamiz */ }
  const v = resolve(env, parse(env, raw));
  cache = { at: Date.now(), v };
  return v;
}

/** Foydalanuvchining tarifi uchun 5 soatlik limit. Hisoblagich barcha modellar uchun BITTA, shuning uchun limit ham bitta (tarif bo'yicha); modelga xos limitlar e'tiborsiz qoldiriladi. */
export function limitFor(st: FreeAiState, tier: Tier, _modelId?: string): number {
  return st.cfg.limits[tier];
}

// ───────────────────────── Servisga so'rov ─────────────────────────

export interface UpstreamResult {
  text: string;
  /** HTTP status; 0 = tarmoq xatosi yoki vaqt tugadi */
  status: number;
  /** Servis javobida bildirilgan model nomi (tekshirish uchun) */
  reportedModel: string | null;
}

/** Bitta model uchun servisga so'rov yuboradi va javob matnini oladi. Xatoda text = "". */
export async function callUpstream(st: FreeAiState, fm: FreeModel, prompt: string, timeoutMs = 60_000): Promise<UpstreamResult> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    // Gemini servisi sessiya (session_id) talab qiladi: har so'rovga YANGI tasodifiy id (servis sessiyalari umumiy —
    // bir xil id bilan boshqa odamning tarixi chiqib qoladi). aiwriter-chat sessiyasiz; model `model` maydonida.
    const payload = fm.kind === "gemini"
      ? { prompt, session_id: crypto.randomUUID() }
      : { prompt, ...(fm.upstream ? { model: fm.upstream } : {}) };
    const r = await fetch(`${st.base}${fm.path}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
      signal: ctl.signal,
    });
    if (!r.ok) return { text: "", status: r.status, reportedModel: null };
    const j = (await r.json().catch(() => null)) as {
      status?: unknown; response?: unknown; model?: unknown; result?: { status?: unknown; text?: unknown; model?: unknown } | null;
    } | null;
    if (!j || j.status === false) return { text: "", status: r.status, reportedModel: null };
    if (fm.kind === "gemini") {
      return { text: typeof j.response === "string" ? j.response.trim() : "", status: r.status, reportedModel: null };
    }
    if (!j.result || j.result.status === false) return { text: "", status: r.status, reportedModel: null };
    const t = j.result.text;
    const text = Array.isArray(t) ? t.filter((x): x is string => typeof x === "string").join("\n").trim() : typeof t === "string" ? t.trim() : "";
    const rm = typeof j.result.model === "string" ? j.result.model : typeof j.model === "string" ? j.model : null;
    return { text, status: r.status, reportedModel: rm };
  } catch {
    return { text: "", status: 0, reportedModel: null };
  } finally {
    clearTimeout(timer);
  }
}

// ───────────────────────── Admin ─────────────────────────

function cfgView(st: FreeAiState) {
  return {
    enabled: st.cfg.enabled,
    envDisabled: st.enabled === false && st.cfg.enabled === true, // FREE_AI_DISABLED=1 bilan o'chirilgan
    base: st.cfg.base,
    effectiveBase: st.base,
    limits: st.cfg.limits,
    defaults: DEFAULT_TIER_LIMITS,
    models: st.all.map((m) => ({
      id: m.id, name: m.name, defaultName: FREE_MODELS.find((b) => b.id === m.id)?.name ?? m.name, kind: m.kind,
      upstream: m.upstream ?? null, tier: m.tier, builtin: m.builtin, enabled: m.on, showFree: m.showFree,
      limits: st.cfg.modelLimits[m.id] ?? {},
    })),
  };
}

function validate(env: Env, raw: unknown): { ok: true; v: FreeAiCfg } | { ok: false; error: string } {
  if (typeof raw !== "object" || raw === null) return { ok: false, error: "JSON obyekt kerak" };
  const r = raw as Record<string, unknown>;
  const v = defaults(env);
  v.enabled = r.enabled !== false;

  const base = typeof r.base === "string" ? r.base.trim().replace(/\/+$/, "") : "";
  if (base) {
    let u: URL;
    try { u = new URL(base); } catch { return { ok: false, error: "Servis manzili noto'g'ri (https://... bo'lsin)" }; }
    if (u.protocol !== "https:" || u.search || u.hash || (u.pathname !== "/" && u.pathname !== "")) {
      return { ok: false, error: "Servis manzili faqat https://domen ko'rinishida bo'lsin (yo'l va parametrsiz)" };
    }
    v.base = u.origin;
  }

  const lim = (typeof r.limits === "object" && r.limits !== null ? r.limits : {}) as Record<string, unknown>;
  for (const t of TIERS) {
    const x = lim[t] === undefined || lim[t] === "" ? v.limits[t] : Number(lim[t]);
    if (!isLimit(x)) return { ok: false, error: `Limit (${t}, token) 1 dan ${MAX_LIMIT} gacha butun son bo'lsin` };
    v.limits[t] = x;
  }

  const builtinIds = new Set(FREE_MODELS.map((m) => m.id));
  const ids = new Set<string>(builtinIds);
  const custom = Array.isArray(r.custom) ? r.custom : [];
  if (custom.length > 40) return { ok: false, error: "Qo'shimcha modellar 40 tadan oshmasin" };
  for (const c of custom) {
    const x = c as Partial<CustomModel> | null;
    if (!x || typeof x.id !== "string" || !ID_RE.test(x.id)) return { ok: false, error: "Model id: kichik harf, raqam va '-' (2-61 belgi)" };
    if (ids.has(x.id)) return { ok: false, error: `Model id takrorlangan: ${x.id}` };
    const name = typeof x.name === "string" ? x.name.trim() : "";
    if (!name || name.length > 60) return { ok: false, error: `Model nomi 1-60 belgi bo'lsin (${x.id})` };
    if (typeof x.upstream !== "string" || !CODE_RE.test(x.upstream)) return { ok: false, error: `Servisdagi model kodi noto'g'ri (${x.id})` };
    ids.add(x.id);
    v.custom.push({ id: x.id, name, upstream: x.upstream, tier: x.tier === "strong" ? "strong" : "medium" });
  }

  const off = Array.isArray(r.off) ? r.off : [];
  v.off = off.filter((x): x is string => typeof x === "string" && ids.has(x));

  if (typeof r.names === "object" && r.names !== null) {
    for (const [k, n] of Object.entries(r.names)) {
      if (!ids.has(k) || typeof n !== "string") continue;
      const t = n.trim();
      if (t.length > 60) return { ok: false, error: `Nom 60 belgidan oshmasin (${k})` };
      if (t) v.names[k] = t;
    }
  }

  if (typeof r.modelLimits === "object" && r.modelLimits !== null) {
    for (const [k, m] of Object.entries(r.modelLimits)) {
      if (!ids.has(k) || typeof m !== "object" || m === null) continue;
      const e: Partial<Record<Tier, number>> = {};
      for (const t of TIERS) {
        const x = (m as Record<string, unknown>)[t];
        if (x === undefined || x === null || x === "") continue;
        const n = Number(x);
        if (!isLimit(n)) return { ok: false, error: `Limit (${k}, ${t}, token) 1 dan ${MAX_LIMIT} gacha butun son bo'lsin` };
        e[t] = n;
      }
      if (Object.keys(e).length) v.modelLimits[k] = e;
    }
  }
  return { ok: true, v };
}

/** /admin/api/free-ai[/test] — handleAdmin chaqiradi. null qaytsa, bu yo'l unga tegishli emas. */
export async function handleFreeAi(env: Env, req: Request, path: string, actor: string): Promise<Response | null> {
  if (path === "/admin/api/free-ai") {
    if (req.method === "GET") return json(cfgView(await loadFreeAi(env, true)));
    if (req.method === "PUT") {
      const x = validate(env, await req.json().catch(() => null));
      if (!x.ok) return json({ error: x.error }, 400);
      const before = await loadFreeAi(env, true);
      await env.DB.prepare("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")
        .bind(KEY, JSON.stringify(x.v)).run();
      cache = null;
      const after = await loadFreeAi(env, true);
      const ch: string[] = [];
      if (before.cfg.enabled !== after.cfg.enabled) ch.push(after.cfg.enabled ? "yoqildi" : "hammasi o'chirildi");
      if (before.cfg.base !== after.cfg.base) ch.push("servis manzili");
      if (JSON.stringify(before.cfg.limits) !== JSON.stringify(after.cfg.limits)) ch.push("limitlar");
      if (JSON.stringify(before.cfg.modelLimits) !== JSON.stringify(after.cfg.modelLimits)) ch.push("model limitlari");
      if (JSON.stringify(before.cfg.off) !== JSON.stringify(after.cfg.off)) ch.push("model yoqish/o'chirish");
      if (JSON.stringify(before.cfg.names) !== JSON.stringify(after.cfg.names)) ch.push("nomlar");
      if (JSON.stringify(before.cfg.custom) !== JSON.stringify(after.cfg.custom)) ch.push("qo'shimcha modellar");
      await logAudit(env, {
        actor, action: "update", entity: "free_ai", entityId: KEY,
        summary: ch.length ? `Bepul AI API: ${ch.join(", ")}` : "Bepul AI API: saqlandi (o'zgarishsiz)",
      });
      return json({ ok: true, ...cfgView(after) });
    }
    return null;
  }

  // POST /admin/api/free-ai/test { id } — modelga "Hi" yuborib, servis ishlayotganini tekshiradi (limitga tegmaydi).
  if (path === "/admin/api/free-ai/test" && req.method === "POST") {
    const b = (await req.json().catch(() => null)) as { id?: unknown } | null;
    const st = await loadFreeAi(env, true);
    const fm = st.all.find((m) => m.id === b?.id);
    if (!fm) return json({ error: "Model topilmadi" }, 404);
    const t0 = Date.now();
    const r = await callUpstream(st, fm, "Hi", 30_000);
    return json({
      ok: r.text.length > 0, status: r.status, ms: Date.now() - t0,
      reportedModel: r.reportedModel, expected: fm.upstream ?? null,
      sample: r.text.slice(0, 200),
    });
  }
  return null;
}
