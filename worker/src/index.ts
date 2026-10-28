import { isAdmin, verifyFirebase } from "./auth";
import { handleAdmin } from "./admin";
import { auditCleanup } from "./audit";
import { deleteAccountPage, privacyPage } from "./pages";
import { landingPage } from "./landing";
import { publicAnnouncement } from "./announce";
import { publicAppUpdate } from "./appupdate";
import { submitFeedback, wipeFeedback } from "./feedback";
import { handleAuthMail } from "./authmail";
import { errorPage, wantsHtml } from "./errorpage";
import { ensureDb } from "./db-init";
import { cloudScheduled, handleCloud, wipeCloudUser } from "./cloud";
export { UserBudget } from "./budget";
import { CORS_HEADERS, html, json } from "./util";
import { METRICS, type Env } from "./types";

const today = () => new Date().toISOString().slice(0, 10);

async function sha256Hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function publicModel(r: Record<string, unknown>) {
  return {
    id: r.id,
    name: r.name,
    downloadUrl: r.download_url,
    direction: r.direction,
    category: r.category,
    baseSizeMb: r.base_size_mb,
    baseRamMb: r.base_ram_mb,
    minPhoneClass: r.min_phone_class,
    family: r.family,
    paramsBillion: r.params_billion,
    maxContext: r.max_context,
    role: r.role,
    sha256: r.sha256,
    sizeBytes: r.size_bytes,
    stars: r.star_count,
    systemPrompt: r.system_prompt ?? null,
    agentPrompt: r.agent_prompt ?? null,
    badge: r.badge ?? null,
  };
}

async function recountStars(env: Env, modelId: string): Promise<number> {
  await env.DB.prepare(
    "UPDATE models SET star_count = (SELECT COUNT(*) FROM stars WHERE model_id = ?1) WHERE id = ?1",
  ).bind(modelId).run();
  const row = await env.DB.prepare("SELECT star_count FROM models WHERE id = ?").bind(modelId).first<{ star_count: number }>();
  return row?.star_count ?? 0;
}

async function setStar(env: Env, uid: string, modelId: string, on: boolean): Promise<Response> {
  const exists = await env.DB.prepare("SELECT 1 AS x FROM models WHERE id = ? AND enabled = 1").bind(modelId).first();
  if (!exists) return json({ error: "model topilmadi" }, 404);
  if (on) {
    await env.DB.prepare("INSERT OR IGNORE INTO stars (uid, model_id, created_at) VALUES (?, ?, ?)")
      .bind(uid, modelId, Date.now()).run();
  } else {
    await env.DB.prepare("DELETE FROM stars WHERE uid = ? AND model_id = ?").bind(uid, modelId).run();
  }
  const stars = await recountStars(env, modelId);
  return json({ starred: on, stars });
}

/** Admin panelning standart manzili: https://<worker>/alpha/maxfiy_sahifa */
const DEFAULT_ADMIN_PATH = "/alpha/maxfiy_sahifa";

/**
 * Admin manzili. Standart — /alpha/maxfiy_sahifa. Xohlasangiz ADMIN_PATH (secret) bilan boshqasiga almashtirish mumkin
 * (1-4 bo'lak, har biri harf/raqam/_/-, masalan "alpha/boshqa_yol"). Panel faqat shu manzilda ochiladi, /admin va /admin/api/*
 * esa mavjud emasdek 404 beradi. ADMIN_PATH qo'yilgan, lekin noto'g'ri bo'lsa — admin butunlay yopiladi (xavfsiz tomonga).
 */
function adminPrefix(env: Env): { mode: "secret"; prefix: string } | { mode: "closed" } {
  const raw = env.ADMIN_PATH?.trim().replace(/^\/+|\/+$/g, "");
  if (!raw) return { mode: "secret", prefix: DEFAULT_ADMIN_PATH };
  return /^[A-Za-z0-9_-]{1,40}(\/[A-Za-z0-9_-]{1,40}){0,3}$/.test(raw) ? { mode: "secret", prefix: "/" + raw } : { mode: "closed" };
}

async function handle(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  // CORS Preflight
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  const url = new URL(req.url);
  let path = url.pathname.replace(/\/+$/, "") || "/";
  const m = req.method;

  // ---- Admin manzili himoyasi: ruxsatsiz so'rovga "mavjud emas" (404) — panel borligi ham bilinmasin ----
  const ap = adminPrefix(env);
  let viaSecret = false;
  if (ap.mode === "secret" && (path === ap.prefix || path.startsWith(ap.prefix + "/"))) {
    path = "/admin" + path.slice(ap.prefix.length); // ichki yo'l: /<ADMIN_PATH>/api/... -> /admin/api/...
    viaSecret = true;
  }
  if (path === "/admin" || path.startsWith("/admin/")) {
    if (ap.mode === "closed" || (ap.mode === "secret" && !viaSecret)) {
      return json({ error: "topilmadi" }, 404);
    }
  }

  // ---- Ochiq sahifalar (Play Console shularni so'raydi) ----
  if (m === "GET" && path === "/privacy") return privacyPage(env, url.searchParams.get("lang"));
  if (m === "GET" && path === "/delete-account") return deleteAccountPage(env, url.searchParams.get("lang"));
  if (m === "GET" && path === "/") return landingPage(env, url.searchParams.get("lang"));
  if (m === "GET" && path === "/health") return json({ ok: true, service: "mobagent-api" });

  // Qolgan barcha yo'llar bazadan foydalanadi: jadvallar va boshlang'ich modellar avtomatik yaratiladi.
  await ensureDb(env);

  // ---- Katalog (ochiq, kesh qilinadi) ----
  if (m === "GET" && path === "/catalog") {
    const { results } = await env.DB.prepare(
      "SELECT * FROM models WHERE enabled = 1 ORDER BY sort_order ASC, created_at ASC",
    ).all();
    return json({ models: results.map(publicModel) }, 200, { "cache-control": "public, max-age=300" });
  }

  // ---- Yangilik / e'lon (bosh sahifadagi banner) ----
  if (m === "GET" && path === "/announcement") return publicAnnouncement(env, url);

  // ---- Ilovani yangilash eslatmasi (bosh sahifadagi banner) ----
  if (m === "GET" && path === "/app-update") return publicAppUpdate(env);

  // ---- Tasdiqlash / parol tiklash xatlari ----
  if (path.startsWith("/auth/")) {
    const r = await handleAuthMail(env, req, path);
    if (r) return r;
  }

  // ---- Foydalanuvchi fikri (ilovadan) ----
  if (m === "POST" && path === "/feedback") return submitFeedback(env, req);

  // ---- Yulduzchalar ----
  const star = path.match(/^\/models\/([a-z0-9._-]{2,64})\/star$/);
  if (star && (m === "PUT" || m === "DELETE")) {
    const user = await verifyFirebase(req, env);
    if (!user) return json({ error: "kirish kerak" }, 401);
    if (!user.emailVerified) return json({ error: "email tasdiqlanmagan" }, 403);
    return setStar(env, user.uid, star[1], m === "PUT");
  }
  if (m === "GET" && path === "/me/stars") {
    const user = await verifyFirebase(req, env);
    if (!user) return json({ error: "kirish kerak" }, 401);
    const { results } = await env.DB.prepare("SELECT model_id FROM stars WHERE uid = ?").bind(user.uid).all<{ model_id: string }>();
    return json({ ids: results.map((r) => r.model_id) });
  }

  // ---- Hisob ma'lumotlarini o'chirish (ilova hisobni o'chirishdan oldin chaqiradi) ----
  if (m === "DELETE" && path === "/me") {
    const user = await verifyFirebase(req, env);
    if (!user) return json({ error: "kirish kerak" }, 401);
    const { results } = await env.DB.prepare("SELECT model_id FROM stars WHERE uid = ?").bind(user.uid).all<{ model_id: string }>();
    await env.DB.prepare("DELETE FROM stars WHERE uid = ?").bind(user.uid).run();
    for (const r of results) await recountStars(env, r.model_id);
    await wipeFeedback(env, user.uid);
    await wipeCloudUser(env, user.uid);
    return json({ deleted: true });
  }

  // ---- Veb orqali hisobni o'chirish so'rovi (Google Play talabi). Egalikni admin email orqali tasdiqlaydi. ----
  if (m === "POST" && path === "/delete-request") {
    let b: { email?: unknown; note?: unknown } = {};
    try { b = await req.json(); } catch { return json({ error: "JSON kerak" }, 400); }
    const email = typeof b.email === "string" ? b.email.trim().toLowerCase() : "";
    const note = typeof b.note === "string" ? b.note.trim().slice(0, 500) : "";
    if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json({ error: "Email noto'g'ri" }, 400);
    const hourAgo = Date.now() - 3600_000;
    const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM delete_requests WHERE created_at > ?").bind(hourAgo).first<{ n: number }>();
    if ((recent?.n ?? 0) >= 30) return json({ error: "Juda ko'p so'rov. Keyinroq urinib ko'ring." }, 429);
    const dup = await env.DB.prepare("SELECT 1 AS x FROM delete_requests WHERE email = ? AND done_at = 0").bind(email).first();
    if (!dup) await env.DB.prepare("INSERT INTO delete_requests (email, note, created_at) VALUES (?, ?, ?)").bind(email, note, Date.now()).run();
    return json({ ok: true }); // takroriy so'rovga ham bir xil javob
  }

  // ---- Statistika hodisalari (faqat yig'ma son) ----
  if (m === "POST" && path === "/events") {
    let body: { metric?: unknown; modelId?: unknown } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "JSON kerak" }, 400);
    }
    const metric = typeof body.metric === "string" ? body.metric : "";
    if (!(METRICS as readonly string[]).includes(metric)) return json({ error: "noma'lum metrika" }, 400);
    const modelId = typeof body.modelId === "string" && /^[a-z0-9._-]{2,64}$/.test(body.modelId) ? body.modelId : "";
    const day = today();

    await env.DB.prepare(
      `INSERT INTO stats_daily (day, metric, model_id, count)
       SELECT ?1, ?2, ?3, 1 WHERE (?3 = '' OR EXISTS (SELECT 1 FROM models WHERE id = ?3))
       ON CONFLICT(day, metric, model_id) DO UPDATE SET count = count + 1`,
    ).bind(day, metric, modelId).run();

    const user = await verifyFirebase(req, env);
    if (user && env.STATS_SALT) {
      const h = await sha256Hex(`${env.STATS_SALT}|${day}|${user.uid}`);
      await env.DB.prepare("INSERT OR IGNORE INTO dau (day, h) VALUES (?, ?)").bind(day, h).run();
    }
    return json({ ok: true });
  }

  // ---- Pullik rejim: obuna, byudjet, AI proksi ----
  const cloud = await handleCloud(req, env, ctx, path, url);
  if (cloud) return cloud;

  // ---- Admin ----
  if (path === "/admin" || path.startsWith("/admin/")) {
    const noIndex = (r: Response) => { const h = new Headers(r.headers); h.set("x-robots-tag", "noindex, nofollow"); h.set("cache-control", "no-store"); return new Response(r.body, { status: r.status, headers: h }); };
    if (path === "/admin" && m === "GET") return noIndex(await handleAdmin(req, env, path, null));
    if (!(await isAdmin(req, env))) return json({ error: "ruxsat yo'q" }, 403);
    return noIndex(await handleAdmin(req, env, path, true));
  }

  return json({ error: "topilmadi" }, 404);
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    let res: Response;
    try {
      res = await handle(req, env, ctx);
    } catch (e) {
      // Foydalanuvchi ma'lumoti logga tushmasligi uchun faqat xato turi yoziladi.
      console.error("unhandled", e instanceof Error ? e.name : "error");
      res = json({ error: "server xatosi" }, 500);
    }
    // Brauzerga (Accept: text/html) JSON xato o'rniga chiroyli sahifa; ilova va API JSON'ni olaveradi.
    if (res.status >= 400 && wantsHtml(req) && (res.headers.get("content-type") ?? "").includes("application/json")) {
      return errorPage(env, req, res.status);
    }
    return res;
  },

  async scheduled(_ev: ScheduledController, env: Env): Promise<void> {
    await ensureDb(env);
    const cutoff = new Date(Date.now() - 35 * 86400_000).toISOString().slice(0, 10);
    await env.DB.prepare("DELETE FROM dau WHERE day < ?").bind(cutoff).run();
    const old = new Date(Date.now() - 400 * 86400_000).toISOString().slice(0, 10);
    await env.DB.prepare("DELETE FROM stats_daily WHERE day < ?").bind(old).run();
    await auditCleanup(env);
    await cloudScheduled(env);
  },
};
