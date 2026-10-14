import { ADMIN_HTML } from "./admin-html";
import { html, json } from "./util";
import { METRICS, type Env } from "./types";
import { validateModel } from "./validate";
import { adminCloudAction, adminCloudOverview, adminCloudUser } from "./cloud";
import { handleAnnouncements } from "./announce";
import { handleAppUpdate } from "./appupdate";
import { handleCloudModels } from "./cloudmodels";
import { handleAdminFeedback } from "./feedback";
import { adminActor, adminHistory, adminRevert, bindUpsert, logAudit, logModelChange, logModelChanges, snapOfInput, snapOfRow } from "./audit";

function modelRow(r: Record<string, unknown>) {
  return {
    id: r.id, name: r.name, downloadUrl: r.download_url, direction: r.direction, category: r.category,
    baseSizeMb: r.base_size_mb, baseRamMb: r.base_ram_mb, minPhoneClass: r.min_phone_class, family: r.family,
    paramsBillion: r.params_billion, maxContext: r.max_context, role: r.role, sha256: r.sha256,
    sizeBytes: r.size_bytes, enabled: r.enabled === 1, sortOrder: r.sort_order, stars: r.star_count,
    systemPrompt: r.system_prompt ?? null, agentPrompt: r.agent_prompt ?? null, note: r.note ?? null, badge: r.badge ?? null, updatedAt: r.updated_at,
  };
}

export interface LinkCheck {
  id: string;
  name: string;
  enabled: boolean;
  state: "ok" | "warn" | "broken";
  status: number; // 0 = tarmoq xatosi / vaqt tugadi
  note: string;
  sizeBytes: number | null;
}

/** Bitta havolani tekshiradi: HEAD (kerak bo'lsa GET Range 0-0), qo'lda ≤5 ta redirect, 8 soniya. */
async function checkLink(url: string, expectBytes: number | null, baseMb: number): Promise<Omit<LinkCheck, "id" | "name" | "enabled">> {
  let cur = url;
  let method: "HEAD" | "GET" = "HEAD";
  try {
    for (let hop = 0; hop < 6; hop++) {
      const u = new URL(cur);
      if (u.protocol !== "https:") return { state: "broken", status: 0, note: "https emas", sizeBytes: null };
      const res = await fetch(u.toString(), {
        method,
        redirect: "manual",
        headers: method === "GET" ? { Range: "bytes=0-0" } : {},
        signal: AbortSignal.timeout(8000),
      });
      if (res.status >= 300 && res.status < 400) {
        const loc = res.headers.get("location");
        if (!loc) return { state: "broken", status: res.status, note: "redirect manzilsiz", sizeBytes: null };
        cur = new URL(loc, u).toString();
        continue;
      }
      if (method === "HEAD" && (res.status === 405 || res.status === 403 || res.status === 501)) {
        method = "GET"; // ba'zi CDN HEAD'ni rad etadi
        continue;
      }
      if (res.status >= 400) {
        const note = res.status === 404 ? "fayl topilmadi (o'chgan yoki nomi o'zgargan)" : res.status === 401 || res.status === 403 ? "ruxsat yo'q (yopiq/gated model?)" : "server xatosi";
        return { state: "broken", status: res.status, note, sizeBytes: null };
      }
      const cr = res.headers.get("content-range"); // "bytes 0-0/12345"
      const total = Number(cr?.split("/")[1] ?? res.headers.get("x-linked-size") ?? res.headers.get("content-length") ?? 0);
      const size = total > 0 ? total : null;
      const expected = expectBytes ?? baseMb * 1048576;
      if (size && size < 1_000_000) return { state: "broken", status: res.status, note: `fayl juda kichik (${size} bayt) — GGUF emas, HTML sahifa bo'lishi mumkin`, sizeBytes: size };
      if (size && expected && Math.abs(size - expected) / expected > (expectBytes ? 0.01 : 0.15))
        return { state: "warn", status: res.status, note: `hajm o'zgargan: serverda ${(size / 1048576).toFixed(0)} MB, katalogda ${(expected / 1048576).toFixed(0)} MB`, sizeBytes: size };
      return { state: "ok", status: res.status, note: "", sizeBytes: size };
    }
    return { state: "broken", status: 0, note: "redirect juda ko'p", sizeBytes: null };
  } catch (e) {
    const t = e instanceof Error && (e.name === "TimeoutError" || e.name === "AbortError");
    return { state: "broken", status: 0, note: t ? "javob kelmadi (8 soniya)" : "ulanib bo'lmadi", sizeBytes: null };
  }
}

export async function handleAdmin(req: Request, env: Env, path: string, authed: true | null): Promise<Response> {
  // Sahifaning o'zi (HTML) ochiq beriladi — unda hech qanday ma'lumot yo'q; Access sozlangan bo'lsa
  // u baribir Access'dan o'tmaguncha ko'rinmaydi. Barcha ma'lumot /admin/api/* orqali, admin tekshiruvidan keyin.
  if (authed === null) return html(ADMIN_HTML);

  // CSRF: brauzer cookie (Access) bilan o'zgartirish so'rovlari maxsus sarlavhasiz o'tmaydi.
  if (req.method !== "GET" && req.headers.get("X-Admin") !== "1") return json({ error: "X-Admin sarlavhasi kerak" }, 400);

  const m = req.method;
  const actor = adminActor(req);

  // ---- O'zgarishlar tarixi ----
  if (m === "GET" && path === "/admin/api/history") return adminHistory(env, new URL(req.url));
  const rv = path.match(/^\/admin\/api\/history\/(\d{1,12})\/revert$/);
  if (rv && m === "POST") return adminRevert(env, actor, Number(rv[1]), await req.json().catch(() => null));

  // ---- Yangilik / e'lonlar ----
  if (path === "/admin/api/announcements" || path.startsWith("/admin/api/announcements/")) {
    const r = await handleAnnouncements(env, req, path, actor);
    if (r) return r;
  }

  // ---- Ilovani yangilash eslatmasi ----
  if (path === "/admin/api/app-update") {
    const r = await handleAppUpdate(env, req, path, actor);
    if (r) return r;
  }

  // ---- Pullik (provayder API) modellarini yoqish/o'chirish ----
  if (path === "/admin/api/cloud-models") {
    const r = await handleCloudModels(env, req, path, actor);
    if (r) return r;
  }

  // ---- Foydalanuvchi fikrlari ----
  if (path === "/admin/api/feedback" || path.startsWith("/admin/api/feedback/")) {
    const r = await handleAdminFeedback(env, req, path);
    if (r) return r;
  }

  if (m === "GET" && path === "/admin/api/models") {
    const { results } = await env.DB.prepare("SELECT * FROM models ORDER BY sort_order ASC, created_at ASC").all();
    return json({ models: results.map(modelRow) });
  }

  if (m === "POST" && path === "/admin/api/models") {
    const v = validateModel(await req.json().catch(() => null));
    if (!v.ok) return json({ error: v.error }, 400);
    const x = v.value;
    const exists = await env.DB.prepare("SELECT 1 AS x FROM models WHERE id = ?").bind(x.id).first();
    if (exists) return json({ error: "bunday id allaqachon bor" }, 409);
    const now = Date.now();
    await env.DB.prepare(
      `INSERT INTO models (id,name,download_url,direction,category,base_size_mb,base_ram_mb,min_phone_class,family,
        params_billion,max_context,role,sha256,size_bytes,enabled,sort_order,star_count,created_at,updated_at,system_prompt,agent_prompt,note,badge)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)`,
    ).bind(x.id, x.name, x.downloadUrl, x.direction, x.category, x.baseSizeMb, x.baseRamMb, x.minPhoneClass, x.family,
      x.paramsBillion, x.maxContext, x.role, x.sha256, x.sizeBytes, x.enabled ? 1 : 0, x.sortOrder, now, now, x.systemPrompt, x.agentPrompt, x.note, x.badge).run();
    await logModelChange(env, actor, null, snapOfInput(x));
    return json({ ok: true, id: x.id }, 201);
  }

  const one = path.match(/^\/admin\/api\/models\/([a-z0-9._-]{2,64})$/);
  if (one && m === "PUT") {
    const v = validateModel(await req.json().catch(() => null), one[1]);
    if (!v.ok) return json({ error: v.error }, 400);
    const x = v.value;
    const prev = await env.DB.prepare("SELECT * FROM models WHERE id = ?").bind(x.id).first<Record<string, unknown>>();
    const r = await env.DB.prepare(
      `UPDATE models SET name=?, download_url=?, direction=?, category=?, base_size_mb=?, base_ram_mb=?,
        min_phone_class=?, family=?, params_billion=?, max_context=?, role=?, sha256=?, size_bytes=?,
        enabled=?, sort_order=?, updated_at=?, system_prompt=?, agent_prompt=?, note=?, badge=? WHERE id=?`,
    ).bind(x.name, x.downloadUrl, x.direction, x.category, x.baseSizeMb, x.baseRamMb, x.minPhoneClass, x.family,
      x.paramsBillion, x.maxContext, x.role, x.sha256, x.sizeBytes, x.enabled ? 1 : 0, x.sortOrder, Date.now(), x.systemPrompt, x.agentPrompt, x.note, x.badge, x.id).run();
    if (!r.meta.changes) return json({ error: "topilmadi" }, 404);
    if (prev) await logModelChange(env, actor, snapOfRow(prev), snapOfInput(x));
    return json({ ok: true });
  }
  if (one && m === "DELETE") {
    const prev = await env.DB.prepare("SELECT * FROM models WHERE id = ?").bind(one[1]).first<Record<string, unknown>>();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM stars WHERE model_id = ?").bind(one[1]),
      env.DB.prepare("DELETE FROM stats_daily WHERE model_id = ?").bind(one[1]),
      env.DB.prepare("DELETE FROM models WHERE id = ?").bind(one[1]),
    ]);
    if (prev) await logModelChange(env, actor, snapOfRow(prev), null);
    return json({ ok: true });
  }

  // ---- Zaxira: katalogni JSON qilib olish / qaytarish (yulduzchalarga tegmaydi) ----
  if (m === "GET" && path === "/admin/api/export") {
    const { results } = await env.DB.prepare("SELECT * FROM models ORDER BY sort_order ASC, created_at ASC").all();
    return json({ version: 1, models: results.map((r) => { const { stars, updatedAt, ...rest } = modelRow(r) as Record<string, unknown>; return rest; }) });
  }
  if (m === "POST" && path === "/admin/api/import") {
    const body = (await req.json().catch(() => null)) as { models?: unknown } | null;
    if (!body || !Array.isArray(body.models) || body.models.length === 0 || body.models.length > 200)
      return json({ error: "models: 1–200 ta yozuvli massiv kerak" }, 400);
    const items: ReturnType<typeof validateModel>[] = body.models.map((x) => validateModel(x));
    const bad = items.findIndex((v) => !v.ok);
    if (bad >= 0) return json({ error: `${bad + 1}-yozuv: ${(items[bad] as { error: string }).error}` }, 400);
    const now = Date.now();
    const inputs = items.map((v) => (v as { value: import("./validate").ModelInput }).value);
    const ids = [...new Set(inputs.map((x) => x.id))];
    const prevRows = new Map<string, Record<string, unknown>>();
    for (let k = 0; k < ids.length; k += 50) {
      const part = ids.slice(k, k + 50);
      const { results: rs } = await env.DB.prepare(`SELECT * FROM models WHERE id IN (${part.map(() => "?").join(",")})`).bind(...part).all<Record<string, unknown>>();
      for (const r of rs) prevRows.set(r.id as string, r);
    }
    await env.DB.batch(inputs.map((x) => bindUpsert(env, x, now)));
    await logModelChanges(env, actor, inputs.map((x) => [prevRows.has(x.id) ? snapOfRow(prevRows.get(x.id)!) : null, snapOfInput(x)]), "(import) ");
    return json({ ok: true, count: items.length });
  }

  // HuggingFace havolasidan sha256 va hajmni olish (X-Linked-Etag / X-Linked-Size sarlavhalari)
  if (m === "POST" && path === "/admin/api/probe") {
    const body = (await req.json().catch(() => ({}))) as { url?: unknown };
    let target: URL;
    try {
      target = new URL(String(body.url ?? ""));
    } catch {
      return json({ error: "URL noto'g'ri" }, 400);
    }
    if (target.protocol !== "https:" || target.hostname !== "huggingface.co")
      return json({ error: "faqat https://huggingface.co havolalari" }, 400);
    const res = await fetch(target.toString(), { method: "HEAD", redirect: "manual" });
    const etag = (res.headers.get("x-linked-etag") ?? "").replace(/"/g, "").toLowerCase();
    const size = Number(res.headers.get("x-linked-size") ?? res.headers.get("content-length") ?? 0);
    return json({
      status: res.status,
      sha256: /^[0-9a-f]{64}$/.test(etag) ? etag : null,
      sizeBytes: size > 0 ? size : null,
    });
  }

  // Barcha (yoki faol) modellar havolasini ommaviy tekshirish: buzilgan / o'chgan / hajmi o'zgargan havolalarni topadi.
  if (m === "POST" && path === "/admin/api/check-links") {
    const body = (await req.json().catch(() => ({}))) as { onlyEnabled?: unknown; offset?: unknown };
    const offset = Number.isInteger(body.offset) && (body.offset as number) > 0 ? (body.offset as number) : 0;
    const where = body.onlyEnabled === false ? "" : "WHERE enabled = 1";
    const { results } = await env.DB.prepare(
      `SELECT id, name, download_url, size_bytes, base_size_mb, enabled FROM models ${where} ORDER BY sort_order ASC, created_at ASC LIMIT 12 OFFSET ${offset}`, // Cloudflare: bitta so'rovda subrequest chegarasi bor, shuning uchun bo'laklab
    ).all<{ id: string; name: string; download_url: string; size_bytes: number | null; base_size_mb: number; enabled: number }>();

    const out: LinkCheck[] = new Array(results.length);
    let next = 0;
    const worker = async () => {
      for (let i = next++; i < results.length; i = next++) {
        const r = results[i];
        out[i] = { id: r.id, name: r.name, enabled: r.enabled === 1, ...(await checkLink(r.download_url, r.size_bytes, r.base_size_mb)) };
      }
    };
    await Promise.all(Array.from({ length: 5 }, worker));
    const bad = out.filter((x) => x.state !== "ok").length;
    return json({ checkedAt: Date.now(), count: out.length, bad, hasMore: out.length === 12, results: out });
  }

  // ---- Hisobni o'chirish so'rovlari ----
  if (m === "GET" && path === "/admin/api/delete-requests") {
    const { results } = await env.DB.prepare("SELECT id, email, note, created_at, done_at FROM delete_requests ORDER BY done_at ASC, created_at DESC LIMIT 200").all();
    return json({ requests: results });
  }
  const dr = path.match(/^\/admin\/api\/delete-requests\/(\d{1,12})$/);
  if (dr && m === "POST") {
    await env.DB.prepare("UPDATE delete_requests SET done_at = ? WHERE id = ?").bind(Date.now(), Number(dr[1])).run();
    return json({ ok: true });
  }
  if (dr && m === "DELETE") {
    await env.DB.prepare("DELETE FROM delete_requests WHERE id = ?").bind(Number(dr[1])).run();
    return json({ ok: true });
  }

  // ---- Pullik rejim: obunalar, foydalanuvchi, qo'lda tarif berish ----
  if (m === "GET" && path === "/admin/api/cloud/overview") return adminCloudOverview(env);
  if (m === "GET" && path === "/admin/api/cloud/user") return adminCloudUser(env, new URL(req.url).searchParams.get("uid") ?? "");
  if (m === "POST" && path.startsWith("/admin/api/cloud/")) {
    const action = path.slice("/admin/api/cloud/".length);
    const body = (await req.json().catch(() => null)) as Record<string, unknown> | null;
    const res = await adminCloudAction(env, action, body);
    if (res.ok && action !== "resync") {
      const uid = typeof body?.uid === "string" ? body.uid : "";
      const detail = action === "grant" ? `${String(body?.productId ?? "")}, ${String(body?.days ?? "")} kun` : action === "revoke" ? "admin bergan tarif qaytarib olindi" : "limit hisoblagichlari nolga tushirildi";
      await logAudit(env, { actor, action, entity: "user", entityId: uid || String(body?.token ?? "").slice(0, 80), summary: detail });
    }
    return res;
  }

  if (m === "GET" && path === "/admin/api/stats") {
    const days = 30;
    const since = new Date(Date.now() - (days - 1) * 86400_000).toISOString().slice(0, 10);
    const [dau, counters, top, totals] = await Promise.all([
      env.DB.prepare("SELECT day, COUNT(*) AS n FROM dau WHERE day >= ? GROUP BY day").bind(since).all<{ day: string; n: number }>(),
      env.DB.prepare("SELECT day, metric, SUM(count) AS n FROM stats_daily WHERE day >= ? GROUP BY day, metric")
        .bind(since).all<{ day: string; metric: string; n: number }>(),
      env.DB.prepare(
        `SELECT m.id, m.name, m.star_count AS stars,
           COALESCE((SELECT SUM(count) FROM stats_daily s WHERE s.model_id = m.id AND s.metric = 'download_ok'), 0) AS downloads
         FROM models m ORDER BY m.star_count DESC, downloads DESC LIMIT 50`,
      ).all(),
      env.DB.prepare("SELECT COUNT(*) AS models, (SELECT COUNT(*) FROM stars) AS stars FROM models").first(),
    ]);
    const byDay: Record<string, Record<string, number>> = {};
    for (let i = 0; i < days; i++) {
      const d = new Date(Date.now() - i * 86400_000).toISOString().slice(0, 10);
      byDay[d] = { dau: 0, ...Object.fromEntries(METRICS.map((k) => [k, 0])) };
    }
    for (const r of dau.results) if (byDay[r.day]) byDay[r.day].dau = r.n;
    for (const r of counters.results) if (byDay[r.day]) byDay[r.day][r.metric] = r.n;
    return json({
      totals,
      days: Object.entries(byDay).sort(([a], [b]) => (a < b ? 1 : -1)).map(([day, v]) => ({ day, ...v })),
      topModels: top.results,
    });
  }

  return json({ error: "topilmadi" }, 404);
}
