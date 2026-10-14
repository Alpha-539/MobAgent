import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";
import type { Env } from "./types";

const FIREBASE_JWKS = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);

export interface FirebaseUser {
  uid: string;
  emailVerified: boolean;
  email: string;
}

/** Firebase ID tokenni tekshiradi (imzo, iss, aud, muddat). Yaroqsiz bo'lsa null. */
export async function verifyFirebase(req: Request, env: Env): Promise<FirebaseUser | null> {
  const h = req.headers.get("Authorization") ?? "";
  if (!h.startsWith("Bearer ")) return null;
  const token = h.slice(7).trim();
  if (!token) return null;
  try {
    const { payload } = await jwtVerify(token, FIREBASE_JWKS, {
      issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`,
      audience: env.FIREBASE_PROJECT_ID,
      algorithms: ["RS256"],
    });
    const uid = typeof payload.sub === "string" ? payload.sub : "";
    if (!uid || uid.length > 128) return null;
    return { uid, emailVerified: payload.email_verified === true, email: typeof payload.email === "string" ? payload.email : "" };
  } catch {
    return null;
  }
}

const accessKeys = new Map<string, JWTVerifyGetKey>();

function timingSafeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const x = enc.encode(a);
  const y = enc.encode(b);
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

/**
 * Admin huquqi:
 *  - Cloudflare Access sozlangan bo'lsa: FAQAT Access JWT (Cf-Access-Jwt-Assertion) qabul qilinadi.
 *  - Aks holda: ADMIN_TOKEN (Authorization: Bearer ...) bo'yicha.
 *  - Ikkalasi ham yo'q bo'lsa admin yopiq.
 */
export async function isAdmin(req: Request, env: Env): Promise<boolean> {
  if (env.ACCESS_TEAM_DOMAIN && env.ACCESS_AUD) {
    const jwt = req.headers.get("Cf-Access-Jwt-Assertion");
    if (!jwt) return false;
    try {
      let keys = accessKeys.get(env.ACCESS_TEAM_DOMAIN);
      if (!keys) {
        keys = createRemoteJWKSet(new URL(`https://${env.ACCESS_TEAM_DOMAIN}/cdn-cgi/access/certs`));
        accessKeys.set(env.ACCESS_TEAM_DOMAIN, keys);
      }
      await jwtVerify(jwt, keys, {
        issuer: `https://${env.ACCESS_TEAM_DOMAIN}`,
        audience: env.ACCESS_AUD,
      });
      return true;
    } catch {
      return false;
    }
  }
  if (env.ADMIN_TOKEN && env.ADMIN_TOKEN.length >= 24) {
    const h = req.headers.get("Authorization") ?? "";
    if (h.startsWith("Bearer ")) return timingSafeEqual(h.slice(7).trim(), env.ADMIN_TOKEN);
  }
  return false;
}
