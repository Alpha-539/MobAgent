import { BADGES, CATEGORIES, FAMILIES, ROLES } from "./types";

export interface ModelInput {
  id: string;
  name: string;
  downloadUrl: string;
  direction: string;
  category: string;
  baseSizeMb: number;
  baseRamMb: number;
  minPhoneClass: string;
  family: string;
  paramsBillion: number;
  maxContext: number;
  role: string | null;
  sha256: string | null;
  sizeBytes: number | null;
  enabled: boolean;
  sortOrder: number;
  systemPrompt: string | null;
  agentPrompt: string | null;
  note: string | null;
  badge: string | null;
}

const ID_RE = /^[a-z0-9][a-z0-9._-]{1,63}$/;
const SHA_RE = /^[0-9a-f]{64}$/;

function str(v: unknown, max: number): string | null {
  return typeof v === "string" && v.trim().length > 0 && v.length <= max ? v.trim() : null;
}
function int(v: unknown, min: number, max: number): number | null {
  return typeof v === "number" && Number.isInteger(v) && v >= min && v <= max ? v : null;
}

/** Natija: { ok: true, value } yoki { ok: false, error } */
export function validateModel(
  raw: unknown,
  forcedId?: string,
): { ok: true; value: ModelInput } | { ok: false; error: string } {
  if (typeof raw !== "object" || raw === null) return { ok: false, error: "JSON obyekt kerak" };
  const r = raw as Record<string, unknown>;

  const id = forcedId ?? (typeof r.id === "string" ? r.id : "");
  if (!ID_RE.test(id)) return { ok: false, error: "id: a-z, 0-9, '.', '_', '-' (2-64 belgi)" };

  const name = str(r.name, 80);
  if (!name) return { ok: false, error: "name kerak" };

  const downloadUrl = str(r.downloadUrl, 500);
  if (!downloadUrl) return { ok: false, error: "downloadUrl kerak" };
  try {
    if (new URL(downloadUrl).protocol !== "https:") return { ok: false, error: "downloadUrl https bo'lishi shart" };
  } catch {
    return { ok: false, error: "downloadUrl noto'g'ri" };
  }

  const direction = str(r.direction, 300);
  if (!direction) return { ok: false, error: "direction kerak" };
  const minPhoneClass = str(r.minPhoneClass, 120);
  if (!minPhoneClass) return { ok: false, error: "minPhoneClass kerak" };

  if (typeof r.category !== "string" || !(CATEGORIES as readonly string[]).includes(r.category))
    return { ok: false, error: `category: ${CATEGORIES.join(" | ")}` };
  const family = r.family == null || r.family === "" ? "GENERIC" : r.family;
  if (typeof family !== "string" || !(FAMILIES as readonly string[]).includes(family))
    return { ok: false, error: `family: ${FAMILIES.join(" | ")}` };
  const role = r.role == null || r.role === "" ? null : r.role;
  if (role !== null && (typeof role !== "string" || !(ROLES as readonly string[]).includes(role)))
    return { ok: false, error: `role: ${ROLES.join(" | ")} yoki bo'sh` };

  const baseSizeMb = int(r.baseSizeMb, 1, 200_000);
  const baseRamMb = int(r.baseRamMb, 1, 200_000);
  if (baseSizeMb === null || baseRamMb === null) return { ok: false, error: "baseSizeMb/baseRamMb: butun son" };
  const paramsBillion = typeof r.paramsBillion === "number" && r.paramsBillion > 0 && r.paramsBillion < 1000 ? r.paramsBillion : null;
  if (paramsBillion === null) return { ok: false, error: "paramsBillion: musbat son" };
  const maxContext = r.maxContext == null ? 16384 : int(r.maxContext, 512, 1_000_000);
  if (maxContext === null) return { ok: false, error: "maxContext: butun son" };

  let sha256: string | null = null;
  if (r.sha256 != null && r.sha256 !== "") {
    sha256 = typeof r.sha256 === "string" ? r.sha256.toLowerCase() : "";
    if (!SHA_RE.test(sha256)) return { ok: false, error: "sha256: 64 ta hex belgi" };
  }
  const sizeBytes = r.sizeBytes == null || r.sizeBytes === "" ? null : int(r.sizeBytes, 1, 2 ** 52);
  if (r.sizeBytes != null && r.sizeBytes !== "" && sizeBytes === null) return { ok: false, error: "sizeBytes: butun son" };

  const sortOrder = r.sortOrder == null ? 0 : int(r.sortOrder, -100000, 100000);
  if (sortOrder === null) return { ok: false, error: "sortOrder: butun son" };

  const optText = (v: unknown, max: number): string | null | undefined => {
    if (v == null || v === "") return null;
    if (typeof v !== "string") return undefined;
    const t = v.replace(/\r\n/g, "\n").trim();
    if (t.length > max) return undefined;
    return t === "" ? null : t;
  };
  const systemPrompt = optText(r.systemPrompt, 2000);
  if (systemPrompt === undefined) return { ok: false, error: "systemPrompt: matn, ko'pi bilan 2000 belgi" };
  const agentPrompt = optText(r.agentPrompt, 1000);
  if (agentPrompt === undefined) return { ok: false, error: "agentPrompt: matn, ko'pi bilan 1000 belgi" };
  const note = optText(r.note, 500);
  if (note === undefined) return { ok: false, error: "note: matn, ko'pi bilan 500 belgi" };

  const badge = r.badge == null || r.badge === "" ? null : r.badge;
  if (badge !== null && (typeof badge !== "string" || !(BADGES as readonly string[]).includes(badge)))
    return { ok: false, error: `badge: ${BADGES.join(" | ")} yoki bo'sh` };

  return {
    ok: true,
    value: {
      id, name, downloadUrl, direction, category: r.category, baseSizeMb, baseRamMb, minPhoneClass,
      family, paramsBillion, maxContext, role: role as string | null, sha256, sizeBytes,
      enabled: r.enabled !== false, sortOrder, systemPrompt, agentPrompt, note,
      badge: badge as string | null,
    },
  };
}
