import { SEED_MODELS } from "./seed-data";
import type { Env } from "./types";

// Jadvallar Worker birinchi so'rovida o'zi yaratiladi (IF NOT EXISTS — xavfsiz, qayta ishlatsa ham zarar yo'q).
// Sxemani keyin o'zgartirsangiz, yangi jadval/indeksni shu yerga qo'shing; mavjud jadvalga ustun qo'shish uchun ALTER kerak bo'ladi.
const SCHEMA: string[] = [
  // Xat yuborish cheklovi (spamga qarshi). key — uid yoki email/IP ning xeshi, shaxsiy ma'lumot emas; 2 kundan keyin tozalanadi.
  `CREATE TABLE IF NOT EXISTS mail_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT NOT NULL, kind TEXT NOT NULL, created_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_mail_log_k ON mail_log(k, created_at)`,
  `CREATE TABLE IF NOT EXISTS models (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, download_url TEXT NOT NULL, direction TEXT NOT NULL,
    category TEXT NOT NULL, base_size_mb INTEGER NOT NULL, base_ram_mb INTEGER NOT NULL,
    min_phone_class TEXT NOT NULL, family TEXT NOT NULL DEFAULT 'GENERIC', params_billion REAL NOT NULL,
    max_context INTEGER NOT NULL DEFAULT 16384, role TEXT, sha256 TEXT, size_bytes INTEGER,
    enabled INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL DEFAULT 0, star_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS stars (
    uid TEXT NOT NULL, model_id TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY (uid, model_id))`,
  `CREATE INDEX IF NOT EXISTS idx_stars_model ON stars(model_id)`,
  `CREATE TABLE IF NOT EXISTS stats_daily (
    day TEXT NOT NULL, metric TEXT NOT NULL, model_id TEXT NOT NULL DEFAULT '', count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, metric, model_id))`,
  `CREATE TABLE IF NOT EXISTS dau (day TEXT NOT NULL, h TEXT NOT NULL, PRIMARY KEY (day, h))`,
  `CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS subs (
    purchase_token TEXT PRIMARY KEY, uid TEXT NOT NULL, product_id TEXT NOT NULL,
    valid_until INTEGER NOT NULL DEFAULT 0, acked INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_subs_uid ON subs(uid)`,
  `CREATE TABLE IF NOT EXISTS credit_purchases (
    purchase_token TEXT PRIMARY KEY, uid TEXT NOT NULL, product_id TEXT NOT NULL,
    micro INTEGER NOT NULL, consumed INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_credit_uid ON credit_purchases(uid)`,
  `CREATE TABLE IF NOT EXISTS spend_daily (
    day TEXT NOT NULL, provider TEXT NOT NULL, micro INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, provider))`,
  `CREATE TABLE IF NOT EXISTS delete_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL, note TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL, done_at INTEGER NOT NULL DEFAULT 0)`,
  // O'zgarishlar tarixi (admin): kim, qachon, nima; modellar uchun oldingi/keyingi holat (bekor qilish uchun)
  `CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL,
    entity TEXT NOT NULL, entity_id TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '',
    before_json TEXT, after_json TEXT, revertable INTEGER NOT NULL DEFAULT 0, reverted_at INTEGER NOT NULL DEFAULT 0)`,
  `CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_id, id)`,
  // Yangilik / e'lon (ilovaning bosh sahifasida banner)
  `CREATE TABLE IF NOT EXISTS announcements (
    id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
    level TEXT NOT NULL DEFAULT 'info', lang TEXT NOT NULL DEFAULT 'all', link_url TEXT NOT NULL DEFAULT '',
    enabled INTEGER NOT NULL DEFAULT 1, ends_at INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
  // Foydalanuvchi fikrlari (ilovadan yuboriladi, adminda ko'riladi)
  `CREATE TABLE IF NOT EXISTS feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'other',
    message TEXT NOT NULL, contact TEXT NOT NULL DEFAULT '', app_version TEXT NOT NULL DEFAULT '',
    lang TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, read_at INTEGER NOT NULL DEFAULT 0)`,
  `CREATE INDEX IF NOT EXISTS idx_feedback_uid ON feedback(uid, created_at)`,
  `CREATE INDEX IF NOT EXISTS idx_feedback_created ON feedback(created_at)`,
];

// models jadvaliga keyin qo'shilgan ustunlar: [nomi, turi]
const MODEL_COLUMNS: [string, string][] = [
  ["system_prompt", "TEXT"], // Tezkor (Chat) modelga vazifa beruvchi system prompt; bo'sh = ilovadagi standart
  ["note", "TEXT"], // admin uchun ichki izoh (ilovaga yuborilmaydi)
  ["agent_prompt", "TEXT"], // Agent rejimidagi modelga admindan qo'shimcha ko'rsatma (standart agent promptiga QO'SHILADI); bo'sh = qo'shilmaydi
  ["badge", "TEXT"], // «Tavsiya etilgan» (RECOMMENDED) / «Yangi» (NEW) belgisi; bo'sh = belgisiz
];

let ready: Promise<void> | null = null;

async function init(env: Env): Promise<void> {
  await env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s)));

  // Mavjud bazaga yangi ustunlar (bor bo'lsa tegmaydi).
  const { results: cols } = await env.DB.prepare("SELECT name FROM pragma_table_info('models')").all<{ name: string }>();
  const have = new Set(cols.map((c) => c.name));
  for (const [col, ddl] of MODEL_COLUMNS) {
    if (!have.has(col)) await env.DB.prepare(`ALTER TABLE models ADD COLUMN ${col} ${ddl}`).run();
  }

  // Boshlang'ich modellar faqat BIR MARTA yoziladi. Keyin admin hammasini o'chirsa ham qayta tiklanmaydi.
  const seeded = await env.DB.prepare("SELECT 1 AS x FROM meta WHERE k = 'seeded'").first();
  if (seeded) return;
  const now = Date.now();
  const ins = env.DB.prepare(
    `INSERT OR IGNORE INTO models (id,name,download_url,direction,category,base_size_mb,base_ram_mb,min_phone_class,
       family,params_billion,max_context,role,sha256,size_bytes,enabled,sort_order,star_count,created_at,updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,1,?,0,?,?)`,
  );
  await env.DB.batch([
    ...SEED_MODELS.map((m) =>
      ins.bind(m.id, m.name, m.downloadUrl, m.direction, m.category, m.baseSizeMb, m.baseRamMb, m.minPhoneClass,
        m.family, m.paramsBillion, m.maxContext, m.role, m.sortOrder, now, now)),
    env.DB.prepare("INSERT OR IGNORE INTO meta (k, v) VALUES ('seeded', '1')"),
  ]);
}

/** Har Worker nusxasida bir marta ishlaydi; xato bo'lsa keyingi so'rovda qayta urinadi. */
export function ensureDb(env: Env): Promise<void> {
  if (!ready) ready = init(env).catch((e) => { ready = null; throw e; });
  return ready;
}
