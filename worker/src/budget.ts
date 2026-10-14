import { DurableObject } from "cloudflare:workers";
import { CREDIT_RESERVE_MAX, CREDIT_UNIT_MICRO, MAX_IN_FLIGHT, MICRO, MONTH_MS, PLANS, WEEK_MS, WEEK_SHARE, WINDOW_MS, WINDOW_SHARE, type PlanId } from "./plans";
import type { Env } from "./types";

interface State {
  planId: PlanId | null;
  /** Tarif shu vaqtgacha (ms) amal qiladi */
  validUntil: number;
  monthStart: number;
  monthSpent: number;
  weekStart: number;
  weekSpent: number;
  winStart: number;
  winSpent: number;
  /** Bajarilayotgan so'rovlar: id -> band qilingan miqdor */
  reserved: Record<string, { amt: number; at: number; src?: "plan" | "credits" }>;
  /** Sotib olingan credits qoldig'i (micro-USD). Tarif limitlari tugagach (yoki tarif bo'lmasa) shundan yechiladi. */
  credits: number;
  /** Oxirgi berilgan credits xaridlari (takroriy berishdan himoya). */
  grants: string[];
}

export type Scope = "window" | "week" | "month";

export type ReserveResult =
  | { ok: true }
  | { ok: false; reason: "no_plan" | "busy" | Scope; retryAtMs?: number };

export interface BudgetStatus {
  planId: PlanId | null;
  validUntil: number;
  /** Qolgan credits, "kredit" birligida (1 kredit = $0.01 API xarajati). */
  credits: number;
  /** 0..1 — ilovada foiz ko'rsatish uchun (dollar miqdori foydalanuvchiga ko'rsatilmaydi) */
  windowUsed: number;
  weekUsed: number;
  monthUsed: number;
  windowResetsAt: number;
  weekResetsAt: number;
  monthResetsAt: number;
}

const EMPTY: State = {
  planId: null, validUntil: 0, monthStart: 0, monthSpent: 0, weekStart: 0, weekSpent: 0,
  winStart: 0, winSpent: 0, reserved: {}, credits: 0, grants: [],
};
const STALE_RESERVATION_MS = 10 * 60_000;

/** Har foydalanuvchiga bitta nusxa (idFromName(uid)). Byudjet hisobi shu yerda aniq (ketma-ket) bajariladi. */
export class UserBudget extends DurableObject<Env> {
  private async load(): Promise<State> {
    const s = (await this.ctx.storage.get<State>("st")) ?? { ...EMPTY, reserved: {}, grants: [] };
    s.credits ??= 0; // eski yozuvlarda bu maydon yo'q
    s.grants ??= [];
    return s;
  }
  private save(s: State): Promise<void> {
    return this.ctx.storage.put("st", s);
  }

  /** Muddati o'tgan oyna/hafta/oy hisoblagichlarini nolga tushiradi. */
  private roll(s: State, now: number): void {
    if (s.monthStart === 0) s.monthStart = now;
    while (now >= s.monthStart + MONTH_MS) {
      s.monthStart += MONTH_MS;
      s.monthSpent = 0;
    }
    if (s.weekStart !== 0 && now >= s.weekStart + WEEK_MS) { s.weekStart = 0; s.weekSpent = 0; }
    if (s.winStart !== 0 && now >= s.winStart + WINDOW_MS) { s.winStart = 0; s.winSpent = 0; }
    for (const [id, r] of Object.entries(s.reserved)) {
      if (now - r.at > STALE_RESERVATION_MS) delete s.reserved[id];
    }
  }

  private limits(planId: PlanId) {
    const month = PLANS[planId].monthlyUsd * MICRO;
    return { month, week: month * WEEK_SHARE, window: month * WINDOW_SHARE };
  }

  /** Obuna holati o'zgarganda (xarid, uzaytirish, bekor qilish, qaytarish) chaqiriladi. */
  async setPlan(planId: PlanId | null, validUntil: number): Promise<void> {
    const s = await this.load();
    const now = Date.now();
    this.roll(s, now);
    s.planId = planId;
    s.validUntil = planId ? validUntil : 0;
    await this.save(s);
  }

  async status(): Promise<BudgetStatus> {
    const s = await this.load();
    const now = Date.now();
    this.roll(s, now);
    await this.save(s);
    const active = s.planId !== null && now < s.validUntil;
    const l = s.planId ? this.limits(s.planId) : { month: 1, week: 1, window: 1 };
    return {
      planId: active ? s.planId : null,
      validUntil: s.validUntil,
      credits: Math.floor(Math.max(0, s.credits) / CREDIT_UNIT_MICRO),
      windowUsed: Math.min(1, s.winSpent / l.window),
      weekUsed: Math.min(1, s.weekSpent / l.week),
      monthUsed: Math.min(1, s.monthSpent / l.month),
      windowResetsAt: s.winStart ? s.winStart + WINDOW_MS : 0,
      weekResetsAt: s.weekStart ? s.weekStart + WEEK_MS : 0,
      monthResetsAt: s.monthStart + MONTH_MS,
    };
  }

  /**
   * So'rov boshlanishidan oldin chaqiriladi. `estMicro` — eng yomon holat bahosi; band qilinadigan miqdor
   * oyna limitining 20% idan oshmaydi (aks holda limit oxirida foydalanuvchi qolgan byudjetini ishlata olmasdi).
   * Haqiqiy narx `settle` da ayriladi.
   */
  async reserve(reqId: string, estMicro: number): Promise<ReserveResult> {
    const s = await this.load();
    const now = Date.now();
    this.roll(s, now);
    const planActive = s.planId !== null && now < s.validUntil;
    if (!planActive && s.credits <= 0) {
      await this.save(s);
      return { ok: false, reason: "no_plan" };
    }
    if (Object.keys(s.reserved).length >= MAX_IN_FLIGHT) {
      await this.save(s);
      return { ok: false, reason: "busy" };
    }
    const heldPlan = Object.values(s.reserved).filter((r) => r.src !== "credits").reduce((a, r) => a + r.amt, 0);
    const heldCred = Object.values(s.reserved).filter((r) => r.src === "credits").reduce((a, r) => a + r.amt, 0);

    // 1) Avval tarif limitlaridan
    let fail: ReserveResult | null = null;
    if (planActive) {
      const l = this.limits(s.planId as PlanId);
      const amt = Math.min(estMicro, l.window * 0.2);
      if (s.monthSpent + heldPlan + amt > l.month) fail = { ok: false, reason: "month", retryAtMs: s.monthStart + MONTH_MS };
      else if (s.weekSpent + heldPlan + amt > l.week) fail = { ok: false, reason: "week", retryAtMs: s.weekStart + WEEK_MS };
      else if (s.winSpent + heldPlan + amt > l.window) fail = { ok: false, reason: "window", retryAtMs: s.winStart + WINDOW_MS };
      if (!fail) {
        s.reserved[reqId] = { amt, at: now, src: "plan" };
        await this.save(s);
        return { ok: true };
      }
    }

    // 2) Tarif limiti tugagan (yoki tarif yo'q) — credits'dan
    const avail = s.credits - heldCred;
    if (avail > 0) {
      s.reserved[reqId] = { amt: Math.min(estMicro, CREDIT_RESERVE_MAX, avail), at: now, src: "credits" };
      await this.save(s);
      return { ok: true };
    }
    await this.save(s);
    return fail ?? { ok: false, reason: "no_plan" };
  }

  /** Credits xaridini hisobga qo'shadi. `ref` (xarid tokeni) bo'yicha takroran chaqirilsa ham bir marta qo'shiladi. */
  async grant(ref: string, micro: number): Promise<boolean> {
    const s = await this.load();
    if (s.grants.includes(ref)) return false;
    s.credits += Math.max(0, Math.round(micro));
    s.grants.push(ref);
    if (s.grants.length > 200) s.grants.splice(0, s.grants.length - 200);
    await this.save(s);
    return true;
  }

  /** So'rov tugagach: band qilinganni bo'shatib, haqiqiy narxni oyna/hafta/oyga qo'shadi. */
  async settle(reqId: string, actualMicro: number): Promise<void> {
    const s = await this.load();
    const now = Date.now();
    this.roll(s, now);
    const held = s.reserved[reqId];
    delete s.reserved[reqId];
    const cost = Math.max(0, Math.round(actualMicro));
    if (cost > 0 && held?.src === "credits") {
      s.credits = Math.max(0, s.credits - cost);
    } else if (cost > 0) {
      if (s.winStart === 0) s.winStart = now;
      if (s.weekStart === 0) s.weekStart = now;
      s.winSpent += cost;
      s.weekSpent += cost;
      s.monthSpent += cost;
    }
    await this.save(s);
  }

  /** Admin: foydalanuvchining oyna/hafta/oy sarfini nolga tushiradi (tarif o'zgarmaydi). */
  async resetUsage(): Promise<void> {
    const s = await this.load();
    s.winStart = 0; s.winSpent = 0;
    s.weekStart = 0; s.weekSpent = 0;
    s.monthStart = Date.now(); s.monthSpent = 0;
    s.reserved = {};
    await this.save(s);
  }

  /** Hisob o'chirilganda. */
  async wipe(): Promise<void> {
    await this.ctx.storage.deleteAll();
  }
}
