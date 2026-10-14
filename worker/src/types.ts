export interface Env {
  DB: D1Database;
  FIREBASE_PROJECT_ID: string;
  CONTACT_EMAIL: string;
  APP_NAME: string;
  ACCESS_TEAM_DOMAIN: string;
  ACCESS_AUD: string;
  ADMIN_TOKEN?: string;
  /** Admin panel manzili (ixtiyoriy). Qo'yilmasa — /alpha/maxfiy_sahifa. Masalan: alpha/boshqa_yol (1-4 bo'lak: harf, raqam, _ -). /admin doim 404 beradi. (secret) */
  ADMIN_PATH?: string;
  STATS_SALT?: string;
  // ---- Pullik rejim ----
  USER_BUDGET: DurableObjectNamespace<import("./budget").UserBudget>;
  PLAY_PACKAGE: string;
  /** Google Play service account JSON (secret) */
  PLAY_SERVICE_ACCOUNT?: string;
  /** Pub/Sub push manziliga ?secret=... sifatida qo'yiladi (secret) */
  RTDN_SECRET?: string;
  /** Faqat sinov uchun "1". Production'da QO'YILMASIN. */
  ALLOW_TEST_PURCHASES?: string;
  ANTHROPIC_API_KEY?: string;
  OPENAI_API_KEY?: string;
  GEMINI_API_KEY?: string;
  MOONSHOT_API_KEY?: string;
  DEEPSEEK_API_KEY?: string;
  /** Kunlik umumiy API xarajat chegarasi (USD). Oshsa yangi pullik so'rovlar vaqtincha rad etiladi. Bo'sh = cheklanmagan. */
  DAILY_SPEND_CAP_USD?: string;
  /** Bosh sahifadagi tarif narxlari (erkin matn, masalan "$5 / oy"). Bo'sh = "Narxi Google Play'da". Play Console'dagi narx bilan bir xil yozing. */
  PRICE_BASIC?: string;
  PRICE_STANDARD?: string;
  PRICE_PRO?: string;
  // ---- Tasdiqlash / parol tiklash xatlari (o'z domenimizdan) ----
  /** Cloudflare Email Service binding ([[send_email]] name = "EMAIL"). Yo'q bo'lsa RESEND_API_KEY ishlatiladi. */
  EMAIL?: { send(msg: { to: string; from: string | { email: string; name?: string }; subject: string; html?: string; text?: string; replyTo?: string }): Promise<unknown> };
  /** Jo'natuvchi manzil, masalan noreply@mail.uz-x.info. Bo'sh = o'z xat tizimi o'chiq (ilova Firebase'ning xatiga qaytadi). */
  MAIL_FROM?: string;
  /** Ixtiyoriy: Cloudflare o'rniga Resend (secret). */
  RESEND_API_KEY?: string;
  /** Firebase Admin service account JSON (secret): havola yaratish uchun. */
  FIREBASE_SERVICE_ACCOUNT?: string;
  // ---- Bepul model (uchinchi tomon servis orqali) ----
  /** "1" bo'lsa bepul model o'chiriladi (servis ishlamay qolsa shuni qo'ying). */
  FREE_AI_DISABLED?: string;
  /** Tarifsiz foydalanuvchiga 5 soatda nechta TOKEN (kirish + chiqish; standart 30000). */
  FREE_AI_LIMIT?: string;
  /** Servis manzili (standart https://prexzyapis.com). */
  FREE_AI_BASE?: string;
}

export const CATEGORIES = ["CODING", "CHAT", "TRANSLATION", "REASONING"] as const;
export const FAMILIES = ["QWEN", "DEEPSEEK_R1", "LLAMA3", "GEMMA", "PHI3", "MISTRAL", "GENERIC"] as const;
export const BADGES = ["RECOMMENDED", "NEW"] as const;
export const ROLES = ["QUICK_ANSWERS", "CODE_HELPER", "TEXT_WRITER", "MATH_LOGIC"] as const;
export const METRICS = [
  "app_open",
  "download_start",
  "download_ok",
  "download_fail",
  "chat_offline",
  "chat_online",
] as const;
