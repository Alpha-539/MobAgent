import { SignJWT, importPKCS8 } from "jose";
import { PRODUCT_TO_PLAN, type PlanId } from "./plans";
import type { Env } from "./types";
import { sanitizePrivateKey } from "./util";

const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const GRACE_EXTRA_MS = 3 * 86400_000;

/** Test token faqat dev muhitida (ALLOW_TEST_PURCHASES="1") qabul qilinadi. */
function isTestToken(env: Env, token: string): boolean {
  return env.ALLOW_TEST_PURCHASES === "1" && token.startsWith("test_");
}

let cached: { token: string; exp: number } | null = null;

/** Service account (PLAY_SERVICE_ACCOUNT secret, to'liq JSON) orqali OAuth token oladi. */
async function accessToken(env: Env): Promise<string> {
  if (cached && Date.now() < cached.exp - 60_000) return cached.token;
  if (!env.PLAY_SERVICE_ACCOUNT) {
    throw new Error("PLAY_SERVICE_ACCOUNT muhit o'zgaruvchisi topilmadi");
  }
  
  const sa = JSON.parse(env.PLAY_SERVICE_ACCOUNT) as { client_email: string; private_key: string };
  const key = await importPKCS8(sanitizePrivateKey(sa.private_key), "RS256");
  const assertion = await new SignJWT({ scope: SCOPE })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt()
    .setExpirationTime("55m")
    .sign(key);

  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });

  if (!r.ok) throw new Error(`Google OAuth xatosi: ${r.status}`);
  const j = (await r.json()) as { access_token: string; expires_in: number };
  cached = { token: j.access_token, exp: Date.now() + j.expires_in * 1000 };
  return cached.token;
}

export interface PlaySub {
  productId: string;
  planId: PlanId | null;
  state: string;
  /** Tarif amal qilish muddati (ms). Faol bo'lmasa 0. */
  validUntil: number;
  acknowledged: boolean;
  testPurchase: boolean;
}

/** 
 * purchases.subscriptionsv2.get
 * Token topilmasa yoki yaroqsiz bo'lsa null qaytaradi.
 */
export async function getSubscription(
  env: Env, 
  purchaseToken: string, 
  expectedProductId?: string
): Promise<PlaySub | null> {
  // 1. TEST REJIMI: faqat ALLOW_TEST_PURCHASES="1" bo'lganda va token "test_" bilan boshlansa.
  //    Production'da bu o'zgaruvchi QO'YILMAYDI — shunda soxta token hech qachon obuna ochmaydi.
  if (isTestToken(env, purchaseToken)) {
    const productId =
      expectedProductId && PRODUCT_TO_PLAN[expectedProductId]
        ? expectedProductId
        : Object.keys(PRODUCT_TO_PLAN).find((id) => purchaseToken.toLowerCase().includes(id.toLowerCase())) ??
          Object.keys(PRODUCT_TO_PLAN)[0];
    if (!productId) return null;
    return {
      productId,
      planId: PRODUCT_TO_PLAN[productId],
      state: "SUBSCRIPTION_STATE_ACTIVE",
      validUntil: Date.now() + 30 * 86400_000,
      acknowledged: true,
      testPurchase: true,
    };
  }

  // =========================================================================
  // 2. HAQIQIY GOOGLE PLAY REJIMI (Production)
  // =========================================================================
  try {
    const at = await accessToken(env);
    const url = `${API}/${encodeURIComponent(env.PLAY_PACKAGE)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
    const r = await fetch(url, { headers: { authorization: `Bearer ${at}` } });

    if (r.status === 400 || r.status === 404 || r.status === 410) return null;
    if (!r.ok) throw new Error(`Google Play API javobi: ${r.status}`);

    const j = (await r.json()) as {
      subscriptionState?: string;
      acknowledgementState?: string;
      testPurchase?: unknown;
      lineItems?: { productId?: string; expiryTime?: string }[];
    };

    const item = (j.lineItems ?? []).find((l) => l.productId && PRODUCT_TO_PLAN[l.productId]);
    if (!item || !item.productId) return null;

    const state = j.subscriptionState ?? "";
    const expiry = item.expiryTime ? Date.parse(item.expiryTime) : 0;
    let validUntil = 0;

    if ((state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_CANCELED") && expiry > Date.now()) {
      validUntil = expiry;
    } else if (state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD") {
      validUntil = Math.max(expiry, Date.now()) + GRACE_EXTRA_MS;
    }

    return {
      productId: item.productId,
      planId: PRODUCT_TO_PLAN[item.productId],
      state,
      validUntil,
      acknowledged: j.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
      testPurchase: j.testPurchase !== undefined,
    };
  } catch (err) {
    console.error("Google Play verification error:", err);
    throw err;
  }
}

/** Xaridni tasdiqlash (Acknowledge) */
export async function acknowledge(env: Env, productId: string, purchaseToken: string): Promise<boolean> {
  if (isTestToken(env, purchaseToken)) return true;

  const at = await accessToken(env);
  const url = `${API}/${encodeURIComponent(env.PLAY_PACKAGE)}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
  const r = await fetch(url, {
    method: "POST",
    headers: { authorization: `Bearer ${at}`, "content-type": "application/json" },
    body: "{}",
  });

  return r.ok || r.status === 400;
}

// ───────────────────────── Bir martalik xaridlar (credits) ─────────────────────────

export interface PlayOneTime {
  purchased: boolean;
  pending: boolean;
  consumed: boolean;
}

/** purchases.products.get — mahsulot ID'si URL'da bo'lgani uchun Google uni token bilan solishtiradi (soxta ID o'tmaydi). */
export async function getOneTimePurchase(env: Env, productId: string, purchaseToken: string): Promise<PlayOneTime | null> {
  if (isTestToken(env, purchaseToken)) return { purchased: true, pending: false, consumed: false };
  const at = await accessToken(env);
  const url = `${API}/${encodeURIComponent(env.PLAY_PACKAGE)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;
  const r = await fetch(url, { headers: { authorization: `Bearer ${at}` } });
  if (r.status === 400 || r.status === 404 || r.status === 410) return null;
  if (!r.ok) throw new Error(`Google Play API javobi: ${r.status}`);
  const j = (await r.json()) as { purchaseState?: number; consumptionState?: number };
  // purchaseState: 0 = sotib olingan, 1 = bekor qilingan, 2 = kutilmoqda; consumptionState: 1 = iste'mol qilingan
  return { purchased: j.purchaseState === 0, pending: j.purchaseState === 2, consumed: j.consumptionState === 1 };
}

/** Xaridni iste'mol qiladi (shu bilan tasdiqlanadi ham) — foydalanuvchi keyin yana sotib ola oladi. */
export async function consumePurchase(env: Env, productId: string, purchaseToken: string): Promise<boolean> {
  if (isTestToken(env, purchaseToken)) return true;
  const at = await accessToken(env);
  const url = `${API}/${encodeURIComponent(env.PLAY_PACKAGE)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:consume`;
  const r = await fetch(url, { method: "POST", headers: { authorization: `Bearer ${at}`, "content-length": "0" } });
  return r.ok || r.status === 400; // 400 = allaqachon iste'mol qilingan
}
