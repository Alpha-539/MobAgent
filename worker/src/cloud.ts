import { verifyFirebase, type FirebaseUser } from "./auth";
import type { BudgetStatus } from "./budget";
import { acknowledge, consumePurchase, getOneTimePurchase, getSubscription } from "./play";
import { CLOUD_MODELS, CREDIT_ONLY_RANK, CREDIT_PRODUCTS, MICRO, PLANS, PRODUCT_TO_PLAN, byModelId, type CloudModel, type PlanId, type Provider } from "./plans";
import type { Env } from "./types";
import { isCloudModeOff, publicCloudStatus } from "./cloudmode";
import { base64UrlDecode, json } from "./util";
import { loadOffModels } from "./cloudmodels";

const MAX_BODY_BYTES = 8_000_000;
const MAX_MESSAGES = 200;
const MAX_IMAGES = 6;
const IMAGE_TOKENS = 1500;

const budgetOf = (env: Env, uid: string) => env.USER_BUDGET.get(env.USER_BUDGET.idFromName(uid));

const errJson = (status: number, message: string, code: string, extra: Record<string, string> = {}) =>
  json({ error: { message, type: "mobagent_error", code } }, status, extra);

function providerKey(env: Env, p: Provider): string | undefined {
  return p === "anthropic" ? env.ANTHROPIC_API_KEY
    : p === "openai" ? env.OPENAI_API_KEY
    : p === "moonshot" ? env.MOONSHOT_API_KEY
    : p === "deepseek" ? env.DEEPSEEK_API_KEY
    : env.GEMINI_API_KEY;
}

/** Kaliti (secret) qo'yilgan va admin paneldan o'chirilmagan modellar. */
const enabledModels = async (env: Env) => {
  const off = await loadOffModels(env);
  return CLOUD_MODELS.filter((m) => m.enabled !== false && !off.has(m.id) && !!providerKey(env, m.provider));
};

function humanWait(ms: number): string {
  const min = Math.max(1, Math.ceil(ms / 60_000));
  if (min < 90) return `${min} daqiqadan`;
  const h = Math.ceil(min / 60);
  if (h < 48) return `${h} soatdan`;
  return `${Math.ceil(h / 24)} kundan`;
}

// ───────────────────────── Obuna (Google Play) ─────────────────────────

/** Foydalanuvchining faol obunalaridan eng yuqorisini Durable Object'ga yozadi. */
async function syncUser(env: Env, uid: string): Promise<BudgetStatus> {
  const now = Date.now();
  const { results } = await env.DB.prepare("SELECT product_id, valid_until FROM subs WHERE uid = ? AND valid_until > ?")
    .bind(uid, now).all<{ product_id: string; valid_until: number }>();
  let best: { plan: PlanId; until: number } | null = null;
  for (const r of results) {
    const plan = PRODUCT_TO_PLAN[r.product_id];
    if (!plan) continue;
    if (!best || PLANS[plan].rank > PLANS[best.plan].rank || (plan === best.plan && r.valid_until > best.until)) {
      best = { plan, until: r.valid_until };
    }
  }
  const stub = budgetOf(env, uid);
  await stub.setPlan(best?.plan ?? null, best?.until ?? 0);
  return stub.status();
}

async function requireUser(req: Request, env: Env): Promise<FirebaseUser | Response> {
  const user = await verifyFirebase(req, env);
  if (!user) return errJson(401, "Kirish kerak", "unauthorized");
  if (!user.emailVerified) return errJson(403, "Email tasdiqlanmagan", "email_unverified");
  return user;
}

async function billingVerify(req: Request, env: Env): Promise<Response> {
  const u = await requireUser(req, env);
  if (u instanceof Response) return u;
  let body: { productId?: unknown; purchaseToken?: unknown };
  try { body = await req.json(); } catch { return errJson(400, "JSON kerak", "bad_request"); }
  const token = typeof body.purchaseToken === "string" ? body.purchaseToken : "";
  if (token.length < 10 || token.length > 1024) return errJson(400, "purchaseToken noto'g'ri", "bad_request");

  let sub;
  try { sub = await getSubscription(env, token); } catch (e) {
    console.error("play verify", e instanceof Error ? e.message : "error");
    return errJson(502, "Google Play bilan bog'lanib bo'lmadi. Keyinroq urinib ko'ring.", "play_unavailable");
  }
  if (!sub) return errJson(400, "Xarid topilmadi", "purchase_not_found");

  const owner = await env.DB.prepare("SELECT uid FROM subs WHERE purchase_token = ?").bind(token).first<{ uid: string }>();
  if (owner && owner.uid !== u.uid) return errJson(409, "Bu obuna boshqa hisobga biriktirilgan", "purchase_owned");
  if (sub.validUntil === 0) return errJson(402, "Obuna faol emas", "subscription_inactive");

  await env.DB.prepare(
    `INSERT INTO subs (purchase_token, uid, product_id, valid_until, acked, updated_at) VALUES (?1,?2,?3,?4,?5,?6)
     ON CONFLICT(purchase_token) DO UPDATE SET product_id=?3, valid_until=?4, updated_at=?6`,
  ).bind(token, u.uid, sub.productId, sub.validUntil, sub.acknowledged ? 1 : 0, Date.now()).run();

  if (!sub.acknowledged) {
    try {
      if (await acknowledge(env, sub.productId, token)) {
        await env.DB.prepare("UPDATE subs SET acked = 1 WHERE purchase_token = ?").bind(token).run();
      }
    } catch { /* kunlik cron qayta urinadi (3 kun ichida) */ }
  }
  return json(await syncUser(env, u.uid));
}

/** Credits (bir martalik xarid): Google'da tekshiradi, balansga qo'shadi (bir token = bir marta), so'ng iste'mol qiladi. */
async function billingCredits(req: Request, env: Env): Promise<Response> {
  const u = await requireUser(req, env);
  if (u instanceof Response) return u;
  let body: { productId?: unknown; purchaseToken?: unknown };
  try { body = await req.json(); } catch { return errJson(400, "JSON kerak", "bad_request"); }
  const productId = typeof body.productId === "string" ? body.productId : "";
  const token = typeof body.purchaseToken === "string" ? body.purchaseToken : "";
  const pack = CREDIT_PRODUCTS[productId];
  if (!pack) return errJson(400, "Noma'lum mahsulot", "bad_request");
  if (token.length < 10 || token.length > 1024) return errJson(400, "purchaseToken noto'g'ri", "bad_request");

  let p;
  try { p = await getOneTimePurchase(env, productId, token); } catch (e) {
    console.error("play credits", e instanceof Error ? e.message : "error");
    return errJson(502, "Google Play bilan bog'lanib bo'lmadi. Keyinroq urinib ko'ring.", "play_unavailable");
  }
  if (!p) return errJson(400, "Xarid topilmadi", "purchase_not_found");
  if (p.pending) return errJson(402, "To'lov hali tasdiqlanmagan", "purchase_pending");
  if (!p.purchased) return errJson(402, "Xarid bekor qilingan", "purchase_invalid");

  const owner = await env.DB.prepare("SELECT uid FROM credit_purchases WHERE purchase_token = ?").bind(token).first<{ uid: string }>();
  if (owner && owner.uid !== u.uid) return errJson(409, "Bu xarid boshqa hisobga biriktirilgan", "purchase_owned");
  const micro = Math.round(pack.budgetUsd * MICRO);
  if (!owner) {
    await env.DB.prepare("INSERT OR IGNORE INTO credit_purchases (purchase_token, uid, product_id, micro, consumed, created_at) VALUES (?1,?2,?3,?4,0,?5)")
      .bind(token, u.uid, productId, micro, Date.now()).run();
  }
  // Race condition oldini olish: bazadagi haqiqiy egasi u.uid ekanligini tasdiqlaymiz
  const verified = await env.DB.prepare("SELECT uid FROM credit_purchases WHERE purchase_token = ?").bind(token).first<{ uid: string }>();
  if (verified && verified.uid !== u.uid) return errJson(409, "Bu xarid boshqa hisobga biriktirilgan", "purchase_owned");

  const stub = budgetOf(env, u.uid);
  await stub.grant(token, micro); // takroriy chaqiruvda qayta qo'shilmaydi

  try {
    if (await consumePurchase(env, productId, token)) {
      await env.DB.prepare("UPDATE credit_purchases SET consumed = 1 WHERE purchase_token = ?").bind(token).run();
    }
  } catch { /* kunlik cron 3 kun ichida qayta urinadi */ }
  return json(await stub.status());
}

/** Google Pub/Sub push (RTDN): uzaytirish, bekor qilish, to'lov muammosi, qaytarish. */
async function billingRtdn(req: Request, env: Env, url: URL): Promise<Response> {
  if (!env.RTDN_SECRET || url.searchParams.get("secret") !== env.RTDN_SECRET) return json({ error: "ruxsat yo'q" }, 403);
  let token = "";
  let voided = false;
  try {
    const msg = (await req.json()) as { message?: { data?: string } };
    const rawData = base64UrlDecode(msg.message?.data ?? "");
    const data = JSON.parse(rawData) as {
      subscriptionNotification?: { purchaseToken?: string };
      voidedPurchaseNotification?: { purchaseToken?: string };
    };
    token = data.subscriptionNotification?.purchaseToken ?? data.voidedPurchaseNotification?.purchaseToken ?? "";
    voided = !!data.voidedPurchaseNotification;
  } catch {
    return json({ ok: true }); // buzuq xabar: qayta yuborilmasin
  }
  if (!token) return json({ ok: true }); // testNotification va h.k.

  const row = await env.DB.prepare("SELECT uid, product_id FROM subs WHERE purchase_token = ?").bind(token)
    .first<{ uid: string; product_id: string }>();
  if (!row) return json({ ok: true }); // hali ilova tasdiqlamagan — /billing/verify o'zi yozadi

  let validUntil = 0;
  if (!voided) {
    let sub;
    try { sub = await getSubscription(env, token); } catch { return json({ error: "vaqtincha" }, 500); } // Pub/Sub qayta yuboradi
    validUntil = sub?.validUntil ?? 0;
    if (sub && !sub.acknowledged && validUntil > 0) {
      try { if (await acknowledge(env, row.product_id, token)) await env.DB.prepare("UPDATE subs SET acked = 1 WHERE purchase_token = ?").bind(token).run(); } catch { /* cron */ }
    }
  }
  await env.DB.prepare("UPDATE subs SET valid_until = ?, updated_at = ? WHERE purchase_token = ?").bind(validUntil, Date.now(), token).run();
  await syncUser(env, row.uid);
  return json({ ok: true });
}

/** Kunlik cron: 3 kun ichida tasdiqlanmagan xaridlarni qayta tasdiqlash. */
export async function cloudScheduled(env: Env): Promise<void> {
  const { results } = await env.DB.prepare("SELECT purchase_token, product_id FROM subs WHERE acked = 0 AND valid_until > ?")
    .bind(Date.now()).all<{ purchase_token: string; product_id: string }>();
  for (const r of results) {
    try {
      if (await acknowledge(env, r.product_id, r.purchase_token)) {
        await env.DB.prepare("UPDATE subs SET acked = 1 WHERE purchase_token = ?").bind(r.purchase_token).run();
      }
    } catch { /* ertaga yana */ }
  }
  await retryConsume(env);
}

/** (cron davomi) iste'mol qilinmagan credits xaridlarini qayta iste'mol qilish. */
async function retryConsume(env: Env): Promise<void> {
  const { results } = await env.DB.prepare("SELECT purchase_token, product_id FROM credit_purchases WHERE consumed = 0 AND created_at > ?")
    .bind(Date.now() - 3 * 86400_000).all<{ purchase_token: string; product_id: string }>();
  for (const r of results) {
    try {
      if (await consumePurchase(env, r.product_id, r.purchase_token)) {
        await env.DB.prepare("UPDATE credit_purchases SET consumed = 1 WHERE purchase_token = ?").bind(r.purchase_token).run();
      }
    } catch { /* ertaga yana */ }
  }
}

/** DELETE /me dan chaqiriladi. */
export async function wipeCloudUser(env: Env, uid: string): Promise<void> {
  await env.DB.prepare("DELETE FROM subs WHERE uid = ?").bind(uid).run();
  await env.DB.prepare("DELETE FROM credit_purchases WHERE uid = ?").bind(uid).run();
  await budgetOf(env, uid).wipe();
}

// ───────────────────────── Chat (hisoblangan proksi) ─────────────────────────

type Part = { t: "text"; text: string } | { t: "img"; mime: string; data: string };
interface Msg { role: "system" | "user" | "assistant"; parts: Part[] }

const IMG_RE = /^data:(image\/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)$/;

/** OpenAI formatidagi messages'ni tekshiradi (faqat matn va data: rasmlar; tashqi URL rad etiladi). */
function normalize(raw: unknown): Msg[] | string {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_MESSAGES) return "messages noto'g'ri";
  const out: Msg[] = [];
  let images = 0;
  for (const m of raw) {
    if (typeof m !== "object" || m === null) return "messages noto'g'ri";
    const { role, content } = m as { role?: unknown; content?: unknown };
    if (role !== "system" && role !== "user" && role !== "assistant") return "role noto'g'ri";
    const parts: Part[] = [];
    if (typeof content === "string") parts.push({ t: "text", text: content });
    else if (Array.isArray(content)) {
      for (const p of content) {
        const o = p as { type?: string; text?: unknown; image_url?: { url?: unknown } };
        if (o?.type === "text" && typeof o.text === "string") parts.push({ t: "text", text: o.text });
        else if (o?.type === "image_url" && typeof o.image_url?.url === "string") {
          const mm = IMG_RE.exec(o.image_url.url);
          if (!mm) return "faqat data: rasmlar qabul qilinadi";
          if (++images > MAX_IMAGES) return "rasmlar juda ko'p";
          parts.push({ t: "img", mime: mm[1], data: mm[2] });
        } else return "content noto'g'ri";
      }
    } else return "content noto'g'ri";
    if (parts.length === 0) parts.push({ t: "text", text: "" });
    out.push({ role, parts });
  }
  return out;
}

/** Matn tokenlarini hisoblash: lotin/ASCII va ko'p baytli (kirill, arab va h.k.) tillarni adolatli hisoblaydi */
export function estimateTextTokens(text: string): number {
  if (!text) return 0;
  let ascii = 0;
  let nonAscii = 0;
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) <= 127) ascii++;
    else nonAscii++;
  }
  return Math.ceil(ascii / 3.5 + nonAscii / 1.3);
}

const estimateInputTokens = (msgs: Msg[]): number => {
  let tokens = 0, imgs = 0;
  for (const m of msgs) {
    for (const p of m.parts) {
      if (p.t === "text") tokens += estimateTextTokens(p.text);
      else imgs++;
    }
  }
  return tokens + imgs * IMAGE_TOKENS;
};

const costMicro = (cm: CloudModel, inTok: number, outTok: number) => inTok * cm.inPerM + outTok * cm.outPerM;

const todayUtc = () => new Date().toISOString().slice(0, 10);

/** Haqiqiy API xarajatni (mikro-dollar) kun va provayder bo'yicha yig'ib boradi — admin paneldagi moliya ko'rinishi va kunlik chegara uchun. */
async function recordSpend(env: Env, provider: Provider, micro: number): Promise<void> {
  if (!(micro > 0)) return;
  await env.DB.prepare(
    "INSERT INTO spend_daily (day, provider, micro) VALUES (?1, ?2, ?3) ON CONFLICT(day, provider) DO UPDATE SET micro = micro + ?3",
  ).bind(todayUtc(), provider, Math.round(micro)).run();
}

function openAiMessages(msgs: Msg[]) {
  return msgs.map((m) => {
    if (m.parts.every((p) => p.t === "text")) return { role: m.role, content: m.parts.map((p) => (p as { text: string }).text).join("") };
    return {
      role: m.role,
      content: m.parts.map((p) => p.t === "text"
        ? { type: "text", text: p.text }
        : { type: "image_url", image_url: { url: `data:${p.mime};base64,${p.data}` } }),
    };
  });
}

function anthropicBody(msgs: Msg[], cm: CloudModel, maxOut: number) {
  const system = msgs.filter((m) => m.role === "system").map((m) => m.parts.map((p) => (p.t === "text" ? p.text : "")).join("")).join("\n\n");
  const turns: { role: "user" | "assistant"; content: unknown[] }[] = [];
  for (const m of msgs) {
    if (m.role === "system") continue;
    const content = m.parts
      .map((p) => (p.t === "text" ? (p.text ? { type: "text", text: p.text } : null) : { type: "image", source: { type: "base64", media_type: p.mime, data: p.data } }))
      .filter((x): x is NonNullable<typeof x> => x !== null);
    if (content.length === 0) continue;
    const last = turns[turns.length - 1];
    if (last && last.role === m.role) last.content.push(...content); // ketma-ket bir xil rol birlashtiriladi
    else turns.push({ role: m.role, content });
  }
  if (turns.length === 0 || turns[0].role !== "user") turns.unshift({ role: "user", content: [{ type: "text", text: "." }] });
  // Sampling parametrlari (temperature va h.k.) yangi Claude modellarida rad etilishi mumkin — yuborilmaydi.
  return { model: cm.upstream, max_tokens: maxOut, stream: true, ...(system ? { system } : {}), messages: turns };
}

async function chatCompletions(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const u = await requireUser(req, env);
  if (u instanceof Response) return u;

  const len = Number(req.headers.get("content-length") ?? "0");
  if (len > MAX_BODY_BYTES) return errJson(413, "So'rov juda katta", "too_large");
  let body: { model?: unknown; messages?: unknown; max_tokens?: unknown; max_completion_tokens?: unknown; temperature?: unknown };
  try { body = await req.json(); } catch { return errJson(400, "JSON kerak", "bad_request"); }

  const cm = typeof body.model === "string" ? byModelId(body.model) : undefined;
  if (!cm || cm.enabled === false || (await loadOffModels(env)).has(cm.id) || !providerKey(env, cm.provider)) return errJson(404, "Model topilmadi", "model_not_found");
  const msgs = normalize(body.messages);
  if (typeof msgs === "string") return errJson(400, msgs, "bad_request");
  if (!cm.vision && msgs.some((m) => m.parts.some((x) => x.t === "img"))) {
    return errJson(400, "Bu model rasmni qabul qilmaydi. Rasm uchun boshqa modelni tanlang.", "no_vision");
  }

  // Umumiy kunlik xarajat chegarasi (ixtiyoriy): provayder balansi tugab qolishidan himoya.
  const cap = Number(env.DAILY_SPEND_CAP_USD);
  if (cap > 0) {
    const row = await env.DB.prepare("SELECT COALESCE(SUM(micro), 0) AS m FROM spend_daily WHERE day = ?").bind(todayUtc()).first<{ m: number }>();
    if ((row?.m ?? 0) / 1e6 >= cap) return errJson(503, "Xizmat bugun vaqtincha band. Keyinroq urinib ko'ring.", "capacity", { "retry-after": "3600" });
  }

  const stub = budgetOf(env, u.uid);
  const st = await stub.status();
  if (!st.planId && st.credits <= 0) return errJson(402, "Pullik tarif kerak", "no_plan");
  if ((st.planId ? PLANS[st.planId].rank : CREDIT_ONLY_RANK) < cm.minRank) return errJson(403, "Bu model yuqoriroq tarifda ochiladi", "plan_too_low");

  const asked = Number(body.max_tokens ?? body.max_completion_tokens ?? 2048);
  let maxOut = Math.min(cm.maxOut, Math.max(16, Number.isFinite(asked) ? Math.floor(asked) : 2048));
  if (cm.minOut) maxOut = Math.max(maxOut, cm.minOut); // fikrlash tokenlari ham shu limitdan ketadi
  const estIn = estimateInputTokens(msgs);
  const est = costMicro(cm, estIn, maxOut);

  const reqId = crypto.randomUUID();
  const r = await stub.reserve(reqId, est);
  if (!r.ok) {
    if (r.reason === "no_plan") return errJson(402, "Pullik tarif kerak", "no_plan");
    if (r.reason === "busy") return errJson(429, "Bir vaqtda juda ko'p so'rov. Avvalgisi tugashini kuting.", "busy", { "retry-after": "5" });
    const wait = Math.max(0, (r.retryAtMs ?? Date.now()) - Date.now());
    const what = r.reason === "window" ? "5 soatlik" : r.reason === "week" ? "Haftalik" : "Oylik";
    return errJson(429, `${what} limit tugadi. Taxminan ${humanWait(wait)} keyin tiklanadi yoki credits sotib oling.`, `limit_${r.reason}`,
      { "retry-after": String(Math.ceil(wait / 1000)) });
  }

  // Provayderga so'rov
  let up: Response;
  try {
    if (cm.provider === "anthropic") {
      up = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: { "content-type": "application/json", "x-api-key": env.ANTHROPIC_API_KEY!, "anthropic-version": "2023-06-01" },
        body: JSON.stringify(anthropicBody(msgs, cm, maxOut)),
      });
    } else {
      const isOpenAi = cm.provider === "openai";
      const isMoon = cm.provider === "moonshot";
      const isDeepSeek = cm.provider === "deepseek";
      // temperature: GPT-5/6 va Kimi K3 (fikrlash yoqiq) rad etadi — faqat Gemini'ga yuboriladi.
      const temp = typeof body.temperature === "number" && body.temperature >= 0 && body.temperature <= 2 ? body.temperature : undefined;
      const payload: Record<string, unknown> = {
        model: cm.upstream,
        messages: openAiMessages(msgs),
        stream: true,
        stream_options: { include_usage: true },
        ...(isOpenAi ? { max_completion_tokens: maxOut } : { max_tokens: maxOut }),
        ...(!isOpenAi && !isMoon && temp !== undefined ? { temperature: temp } : {}),
      };
      const url = isOpenAi ? "https://api.openai.com/v1/chat/completions"
        : isMoon ? "https://api.moonshot.ai/v1/chat/completions"
        : isDeepSeek ? "https://api.deepseek.com/chat/completions"
        : "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
      up = await fetch(url, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${providerKey(env, cm.provider)}` },
        body: JSON.stringify(payload),
      });
    }
  } catch {
    await stub.settle(reqId, 0);
    return errJson(502, "AI xizmatiga ulanib bo'lmadi", "upstream_error");
  }
  if (!up.ok || !up.body) {
    console.error("upstream", cm.provider, up.status); // faqat holat kodi, matn emas
    try { await up.body?.cancel(); } catch { /* */ }
    await stub.settle(reqId, 0);
    return errJson(up.status === 429 ? 503 : 502, "AI xizmati hozir band yoki ishlamayapti. Birozdan keyin urinib ko'ring.", "upstream_error");
  }

  const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
  ctx.waitUntil(pump(env, stub, reqId, cm, up, estIn, writable));
  return new Response(readable, {
    headers: { "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-store", "x-accel-buffering": "no" },
  });
}

/** Provayder javobini OpenAI SSE formatida ilovaga uzatadi va oxirida HAQIQIY token sonidan narxni ayiradi. */
async function pump(
  env: Env,
  stub: DurableObjectStub<import("./budget").UserBudget>,
  reqId: string, cm: CloudModel, up: Response, estIn: number, writable: WritableStream<Uint8Array>,
): Promise<void> {
  const writer = writable.getWriter();
  const reader = up.body!.getReader();
  const dec = new TextDecoder();
  const enc = new TextEncoder();
  const anth = cm.provider === "anthropic";
  const id = `chatcmpl-${reqId}`;
  const send = (s: string) => writer.write(enc.encode(s));
  const chunk = (delta: Record<string, unknown>, finish: string | null) =>
    `data: ${JSON.stringify({ id, object: "chat.completion.chunk", model: cm.id, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;

  let buf = "";
  let outChars = 0;
  let accumulatedOut = "";
  let usage: { inTok: number; outTok: number } | null = null;
  let aIn = 0, aOut = 0, aGot = false;

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let nl: number;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl).replace(/\r$/, "");
        buf = buf.slice(nl + 1);
        if (!line.startsWith("data:")) continue;
        const payload = line.slice(5).trim();
        if (!payload) continue;

        if (!anth) {
          if (payload === "[DONE]") { await send("data: [DONE]\n\n"); continue; }
          type U = { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number };
          let o: { usage?: U; choices?: { delta?: { content?: unknown; reasoning_content?: unknown }; usage?: U }[] };
          try { o = JSON.parse(payload); } catch { continue; }
          // Moonshot (Kimi) ba'zan usage'ni choices[0].usage ichida qaytaradi
          const uo = o.usage ?? o.choices?.[0]?.usage;
          if (uo) {
            const p = uo.prompt_tokens ?? 0;
            // Fikrlash tokenlari ba'zan completion_tokens'ga kirmaydi, total_tokens'da bo'ladi — kattasini olamiz.
            usage = { inTok: p, outTok: Math.max(uo.completion_tokens ?? 0, (uo.total_tokens ?? 0) - p) };
          }
          const d = o.choices?.[0]?.delta?.content;
          if (typeof d === "string") {
            outChars += d.length;
            accumulatedOut += d;
          }
          const r = o.choices?.[0]?.delta?.reasoning_content;
          if (typeof r === "string") {
            outChars += r.length;
            accumulatedOut += r;
          }
          await send(`data: ${payload}\n\n`);
        } else {
          let o: {
            type?: string;
            message?: { usage?: { input_tokens?: number; cache_creation_input_tokens?: number; cache_read_input_tokens?: number } };
            delta?: { type?: string; text?: unknown; thinking?: unknown; stop_reason?: string };
            usage?: { output_tokens?: number };
          };
          try { o = JSON.parse(payload); } catch { continue; }
          if (o.type === "message_start") {
            const u = o.message?.usage;
            aIn = (u?.input_tokens ?? 0) + (u?.cache_creation_input_tokens ?? 0) + (u?.cache_read_input_tokens ?? 0);
            aGot = true;
          } else if (o.type === "content_block_delta") {
            const dt = o.delta?.type;
            if (dt === "text_delta" && typeof o.delta?.text === "string") {
              outChars += o.delta.text.length;
              accumulatedOut += o.delta.text;
              await send(chunk({ content: o.delta.text }, null));
            } else if (dt === "thinking_delta" && typeof o.delta?.thinking === "string") {
              outChars += o.delta.thinking.length;
              accumulatedOut += o.delta.thinking;
              await send(chunk({ reasoning_content: o.delta.thinking }, null));
            }
          } else if (o.type === "message_delta") {
            if (typeof o.usage?.output_tokens === "number") aOut = o.usage.output_tokens;
            if (o.delta?.stop_reason) await send(chunk({}, o.delta.stop_reason === "max_tokens" ? "length" : "stop"));
          } else if (o.type === "message_stop") {
            await send("data: [DONE]\n\n");
          } else if (o.type === "error") {
            await send(`data: ${JSON.stringify({ error: { message: "AI xizmatida xatolik", code: "upstream_error" } })}\n\n`);
          }
        }
      }
    }
  } catch {
    // Mijoz uzilgan yoki provayder oqimi buzilgan: provayderdan o'qishni to'xtatamiz.
    try { await reader.cancel(); } catch { /* */ }
  } finally {
    try { await writer.close(); } catch { /* */ }
    if (anth && aGot) usage = { inTok: aIn, outTok: aOut };
    // Usage kelmasa (uzilish): kirish bahosi + olingan matndan adolatli ko'p tilli chiqish bahosi.
    const fallbackOut = estimateTextTokens(accumulatedOut) || Math.ceil(outChars / 3);
    const cost = usage ? costMicro(cm, usage.inTok, usage.outTok) : costMicro(cm, estIn, fallbackOut);
    try { await stub.settle(reqId, cost); } catch { console.error("settle failed"); }
    try { await recordSpend(env, cm.provider, cost); } catch { console.error("recordSpend failed"); }
  }
}

// ───────────────────────── Bepul model (uchinchi tomon servis) ─────────────────────────

// ───────────────────────── Yo'naltirish ─────────────────────────

export async function handleCloud(req: Request, env: Env, ctx: ExecutionContext, path: string, url: URL): Promise<Response | null> {
  const m = req.method;
  if (m === "GET" && path === "/cloud/status") return publicCloudStatus(env);
  // Admin pullik rejimni o'chirgan ("Tez orada"): chat, xarid va tarif so'rovlari rad etiladi.
  // billingRtdn ATAYLAB bu yerda emas — Google Play bildirishnomalari qabul qilinaveradi.
  const cloudPath = (m === "POST" && (path === "/v1/chat/completions" || path === "/billing/verify" || path === "/billing/credits"))
    || (m === "GET" && (path === "/v1/models" || path === "/me/plan"));
  if (cloudPath && (await isCloudModeOff(env))) {
    return errJson(503, "Pullik rejim tez orada ishga tushadi", "cloud_coming_soon");
  }
  if (m === "POST" && path === "/v1/chat/completions") return chatCompletions(req, env, ctx);
  if (m === "POST" && path === "/billing/verify") return billingVerify(req, env);
  if (m === "POST" && path === "/billing/credits") return billingCredits(req, env);
  if (m === "POST" && path === "/billing/rtdn") return billingRtdn(req, env, url);

  if (m === "GET" && (path === "/v1/models" || path === "/me/plan")) {
    const u = await requireUser(req, env);
    if (u instanceof Response) return u;
    const st = await syncUser(env, u.uid); // Play bilan bir xil holatda bo'lishi uchun DB'dan qayta hisoblanadi
    const rank = st.planId ? PLANS[st.planId].rank : st.credits > 0 ? CREDIT_ONLY_RANK : 0;
    const paid = (await enabledModels(env)).map((cm) => ({
      id: cm.id, object: "model", owned_by: cm.provider, name: cm.name, tier: cm.tier, locked: rank < cm.minRank,
    }));
    const models = paid;
    if (path === "/me/plan") return json({ ...st, models });
    if (!st.planId && st.credits <= 0) return errJson(402, "Pullik tarif kerak", "no_plan");
    return json({ object: "list", data: models.filter((x) => !x.locked) });
  }
  return null;
}

// ───────────────────────── Admin (obunalar va foydalanish) ─────────────────────────

const UID_RE = /^[A-Za-z0-9_-]{6,128}$/;
/** Admin qo'lda bergan tarif tokenlari shu prefiks bilan boshlanadi (Google Play bilan aloqasi yo'q). */
const GRANT_PREFIX = "admin:";

interface SubRow { purchase_token: string; uid: string; product_id: string; valid_until: number; acked: number; updated_at: number }

const subView = (r: SubRow) => ({
  token: r.purchase_token, uid: r.uid, productId: r.product_id, validUntil: r.valid_until,
  acked: r.acked === 1, updatedAt: r.updated_at, manual: r.purchase_token.startsWith(GRANT_PREFIX),
});

export async function adminCloudOverview(env: Env): Promise<Response> {
  const now = Date.now();
  const offModels = await loadOffModels(env, true);
  const [active, recent, unacked] = await Promise.all([
    env.DB.prepare("SELECT uid, product_id, valid_until FROM subs WHERE valid_until > ?").bind(now)
      .all<{ uid: string; product_id: string; valid_until: number }>(),
    env.DB.prepare("SELECT purchase_token, uid, product_id, valid_until, acked, updated_at FROM subs ORDER BY updated_at DESC LIMIT 100")
      .all<SubRow>(),
    env.DB.prepare("SELECT COUNT(*) AS n FROM subs WHERE acked = 0 AND valid_until > ?").bind(now).first<{ n: number }>(),
  ]);
  const best = new Map<string, PlanId>();
  let expiring = 0;
  for (const r of active.results) {
    const plan = PRODUCT_TO_PLAN[r.product_id];
    if (!plan) continue;
    const cur = best.get(r.uid);
    if (!cur || PLANS[plan].rank > PLANS[cur].rank) best.set(r.uid, plan);
    if (r.valid_until < now + 7 * 86400_000) expiring++;
  }
  const sp = await env.DB.prepare("SELECT day, provider, micro FROM spend_daily WHERE day >= date('now', '-30 day')")
    .all<{ day: string; provider: string; micro: number }>();
  const today = todayUtc();
  const spend = { todayUsd: 0, days30Usd: 0, byProvider: {} as Record<string, number>, capUsd: Number(env.DAILY_SPEND_CAP_USD) > 0 ? Number(env.DAILY_SPEND_CAP_USD) : 0 };
  for (const r of sp.results) {
    const usd = r.micro / 1e6;
    spend.days30Usd += usd;
    if (r.day === today) spend.todayUsd += usd;
    spend.byProvider[r.provider] = (spend.byProvider[r.provider] ?? 0) + usd;
  }
  const byPlan: Record<PlanId, number> = { basic: 0, standard: 0, pro: 0 };
  let exposureUsd = 0;
  for (const plan of best.values()) { byPlan[plan]++; exposureUsd += PLANS[plan].monthlyUsd; }
  return json({
    totalActive: best.size,
    byPlan,
    exposureUsd,
    expiring7d: expiring,
    spend,
    unacked: unacked?.n ?? 0,
    cloudModeOff: await isCloudModeOff(env, true),
    keys: {
      anthropic: !!env.ANTHROPIC_API_KEY, openai: !!env.OPENAI_API_KEY, gemini: !!env.GEMINI_API_KEY, moonshot: !!env.MOONSHOT_API_KEY, deepseek: !!env.DEEPSEEK_API_KEY,
      playServiceAccount: !!env.PLAY_SERVICE_ACCOUNT, rtdnSecret: !!env.RTDN_SECRET,
    },
    plans: Object.values(PLANS),
    products: PRODUCT_TO_PLAN,
    models: CLOUD_MODELS.map((m) => ({
      id: m.id, name: m.name, provider: m.provider, tier: m.tier, minRank: m.minRank,
      inPerM: m.inPerM, outPerM: m.outPerM,
      keyed: !!providerKey(env, m.provider), codeOff: m.enabled === false, off: offModels.has(m.id),
      enabled: m.enabled !== false && !offModels.has(m.id) && !!providerKey(env, m.provider),
    })),
    recent: recent.results.map(subView),
  });
}

export async function adminCloudUser(env: Env, uid: string): Promise<Response> {
  if (!UID_RE.test(uid)) return json({ error: "uid noto'g'ri" }, 400);
  const { results } = await env.DB.prepare(
    "SELECT purchase_token, uid, product_id, valid_until, acked, updated_at FROM subs WHERE uid = ? ORDER BY updated_at DESC",
  ).bind(uid).all<SubRow>();
  const status = await syncUser(env, uid);
  return json({ uid, subs: results.map(subView), status });
}

/** POST /admin/api/cloud/<grant|revoke|resync|reset-usage> */
export async function adminCloudAction(env: Env, action: string, raw: unknown): Promise<Response> {
  const b = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
  const uid = typeof b.uid === "string" ? b.uid : "";

  if (action === "revoke") {
    const token = typeof b.token === "string" ? b.token : "";
    if (!token.startsWith(GRANT_PREFIX)) return json({ error: "Faqat admin bergan tarifni qaytarib olish mumkin. Play obunasi Play Console orqali bekor qilinadi." }, 400);
    const row = await env.DB.prepare("SELECT uid FROM subs WHERE purchase_token = ?").bind(token).first<{ uid: string }>();
    if (!row) return json({ error: "topilmadi" }, 404);
    await env.DB.prepare("DELETE FROM subs WHERE purchase_token = ?").bind(token).run();
    await syncUser(env, row.uid);
    return json({ ok: true });
  }

  if (!UID_RE.test(uid)) return json({ error: "uid noto'g'ri" }, 400);

  if (action === "grant") {
    const productId = typeof b.productId === "string" ? b.productId : "";
    const days = Number(b.days);
    if (!PRODUCT_TO_PLAN[productId]) return json({ error: "productId noto'g'ri" }, 400);
    if (!Number.isInteger(days) || days < 1 || days > 366) return json({ error: "days 1..366 bo'lishi kerak" }, 400);
    const now = Date.now();
    await env.DB.prepare(
      "INSERT INTO subs (purchase_token, uid, product_id, valid_until, acked, updated_at) VALUES (?1,?2,?3,?4,1,?5)",
    ).bind(`${GRANT_PREFIX}${uid}:${now}`, uid, productId, now + days * 86400_000, now).run();
    return json({ ok: true, status: await syncUser(env, uid) });
  }
  if (action === "resync") return json({ ok: true, status: await syncUser(env, uid) });
  if (action === "reset-usage") {
    await budgetOf(env, uid).resetUsage();
    return json({ ok: true, status: await syncUser(env, uid) });
  }
  return json({ error: "topilmadi" }, 404);
}
