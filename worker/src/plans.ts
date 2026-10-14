// Tariflar va pullik modellar jadvali. NARXLARNI provayder konsolida tekshirib, shu yerda yangilang
// (o'zgartirgach GitHub'ga push qilsangiz Worker avtomatik qayta deploy bo'ladi).
//
// Birlik: micro-USD (1 USD = 1_000_000). Token narxi $/1M token bo'lgani uchun:
//   xarajat (micro-USD) = kirish_tokenlari * inPerM + chiqish_tokenlari * outPerM

export type PlanId = "basic" | "standard" | "pro";

export interface Plan {
  id: PlanId;
  rank: number;
  /** Oylik API byudjeti, USD (foydalanuvchi to'lagan narxning ~50-55% i) */
  monthlyUsd: number;
}

export const PLANS: Record<PlanId, Plan> = {
  basic: { id: "basic", rank: 1, monthlyUsd: 2.3 }, //  $5 tarif
  standard: { id: "standard", rank: 2, monthlyUsd: 7 }, // $15 tarif
  pro: { id: "pro", rank: 3, monthlyUsd: 14 }, // $30 tarif
};

/** Play Console'dagi obuna mahsulot ID'lari -> tarif. Oylik va yillik base plan bitta mahsulot ichida bo'ladi. */
export const PRODUCT_TO_PLAN: Record<string, PlanId> = {
  mobagent_basic: "basic",
  mobagent_standard: "standard",
  mobagent_pro: "pro",
};

/**
 * Alohida sotib olinadigan CREDITS (Play Console'da bir martalik, iste'mol qilinadigan mahsulotlar).
 * budgetUsd — foydalanuvchiga beriladigan API byudjeti (to'langan narxning ~50% i; Play narxi bilan bir xil saqlang).
 */
export const CREDIT_PRODUCTS: Record<string, { budgetUsd: number }> = {
  mobagent_credits_5: { budgetUsd: 2.3 }, //  $5 paket
  mobagent_credits_15: { budgetUsd: 7 }, //  $15 paket
};
/** Ilovada ko'rsatiladigan "kredit" birligi: 1 kredit = 10_000 micro-USD ($0.01 API xarajati). */
export const CREDIT_UNIT_MICRO = 10_000;
/** Tarifi yo'q, faqat credits bor foydalanuvchi ochadigan eng yuqori model darajasi (1 = o'rtacha modellar). */
export const CREDIT_ONLY_RANK = 1;
/** Bitta so'rov uchun kreditdan band qilinadigan eng ko'p miqdor (micro-USD). */
export const CREDIT_RESERVE_MAX = 500_000;

export const MICRO = 1_000_000;
export const WINDOW_MS = 5 * 3600_000; //  5 soatlik oyna
export const WEEK_MS = 7 * 86400_000; //   haftalik cap
export const MONTH_MS = 30 * 86400_000; // byudjet davri (yillik tarifda ham oyma-oy)
export const WINDOW_SHARE = 1 / 6; //      oyna = oylik byudjetning 1/6 qismi
export const WEEK_SHARE = 1 / 4; //        hafta = oylik byudjet / 4
export const MAX_IN_FLIGHT = 3; //         bir foydalanuvchining bir vaqtdagi so'rovlari

export type Provider = "anthropic" | "openai" | "gemini" | "moonshot" | "deepseek";

export interface CloudModel {
  /** Ilovaga ko'rinadigan id */
  id: string;
  name: string;
  provider: Provider;
  /** Provayderdagi haqiqiy model nomi */
  upstream: string;
  tier: "medium" | "strong";
  /** Shu tarif (rank) va undan yuqorilar ishlata oladi */
  minRank: number;
  inPerM: number;
  outPerM: number;
  maxOut: number;
  /** Fikrlash tokenlari javob limitidan ketadigan modellar uchun eng kam max_tokens (aks holda javob bo'sh qolishi mumkin) */
  minOut?: number;
  vision: boolean;
  /** false bo'lsa model vaqtincha o'chirilgan (ro'yxatda ko'rinmaydi, so'rov rad etiladi). Standart: yoqilgan. */
  enabled?: boolean;
}

// MANBALAR (2026-10-05 holati): Claude — platform.claude.com model-deprecations va pricing sahifalari (hammasi "Active");
// OpenAI — developers.openai.com model sahifalari va changelog; Gemini — ai.google.dev narxlari; Kimi — Moonshot API (kimi-k3).
// NARX QOIDASI: noaniq yoki aksiyali narx bo'lsa, QIMMATROG'I yozilgan (xarajatni kam baholab zarar ko'rmaslik uchun):
//   GPT-5.6 Sol/Terra/Luna — OpenAI hozir aksiyada ($4/$20 gacha 21-noyabr), bu yerda oddiy narxlar;
//   Gemini 3.8 Flash — 2026-yil oxirigacha $0.75/$3.75, 2027-yildan $1.5/$7.5.
// Hammasini baribir provayder konsolida tekshiring. Kaliti (secret) yo'q provayderning modellari ro'yxatda ko'rinmaydi.
// ESLATMA: Claude Haiku 4.5 uchun "eng erta 2026-10-15" muddati bor; Anthropic o'chirishni e'lon qilsa, bu yerdan olib tashlang.
// Chiqarilmaganlari: Claude Sonnet 4.5 (30-noyabrda o'chadi), Opus 4.8 "fast mode" (alohida tajriba narxi), GPT-5.3-Codex (Chat Completions orqali tasdiqlanmagan).
export const CLOUD_MODELS: CloudModel[] = [
  // ESLATMA: provider "gemini" (GEMINI_API_KEY) modellari hozircha `enabled: false` bilan o'chirilgan. Qaytarish uchun shu qatorni olib tashlang yoki true qiling.
  // ---- O'rtacha (barcha pullik tariflar) ----
  { id: "claude-haiku-4-5", name: "Claude Haiku 4.5", provider: "anthropic", upstream: "claude-haiku-4-5-20251001",
    tier: "medium", minRank: 1, inPerM: 1, outPerM: 5, maxOut: 8192, vision: true },
  { id: "gpt-6-luna", name: "GPT-6 Luna", provider: "openai", upstream: "gpt-6-luna",
    tier: "medium", minRank: 1, inPerM: 0.1, outPerM: 0.5, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gpt-5-4-mini", name: "GPT-5.4 mini", provider: "openai", upstream: "gpt-5.4-mini",
    tier: "medium", minRank: 1, inPerM: 0.75, outPerM: 4.5, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gpt-5-6-luna", name: "GPT-5.6 Luna", provider: "openai", upstream: "gpt-5.6-luna",
    tier: "medium", minRank: 1, inPerM: 1, outPerM: 6, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gemini-3-8-flash", name: "Gemini 3.8 Flash", provider: "gemini", upstream: "gemini-3.8-flash",
    tier: "medium", minRank: 1, inPerM: 1.5, outPerM: 7.5, maxOut: 8192, minOut: 2048, vision: true, enabled: false },

  // DeepSeek: narxlar api-docs.deepseek.com/quick_start/pricing dan tekshirilgan (2026-10-06), "peak" narxlar; off-peak (01-04 va 06-10 UTC, du-ju dan tashqari barcha vaqt) yarmi.
  { id: "deepseek-flash", name: "DeepSeek Flash", provider: "deepseek", upstream: "deepseek-flash",
    tier: "medium", minRank: 1, inPerM: 0.3, outPerM: 1.2, maxOut: 8192, minOut: 2048, vision: true },

  // ---- Kuchli (o'rta va yuqori tariflar) ----
  { id: "claude-sonnet-5-5", name: "Claude Sonnet 5.5", provider: "anthropic", upstream: "claude-sonnet-5-5",
    tier: "strong", minRank: 2, inPerM: 2, outPerM: 10, maxOut: 8192, minOut: 2048, vision: true },
  { id: "claude-sonnet-5", name: "Claude Sonnet 5", provider: "anthropic", upstream: "claude-sonnet-5",
    tier: "strong", minRank: 2, inPerM: 2, outPerM: 10, maxOut: 8192, minOut: 2048, vision: true },
  { id: "claude-sonnet-4-6", name: "Claude Sonnet 4.6", provider: "anthropic", upstream: "claude-sonnet-4-6",
    tier: "strong", minRank: 2, inPerM: 3, outPerM: 15, maxOut: 8192, vision: true },
  { id: "gpt-6-sol", name: "GPT-6 Sol", provider: "openai", upstream: "gpt-6-sol",
    tier: "strong", minRank: 2, inPerM: 2, outPerM: 10, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gpt-6-1-sol", name: "GPT-6.1 Sol", provider: "openai", upstream: "gpt-6.1-sol",
    tier: "strong", minRank: 2, inPerM: 2, outPerM: 10, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gpt-5-6-terra", name: "GPT-5.6 Terra", provider: "openai", upstream: "gpt-5.6-terra",
    tier: "strong", minRank: 2, inPerM: 2.5, outPerM: 15, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gpt-5-4", name: "GPT-5.4", provider: "openai", upstream: "gpt-5.4",
    tier: "strong", minRank: 2, inPerM: 2.5, outPerM: 15, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gemini-3-1-pro", name: "Gemini 3.1 Pro", provider: "gemini", upstream: "gemini-3.1-pro-preview",
    tier: "strong", minRank: 2, inPerM: 2, outPerM: 12, maxOut: 8192, minOut: 2048, vision: true, enabled: false },
  { id: "deepseek-v4-pro", name: "DeepSeek V4 Pro", provider: "deepseek", upstream: "deepseek-v4-pro",
    tier: "strong", minRank: 2, inPerM: 1.32, outPerM: 3.96, maxOut: 8192, minOut: 2048, vision: false },
  { id: "kimi-k3", name: "Kimi K3", provider: "moonshot", upstream: "kimi-k3",
    tier: "strong", minRank: 2, inPerM: 3, outPerM: 15, maxOut: 8192, minOut: 4096, vision: true },

  // ---- Eng kuchli (faqat yuqori tarif) ----
  { id: "claude-opus-5-5", name: "Claude Opus 5.5", provider: "anthropic", upstream: "claude-opus-5-5",
    tier: "strong", minRank: 3, inPerM: 4, outPerM: 20, maxOut: 8192, minOut: 4096, vision: true },
  { id: "claude-opus-5", name: "Claude Opus 5", provider: "anthropic", upstream: "claude-opus-5",
    tier: "strong", minRank: 3, inPerM: 5, outPerM: 25, maxOut: 8192, minOut: 4096, vision: true },
  { id: "claude-opus-4-8", name: "Claude Opus 4.8", provider: "anthropic", upstream: "claude-opus-4-8",
    tier: "strong", minRank: 3, inPerM: 5, outPerM: 25, maxOut: 8192, minOut: 2048, vision: true },
  { id: "claude-opus-4-7", name: "Claude Opus 4.7", provider: "anthropic", upstream: "claude-opus-4-7",
    tier: "strong", minRank: 3, inPerM: 5, outPerM: 25, maxOut: 8192, minOut: 2048, vision: true },
  { id: "claude-opus-4-6", name: "Claude Opus 4.6", provider: "anthropic", upstream: "claude-opus-4-6",
    tier: "strong", minRank: 3, inPerM: 5, outPerM: 25, maxOut: 8192, vision: true },
  { id: "claude-fable-5-1", name: "Claude Fable 5.1", provider: "anthropic", upstream: "claude-fable-5-1",
    tier: "strong", minRank: 3, inPerM: 10, outPerM: 50, maxOut: 8192, minOut: 4096, vision: true },
  { id: "claude-fable-5", name: "Claude Fable 5", provider: "anthropic", upstream: "claude-fable-5",
    tier: "strong", minRank: 3, inPerM: 10, outPerM: 50, maxOut: 8192, minOut: 4096, vision: true },
  { id: "gpt-6-astra", name: "GPT-6 Astra", provider: "openai", upstream: "gpt-6-astra",
    tier: "strong", minRank: 3, inPerM: 10, outPerM: 50, maxOut: 8192, minOut: 4096, vision: true },
  { id: "gpt-5-6-sol", name: "GPT-5.6 Sol", provider: "openai", upstream: "gpt-5.6-sol",
    tier: "strong", minRank: 3, inPerM: 5, outPerM: 30, maxOut: 8192, minOut: 2048, vision: true },
  { id: "gpt-5-5", name: "GPT-5.5", provider: "openai", upstream: "gpt-5.5",
    tier: "strong", minRank: 3, inPerM: 5, outPerM: 30, maxOut: 8192, minOut: 2048, vision: true },
];

export const byModelId = (id: string): CloudModel | undefined => CLOUD_MODELS.find((m) => m.id === id);
