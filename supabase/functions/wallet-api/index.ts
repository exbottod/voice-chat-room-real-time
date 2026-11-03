// =====================================================================
//  REZO CHAT: wallet-api (Supabase Edge Function)
//  Satu pintu untuk semua operasi dompet/gift/store.
//  Setiap permintaan wajib membawa token login Firebase; token diverifikasi
//  dengan kunci publik Google sebelum operasi dijalankan.
//  ENV otomatis dari Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.
// =====================================================================

const FIREBASE_PROJECT_ID = "arboreal-cat-511705-p6";
const JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

let jwksCache: { keys: Record<string, unknown>[]; at: number } | null = null;

function b64urlDecode(s: string): Uint8Array {
  const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + pad;
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function b64urlJson(s: string): Record<string, unknown> {
  const bytes = b64urlDecode(s);
  return JSON.parse(new TextDecoder().decode(bytes));
}

async function getKeys(): Promise<Record<string, unknown>[]> {
  if (jwksCache && Date.now() - jwksCache.at < 3600_000) return jwksCache.keys;
  const r = await fetch(JWKS_URL);
  if (!r.ok) throw new Error("Gagal mengambil kunci Google (" + r.status + ")");
  const j = await r.json();
  jwksCache = { keys: j.keys ?? [], at: Date.now() };
  return jwksCache.keys;
}

/** Verifikasi token Firebase (RS256) dan kembalikan uid. */
async function verifyFirebase(token: string): Promise<string> {
  return (await verifyFirebaseFull(token)).uid;
}

const ADMIN_EMAIL = "developerex100@gmail.com";

/** Sama seperti verifyFirebase, tapi juga mengembalikan email token. */
async function verifyFirebaseFull(token: string): Promise<{ uid: string; email: string }> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("Token tidak valid");
  let header: Record<string, unknown>;
  try {
    header = b64urlJson(parts[0]);
  } catch {
    throw new Error("Token tidak valid");
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw new Error("Token tidak valid");

  const keys = await getKeys();
  const jwk = keys.find((k) => (k as { kid?: string }).kid === header.kid) as
    | { n: string; e: string }
    | undefined;
  if (!jwk) throw new Error("Token tidak valid");

  const key = await crypto.subtle.importKey(
    "jwk",
    { kty: "RSA", alg: "RS256", use: "sig", n: jwk.n, e: jwk.e },
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    b64urlDecode(parts[2]) as unknown as ArrayBuffer,
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!ok) throw new Error("Token tidak valid");

  let p: Record<string, unknown>;
  try {
    p = b64urlJson(parts[1]);
  } catch {
    throw new Error("Token tidak valid");
  }
  const now = Math.floor(Date.now() / 1000);
  if (p.aud !== FIREBASE_PROJECT_ID) throw new Error("Token tidak valid");
  if (p.iss !== `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`) throw new Error("Token tidak valid");
  if (typeof p.exp !== "number" || p.exp <= now) throw new Error("Sesi habis, buka ulang aplikasinya");
  if (typeof p.sub !== "string" || !p.sub) throw new Error("Token tidak valid");
  return { uid: p.sub, email: typeof p.email === "string" ? p.email.toLowerCase() : "" };
}

const ACTIONS: Record<string, string> = {
  get: "w_get",
  migrate: "w_migrate",
  topup: "w_topup",
  send_gift: "w_send_gift",
  exchange: "w_exchange",
  buy_vip: "w_buy_vip",
  buy_item: "w_buy_item",
  equip: "w_equip",
  claim_level: "w_claim_level",
  backfill: "w_backfill_vip",
  wave_prepare: "w_wave_prepare",
};

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, accept, x-requested-with",
  "Access-Control-Max-Age": "86400",
};

async function rpc(fn: string, params: Record<string, unknown>): Promise<Response> {
  const url = `${Deno.env.get("SUPABASE_URL")}/rest/v1/rpc/${fn}`;
  const sr = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const r = await fetch(url, {
    method: "POST",
    headers: {
      apikey: sr,
      Authorization: `Bearer ${sr}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(params),
  });
  return r;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}


// ================= GAME WAVEGAMES (Teen Patti, Dragon Vs Tiger, dll) =================
// WaveGames memanggil dua endpoint ini:
//   GET  /wave/game/userInfo   -> profil + saldo coin pemain
//   POST /wave/game/submitFlow -> potong coin saat bet (type 1), tambah saat menang (type 2)
// Token yang dikirim WaveGames = token login Firebase milik pemain (parameter
// "token" di URL game). Semua perubahan coin tetap di tabel wallets.

async function wavePlayer(uid: string) {
  const r = await rpc("w_wave_player", { p_uid: uid });
  if (!r.ok) return null;
  const rows = await r.json().catch(() => null);
  const row = Array.isArray(rows) ? rows[0] : rows;
  if (!row) return null;
  return {
    id: uid,
    name: String(row.name ?? "Player"),
    balance: Number(row.balance ?? 0),
    profilePicture: String(row.avatar ?? ""),
  };
}

// Token pemain bisa datang dari header Authorization, query ?token=, atau body.
function bearer(req: Request, bodyToken?: unknown): string {
  const a = req.headers.get("authorization") || req.headers.get("Authorization") || "";
  if (a.toLowerCase().startsWith("bearer ")) return a.slice(7).trim();
  const sp = new URL(req.url).searchParams;
  for (const k of ["token", "userToken", "user_token", "ut", "t", "authToken"]) {
    const v = sp.get(k);
    if (v && v.trim()) return v.trim();
  }
  if (typeof bodyToken === "string" && bodyToken.trim()) return bodyToken.trim();
  return "";
}

async function waveUserInfo(req: Request): Promise<Response> {
  try {
    let bodyToken: unknown = undefined;
    if (req.method === "POST") {
      try {
        const b = await req.clone().json();
        bodyToken = b?.token ?? b?.userToken ?? b?.user_token ?? undefined;
      } catch { /* tanpa body */ }
    }
    const uid = await verifyFirebase(bearer(req, bodyToken));
    const p = await wavePlayer(uid);
    if (!p) return json({ success: false, message: "User not found" }, 404);
    return json({ success: true, message: "User info fetched successfully", data: p });
  } catch (e) {
    const tok = (() => { try { return bearer(req); } catch { return ""; } })();
    // 401 = header token tidak ada, 233 = token ada tapi tidak valid (sesuai dokumen WaveGames)
    return json(
      { success: false, message: e instanceof Error ? e.message : "Invalid or missing user information in the token." },
      tok ? 233 : 401,
    );
  }
}

async function waveSubmitFlow(req: Request): Promise<Response> {
  let body: {
    betAmount?: number;
    type?: number;
    transactionId?: string;
    gameName?: string;
    token?: string;
    userToken?: string;
  };
  try {
    body = await req.json();
  } catch {
    return json({ success: false, message: "Invalid data" }, 422);
  }
  let uid: string;
  const subTok = bearer(req, body.token ?? body.userToken ?? body.user_token);
  try {
    uid = await verifyFirebase(subTok);
  } catch (e) {
    return json(
      { success: false, message: e instanceof Error ? e.message : "Invalid or missing user information in the token." },
      subTok ? 233 : 401,
    );
  }
  const amount = Math.trunc(Number(body.betAmount ?? 0));
  const kind = Math.trunc(Number(body.type ?? 0));
  const tx = String(body.transactionId ?? "");
  if (!Number.isFinite(amount) || amount < 0 || (kind !== 1 && kind !== 2) || !tx) {
    return json({ success: false, message: "Please Provide betAmount and type." }, 422);
  }
  const r = await rpc("w_wave_flow", {
    p_uid: uid,
    p_amount: amount,
    p_kind: kind,
    p_tx: tx,
    p_game: body.gameName ?? null,
  });
  const text = await r.text();
  if (!r.ok) {
    let msg = "Transaksi ditolak";
    try {
      const j = JSON.parse(text);
      msg = j.message ?? msg;
    } catch { /* default */ }
    if (msg.includes("SALDO_KURANG") || msg.includes("saldo")) msg = "Saldo coin tidak cukup";
    if (msg.includes("NOMINAL")) msg = "Nominal tidak valid";
    return json({ success: false, message: msg }, 200);
  }
  const p = await wavePlayer(uid);
  return json({
    success: true,
    message: "OK",
    data: p ?? { id: uid, name: "Player", balance: 0, profilePicture: "" },
  });
}


// ---------- Aksi admin (hanya token milik developer) ----------
/**
 * Tambah diamond + EXP ke dompet penerima gift (bukan pengirim).
 * Cara utama memakai fungsi SQL atomik w_gift_credit; kalau fungsi itu belum
 * dipasang di database, dipakai cara cadangan (baca lalu tulis) supaya gift
 * tetap masuk walaupun SQL belum dijalankan.
 */
/**
 * Hadiah roket diambil dari pengaturan panel admin (Realtime Database,
 * salinan publik "pub/_rocketCfg"). Isi hadiah tiap kotak: gambar, VIP,
 * coin, efek masuk, lencana. Server yang mengundi kotak mana yang dibayar,
 * jadi hadiah tidak bisa dipilih dari aplikasi.
 *   mode "launch" -> kotak acak dari roket level tertinggi yang terisi
 *   mode "lucky"  -> kotak acak dari roket level rendah (penonton)
 *   mode "rank"   -> kotak sesuai peringkat (Top 1..4)
 */
const ROCKET_CFG_URL =
  "https://arboreal-cat-511705-p6-default-rtdb.asia-southeast1.firebasedatabase.app/pub/_rocketCfg.json";

let rkCache: { at: number; data: Record<string, unknown> } | null = null;

async function rocketCfg(): Promise<Record<string, unknown>> {
  const now = Date.now();
  if (rkCache && now - rkCache.at < 60_000) return rkCache.data;
  try {
    const r = await fetch(ROCKET_CFG_URL);
    if (!r.ok) throw new Error("cfg " + r.status);
    const d = await r.json();
    rkCache = { at: now, data: (d && typeof d === "object" ? d : {}) as Record<string, unknown> };
    return rkCache.data;
  } catch {
    return rkCache?.data ?? {};
  }
}

type Prize = { coins: number; vip: number; days: number; img?: string; eff?: string; badge?: string };

function rkSlotToPrize(node: unknown, days: number): Prize | null {
  if (!node || typeof node !== "object") return null;
  const o = node as Record<string, unknown>;
  const coins = Math.max(0, Math.floor(Number(o.coins ?? 0)));
  const vip = Math.max(0, Math.min(9, Math.floor(Number(o.vip ?? 0))));
  if (coins <= 0 && vip <= 0) return null;
  return {
    coins,
    vip,
    days: vip > 0 ? days : 0,
    img: typeof o.img === "string" ? o.img : "",
    eff: typeof o.eff === "string" ? o.eff : "",
    badge: typeof o.badge === "string" ? o.badge : "",
  };
}

/** Kotak acak dari satu level roket; null kalau level itu belum diisi. */
function rkPickLevel(cfg: Record<string, unknown>, lv: number, days: number): Prize | null {
  const node = cfg["lv" + lv];
  if (!node || typeof node !== "object") return null;
  const n = node as Record<string, unknown>;
  const slots = ["r1", "r2", "r3", "r4", "r5", "r6"]
    .map((k) => rkSlotToPrize(n[k] ?? n[k.replace("r", "p")], days))
    .filter((x): x is Prize => !!x);
  if (!slots.length) return null;
  return slots[Math.floor(Math.random() * slots.length)];
}

function rkPickAny(cfg: Record<string, unknown>, levels: number[], days: number): Prize | null {
  for (const lv of levels) {
    const p = rkPickLevel(cfg, lv, days);
    if (p) return p;
  }
  return null;
}

async function rocketClaim(uid: string, p: Record<string, unknown>): Promise<Response> {
  const mode = String(p.mode ?? "launch");
  const cfg = await rocketCfg();
  let prize: Prize | null = null;

  if (mode === "rank") {
    const rank = Math.floor(Number(p.rank ?? 99));
    const key = rank >= 1 && rank <= 4 ? "t" + rank : "t4";
    const rankNode = (cfg.rank ?? {}) as Record<string, unknown>;
    prize = rkSlotToPrize(rankNode[key], 30);
    if (!prize) {
      // Bawaan kalau panel belum diisi.
      if (rank === 1) prize = { coins: 100_000_000, vip: 9, days: 30 };
      else if (rank === 2) prize = { coins: 50_000_000, vip: 8, days: 30 };
      else if (rank === 3) prize = { coins: 10_000_000, vip: 5, days: 30 };
      else prize = { coins: 10_000_000, vip: 0, days: 0 };
    }
  } else if (mode === "lucky") {
    // Penonton: hadiah dari roket level bawah supaya tidak lebih besar dari pengirim.
    prize = rkPickAny(cfg, [1, 2, 3], 7) ?? { coins: 500_000, vip: 0, days: 0 };
  } else {
    // Hadiah kontribusi diambil dari roket yang baru meluncur (Lv.1..Lv.5).
    const lv = Math.max(1, Math.min(5, Math.floor(Number(p.lv ?? 5))));
    prize = rkPickLevel(cfg, lv, 30)
      ?? rkPickAny(cfg, [5, 4, 3, 2, 1], 30)
      ?? { coins: 10_000_000, vip: 0, days: 0 };
  }

  const coins = prize.coins;
  const vip = prize.vip;
  const days = prize.days;
  try {
    const r = await rpc("w_rocket_reward", { p_uid: uid, p_coins: coins, p_vip: vip, p_days: days });
    if (!r.ok) {
      const txt = await r.text().catch(() => "");
      return json({ ok: 0, e: "Hadiah belum bisa diklaim, coba lagi", code: txt.slice(0, 120) }, 200);
    }
    const w = await r.json().catch(() => null);
    return json({
      ok: 1, coins, vip, days, w,
      img: prize.img ?? "", eff: prize.eff ?? "", badge: prize.badge ?? "",
    });
  } catch {
    return json({ ok: 0, e: "Server sibuk, coba lagi" }, 200);
  }
}

/**
 * CASHBACK GIFT.
 * Pengaturan diambil dari panel developer (Realtime Database, salinan publik
 * "pub/_cashbackCfg") supaya bisa diubah tanpa build aplikasi:
 *   { on, rtp, recvPct, jpMin, t1..t4: { min, rows: [{ v, w }, ...] }, assets: { "<v>": "<url svga>" } }
 * Server yang mengundi pecahan cashback; aplikasi hanya menampilkan hasilnya.
 * Pengirim menerima cashback, penerima menerima bonus recvPct% dari nilai gift.
 */
const CASHBACK_CFG_URL =
  "https://arboreal-cat-511705-p6-default-rtdb.asia-southeast1.firebasedatabase.app/pub/_cashbackCfg.json";

let cbCache: { at: number; data: Record<string, unknown> } | null = null;

async function cashbackCfg(): Promise<Record<string, unknown>> {
  const now = Date.now();
  if (cbCache && now - cbCache.at < 60_000) return cbCache.data;
  try {
    const r = await fetch(CASHBACK_CFG_URL);
    if (!r.ok) throw new Error("cfg " + r.status);
    const d = await r.json();
    cbCache = { at: now, data: (d && typeof d === "object" ? d : {}) as Record<string, unknown> };
    return cbCache.data;
  } catch {
    return cbCache?.data ?? {};
  }
}

type CbRow = { v: number; w: number };

function cbRowsOf(node: unknown): CbRow[] {
  const o = node as Record<string, unknown> | null;
  const rows = Array.isArray(o?.rows) ? (o.rows as unknown[]) : [];
  const out: CbRow[] = [];
  for (const r of rows) {
    const x = r as Record<string, unknown>;
    const v = Math.max(0, Math.floor(Number(x.v ?? 0)));
    const w = Math.max(0, Number(x.w ?? 0));
    if (v > 0 && w > 0) out.push({ v, w });
  }
  return out;
}

/** Tier dipilih dari harga gift: t1 kecil, t4 paling besar. */
function cbTierOf(cfg: Record<string, unknown>, value: number): CbRow[] {
  let best: CbRow[] = [];
  let bestMin = -1;
  for (const k of ["t1", "t2", "t3", "t4"]) {
    const node = cfg[k] as Record<string, unknown> | null;
    if (!node) continue;
    const min = Math.floor(Number(node.min ?? 0));
    const rows = cbRowsOf(node);
    if (!rows.length) continue;
    if (value >= min && min >= bestMin) {
      best = rows;
      bestMin = min;
    }
  }
  if (!best.length) {
    // Bawaan kalau panel belum diisi: pecahan kecil sampai 10000.
    best = [
      { v: 10, w: 30 }, { v: 20, w: 20 }, { v: 30, w: 15 }, { v: 50, w: 12 },
      { v: 66, w: 8 }, { v: 88, w: 6 }, { v: 100, w: 5 }, { v: 200, w: 2.5 },
      { v: 500, w: 1 }, { v: 1000, w: 0.4 }, { v: 4000, w: 0.08 }, { v: 10000, w: 0.02 },
    ];
  }
  return best;
}

function cbPick(rows: CbRow[]): CbRow {
  const total = rows.reduce((s, r) => s + r.w, 0);
  let x = Math.random() * total;
  for (const r of rows) {
    x -= r.w;
    if (x <= 0) return r;
  }
  return rows[rows.length - 1];
}

/**
 * Pecahan tampilan cashback: angka dari daftar aset SVGA yang sudah ada di aplikasi.
 * Hanya dipakai kalau total tidak lebih dari 50.000; di atas itu cukup totalnya saja.
 */
const CB_LADDER = [10000, 9000, 8000, 6000, 4000, 3000, 2000, 1000, 888, 666, 500, 400, 300, 200, 100, 88, 66, 50, 30, 20, 10];

function cbSplit(total: number): number[] {
  if (!(total > 0) || total > 50000) return [];
  const out: number[] = [];
  let rem = Math.floor(total);
  let guard = 0;
  while (rem >= 10 && out.length < 8 && guard++ < 16) {
    const v = CB_LADDER.find((x) => x <= rem);
    if (!v) break;
    out.push(v);
    rem -= v;
  }
  return out;
}

async function giftCashback(uid: string, p: Record<string, unknown>): Promise<Response> {
  try {
    const cfg = await cashbackCfg();
    if (cfg.on === false) return json({ ok: 1, amount: 0, off: 1 });

    const price = Math.max(0, Math.floor(Number(p.price ?? 0)));
    const count = Math.max(1, Math.min(999, Math.floor(Number(p.count ?? 1))));
    const value = price * count;
    if (value <= 0) return json({ ok: 0, e: "Nilai gift tidak valid" }, 200);

    // Tabel panel berisi PENGALI gift (x0, x1, x2, x5, x10, x50, x250 dst).
    // Total cashback = harga gift x pengali yang keluar dari undian.
    const row = cbPick(cbTierOf(cfg, value));
    const mult = Math.max(0, Math.min(500, Math.floor(row.v)));
    const target = value * mult;
    // Total dipecah jadi beberapa pecahan dari daftar aset (10..10000).
    const parts = cbSplit(target);
    const amount = parts.length ? parts.reduce((a, b) => a + b, 0) : target;

    // Kredit cashback ke pengirim.
    let paid = 0;
    try {
      const r = await rpc("w_topup", { p_uid: uid, p_coins: amount });
      if (r.ok) paid = amount;
      else await r.text().catch(() => "");
    } catch { /* diabaikan, saldo tidak berubah */ }

    // Bonus untuk penerima gift (bawaan 5% dari nilai gift).
    const recvPct = Math.max(0, Math.min(100, Number(cfg.recvPct ?? 5)));
    const to = String(p.to ?? "").trim();
    const recvBonus = to ? Math.floor((value * recvPct) / 100) : 0;
    let recvPaid = 0;
    if (to && recvBonus > 0) {
      try {
        const r2 = await rpc("w_topup", { p_uid: to, p_coins: recvBonus });
        if (r2.ok) recvPaid = recvBonus;
        else await r2.text().catch(() => "");
      } catch { /* diabaikan */ }
    }

    const jpMin = Math.max(0, Math.floor(Number(cfg.jpMin ?? 1000)));
    return json({
      ok: 1,
      amount: paid,
      mult: mult,
      parts: parts.slice(0, 8),
      pecahan: parts[0] ?? row.v,
      recv: recvPaid,
      jp: paid >= jpMin ? 1 : 0,
      asset: "",
    });
  } catch {
    return json({ ok: 0, e: "Server sibuk, coba lagi" }, 200);
  }
}

async function giftCredit(targets: string[], each: number, itemId: string, roomId: string): Promise<number> {
  // Semua penerima diproses SEKALIGUS dan catatan audit tidak ditunggu, supaya
  // pengiriman gift tidak ikut menunggu satu per satu (dulu berasa lambat).
  const list = targets.map((t) => String(t || "").trim()).filter((t) => t && each > 0);
  if (!list.length) return 0;
  const audit: Promise<unknown>[] = [];
  const hasil = await Promise.all(list.map(async (to) => {
    let done = false;
    try {
      const r = await rpc("w_gift_credit", { p_to: to, p_amount: each });
      if (r.ok) done = true;
      else await r.text().catch(() => "");
    } catch { /* pakai cara cadangan */ }
    if (!done) {
      try {
        const cur = await sbRest(`wallets?uid=eq.${encodeURIComponent(to)}&select=diamonds,exp`);
        const rows = cur.ok ? await cur.json().catch(() => null) : null;
        if (Array.isArray(rows) && rows.length) {
          const patch = await sbRest(`wallets?uid=eq.${encodeURIComponent(to)}`, {
            method: "PATCH",
            headers: { Prefer: "return=minimal" },
            body: JSON.stringify({
              diamonds: Number(rows[0].diamonds ?? 0) + each,
              exp: Number(rows[0].exp ?? 0) + each,
              updated_at: new Date().toISOString(),
            }),
          });
          done = patch.ok;
        }
      } catch { /* abaikan */ }
    }
    if (done) {
      // Catatan audit dikirim tanpa ditunggu.
      audit.push(
        sbRest("money_tx", {
          method: "POST",
          headers: { Prefer: "return=minimal" },
          body: JSON.stringify([{ uid: to, kind: "gift_recv", item_id: itemId, amount: each, room_id: roomId }]),
        }).catch(() => null),
      );
    }
    return done ? 1 : 0;
  }));
  // Beri kesempatan audit selesai, tapi tidak menahan balasan lama.
  if (audit.length) await Promise.race([Promise.all(audit), new Promise((r) => setTimeout(r, 400))]);
  return hasil.reduce((a, b) => a + b, 0);
}

async function sbRest(path: string, init: RequestInit = {}): Promise<Response> {
  const sr = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  return await fetch(`${Deno.env.get("SUPABASE_URL")}/rest/v1/${path}`, {
    ...init,
    headers: {
      apikey: sr,
      Authorization: `Bearer ${sr}`,
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  });
}

/**
 * Kosongkan satu kolom saldo untuk semua user dengan satu perintah di database.
 * Hasilnya jumlah baris yang berubah (0 kalau sudah kosong), atau null kalau gagal.
 */
async function resetAllField(field: "coins" | "diamonds"): Promise<number | null> {
  const r = await sbRest("rpc/w_admin_reset_all", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ p_field: field }),
  });
  if (r.ok) {
    const n = await r.json().catch(() => null);
    if (typeof n === "number") return n;
  }
  // Cadangan kalau fungsi database belum dipasang: update tanpa mengembalikan data.
  const r2 = await sbRest(`wallets?${field}=neq.0&select=uid`, {
    method: "PATCH",
    headers: { Prefer: "return=minimal, count=exact" },
    body: JSON.stringify({ [field]: 0, updated_at: new Date().toISOString() }),
  });
  if (!r2.ok) return null;
  const cr = r2.headers.get("content-range") ?? "";
  const m = cr.match(/\/(\d+)$/);
  return m ? Number(m[1]) : 0;
}

async function adminWallet(uid: string): Promise<Record<string, unknown> | null> {
  const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(uid)}&select=*`);
  if (!r.ok) return null;
  const rows = await r.json().catch(() => null);
  return Array.isArray(rows) && rows.length ? rows[0] : null;
}

// ---------- Hapus akun login Firebase (butuh service account) ----------
// Service account disimpan sebagai secret function FIREBASE_SA_JSON
// (isi file JSON dari Firebase Console > Project settings > Service accounts).
// Tanpa secret ini data user tetap bisa dihapus, tapi akun loginnya tetap ada.
async function firebaseDeleteUser(uid: string): Promise<string> {
  const raw = Deno.env.get("FIREBASE_SA_JSON") ?? "";
  if (!raw) return "belum diatur";
  let sa: { project_id?: string; client_email?: string; private_key?: string };
  try {
    sa = JSON.parse(raw);
  } catch {
    return "format secret salah";
  }
  if (!sa.project_id || !sa.client_email || !sa.private_key) return "secret tidak lengkap";
  try {
    const b64u = (b: ArrayBuffer) =>
      btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    const b64uStr = (t: string) => b64u(new TextEncoder().encode(t).buffer as ArrayBuffer);
    const now = Math.floor(Date.now() / 1000);
    const kepala = b64uStr(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const badan = b64uStr(JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/identitytoolkit",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    }));
    const inti = `${kepala}.${badan}`;
    const pem = sa.private_key.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
    const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
    const kunci = await crypto.subtle.importKey(
      "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
    );
    const ttd = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", kunci, new TextEncoder().encode(inti));
    const jwt = `${inti}.${b64u(ttd)}`;
    const tr = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: jwt,
      }).toString(),
    });
    const tj = await tr.json().catch(() => ({})) as { access_token?: string };
    if (!tj.access_token) return "gagal ambil token Google";
    const del = await fetch(
      `https://identitytoolkit.googleapis.com/v1/projects/${sa.project_id}/accounts:delete`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${tj.access_token}` },
        body: JSON.stringify({ localId: uid }),
      },
    );
    if (del.ok) return "terhapus";
    const txt = await del.text().catch(() => "");
    if (txt.includes("USER_NOT_FOUND")) return "terhapus";
    return "gagal: " + txt.slice(0, 100);
  } catch (e) {
    return "gagal: " + (e instanceof Error ? e.message : "tidak diketahui");
  }
}

async function handleAdmin(body: { t?: string; p?: Record<string, unknown> }): Promise<Response> {
  let who: { uid: string; email: string };
  try {
    who = await verifyFirebaseFull(body.t ?? "");
  } catch (e) {
    return json({ ok: 0, e: e instanceof Error ? e.message : "Token tidak valid" }, 401);
  }
  if (who.email !== ADMIN_EMAIL) return json({ ok: 0, e: "Hanya admin" }, 403);

  const p = body.p ?? {};
  const op = String(p.op ?? "");
  const target = String(p.uid ?? "");
  const tanpaUid = ["reset_all", "reset_gem_all", "pay_get", "pay_set", "manual_pending", "manual_all", "manual_decide"];
  if (!tanpaUid.includes(op) && !target) return json({ ok: 0, e: "uid kosong" }, 400);

  const num = (v: unknown) => {
    const n = Math.trunc(Number(v));
    return Number.isSafeInteger(n) ? n : NaN;
  };

  // ---- topup manual DANA (panel developer) ----
  if (op === "pay_get") {
    const r = await rpc("w_pay_info", {});
    const v = r.ok ? await r.json().catch(() => ({})) : {};
    return json({ ok: 1, pay: v ?? {} });
  }

  if (op === "pay_set") {
    let r = await rpc("w_pay_set_v2", {
      p_dana: String(p.dana ?? ""),
      p_name: String(p.name ?? ""),
      p_gopay: String(p.gopay ?? ""),
      p_qris: String(p.qris ?? ""),
    });
    if (!r.ok) {
      r = await rpc("w_pay_set", { p_dana: String(p.dana ?? ""), p_name: String(p.name ?? "") });
    }
    if (!r.ok) return json({ ok: 0, e: "Gagal menyimpan info pembayaran" }, 200);
    return json({ ok: 1, pay: await r.json().catch(() => ({})) });
  }

  if (op === "manual_pending" || op === "manual_all") {
    const r = await rpc("w_manual_list", {
      p_status: op === "manual_all" ? "all" : "pending",
      p_limit: p.limit ?? 60,
    });
    const rows = r.ok ? await r.json().catch(() => []) : [];
    return json({ ok: 1, list: rows });
  }

  if (op === "manual_decide") {
    const r = await rpc("w_manual_decide", { p_id: String(p.id ?? ""), p_ok: p.ok === true || p.ok === "true" });
    if (!r.ok) {
      let msg = "Gagal memproses pengajuan";
      try {
        const j = await r.json();
        msg = String(j.message ?? msg).replace(/^.*?:\s*/, "");
      } catch { /* biarkan */ }
      return json({ ok: 0, e: msg }, 200);
    }
    return json({ ok: 1, r: await r.json().catch(() => ({})) });
  }

  if (op === "get") {
    const w = await adminWallet(target);
    return json({ ok: 1, w: w ?? { uid: target, coins: 0, diamonds: 0, exp: 0, vip: 0, vip_until: 0 } });
  }

  if (op === "add") {
    const coins = p.coins === undefined ? 0 : num(p.coins);
    const exp = p.exp === undefined ? 0 : num(p.exp);
    if (Number.isNaN(coins) || Number.isNaN(exp) || Math.abs(coins) > 1e12 || Math.abs(exp) > 1e12) {
      return json({ ok: 0, e: "Jumlah tidak valid" }, 400);
    }
    await sbRest("wallets?on_conflict=uid", {
      method: "POST",
      headers: { Prefer: "resolution=ignore-duplicates" },
      body: JSON.stringify({ uid: target }),
    });
    const cur = await adminWallet(target);
    const nc = Number(cur?.coins ?? 0) + coins;
    const ne = Number(cur?.exp ?? 0) + exp;
    const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(target)}`, {
      method: "PATCH",
      headers: { Prefer: "return=representation" },
      body: JSON.stringify({ coins: nc < 0 ? 0 : nc, exp: ne < 0 ? 0 : ne, updated_at: new Date().toISOString() }),
    });
    if (!r.ok) return json({ ok: 0, e: "Gagal menyimpan saldo" }, 200);
    if (coins !== 0) {
      await sbRest("money_tx", {
        method: "POST",
        body: JSON.stringify({ uid: target, kind: "admin_coin", item_id: who.email, amount: coins }),
      });
    }
    return json({ ok: 1, w: (await adminWallet(target)) });
  }

  if (op === "vip") {
    const tier = num(p.tier);
    const until = num(p.until);
    if (!(tier >= 0 && tier <= 9) || Number.isNaN(until)) return json({ ok: 0, e: "VIP tidak valid" }, 400);
    await sbRest("wallets?on_conflict=uid", {
      method: "POST",
      headers: { Prefer: "resolution=ignore-duplicates" },
      body: JSON.stringify({ uid: target }),
    });
    const cur = await adminWallet(target);
    const inv = { ...((cur?.inv as Record<string, unknown>) ?? {}) };
    if (tier > 0) {
      for (const k of ["vipt_", "vipf_", "vipb_", "vipe_"]) inv[k + tier] = until;
    }
    const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(target)}`, {
      method: "PATCH",
      body: JSON.stringify({ vip: tier, vip_until: until, inv, updated_at: new Date().toISOString() }),
    });
    if (!r.ok) return json({ ok: 0, e: "Gagal menyimpan VIP" }, 200);
    return json({ ok: 1, w: (await adminWallet(target)) });
  }

  // ---- hapus akun user (panel developer) ----
  // Menghapus semua data milik user: wallet, riwayat transaksi, permintaan tukar,
  // topup, dan data permainan. Setelah itu akun login Firebase ikut dihapus
  // (kalau secret FIREBASE_SA_JSON tersedia) sehingga user harus daftar ulang.
  if (op === "delete_user") {
    if (target === who.uid) return json({ ok: 0, e: "Tidak bisa menghapus akun sendiri" }, 400);
    const tabel = [
      "wallets", "money_tx", "exchange_req", "topup_orders",
      "topup_manual", "wave_players", "wave_tx",
    ];
    const gagal: string[] = [];
    for (const t of tabel) {
      const r = await sbRest(`${t}?uid=eq.${encodeURIComponent(target)}`, { method: "DELETE" });
      if (!r.ok) gagal.push(t);
    }
    const auth = await firebaseDeleteUser(target);
    return json({ ok: 1, auth, tabel_gagal: gagal });
  }

  // ---- hapus akun BANYAK user sekaligus (panel developer) ----
  // Panel mengirim daftar uid per batch kecil. Server tidak pernah menghapus
  // akun admin yang sedang login, dan melewati uid yang masuk daftar "kecuali".
  if (op === "delete_users") {
    const bersih = (v: unknown) =>
      Array.isArray(v)
        ? (v as unknown[]).map((x) => String(x ?? "")).filter((x) => /^[A-Za-z0-9_-]{6,128}$/.test(x))
        : [];
    const daftar = bersih(p.uids).slice(0, 25);
    const kecuali = new Set(bersih(p.kecuali));
    kecuali.add(who.uid);
    const tabel = [
      "wallets", "money_tx", "exchange_req", "topup_orders",
      "topup_manual", "wave_players", "wave_tx",
    ];
    let terhapus = 0, dilewati = 0, authGagal = 0;
    for (const uid of daftar) {
      if (kecuali.has(uid)) { dilewati++; continue; }
      for (const t of tabel) {
        await sbRest(`${t}?uid=eq.${encodeURIComponent(uid)}`, { method: "DELETE" });
      }
      const a = await firebaseDeleteUser(uid);
      if (a !== "terhapus" && a !== "belum diatur") authGagal++;
      terhapus++;
    }
    const authDiatur = (Deno.env.get("FIREBASE_SA_JSON") ?? "") !== "";
    return json({ ok: 1, terhapus, dilewati, authGagal, authDiatur });
  }

  // ---- reset aset SEMUA user (panel developer) ----
  // Membersihkan aset/VIP/level seluruh user sekaligus. Katalog store
  // (item unggahan) tidak disentuh.
  if (op === "reset_all_assets") {
    const patch: Record<string, unknown> = { updated_at: new Date().toISOString() };
    const bagian: string[] = [];
    if (p.assets !== false) {
      patch.inv = {};
      patch.equip = {};
      bagian.push("aset");
    }
    if (p.vip !== false) {
      patch.vip = 0;
      patch.vip_until = 0;
      bagian.push("vip");
    }
    if (p.level === true) {
      patch.exp = 0;
      patch.lv_claimed = 0;
      bagian.push("level");
    }
    if (p.coins === true) {
      patch.coins = 0;
      bagian.push("coin");
    }
    if (p.gems === true) {
      patch.diamonds = 0;
      bagian.push("diamond");
    }
    if (bagian.length === 0) return json({ ok: 0, e: "Tidak ada bagian yang dipilih" }, 400);
    // Akun official yang harus dilewati (dikirim panel).
    const kecuali = Array.isArray(p.kecuali)
      ? (p.kecuali as unknown[]).map((x) => String(x ?? "")).filter((x) => /^[A-Za-z0-9]+$/.test(x))
      : [];
    let saring = "uid=not.is.null";
    if (kecuali.length > 0) {
      saring += `&uid=not.in.(${kecuali.join(",")})`;
    }
    // Satu perintah untuk semua baris. Hanya uid yang dikembalikan supaya
    // fungsi tidak kehabisan tenaga (HTTP 546) saat user sudah banyak.
    const r = await sbRest(`wallets?${saring}&select=uid`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
    let jumlah = 0;
    if (r.ok) {
      const rows = await r.json().catch(() => []);
      jumlah = Array.isArray(rows) ? rows.length : 0;
    } else {
      return json({ ok: 0, e: "Gagal reset semua aset" }, 200);
    }
    await sbRest("money_tx", {
      method: "POST",
      body: JSON.stringify({
        uid: who.uid,
        kind: "admin_reset_all_assets",
        item_id: who.email,
        amount: 0,
      }),
    });
    return json({ ok: 1, count: jumlah, bagian, dilewati: kecuali.length });
  }

  // ---- reset aset user (panel developer) ----
  // Menghapus yang DIMILIKI user: frame, medal, efek masuk, bubble, badge,
  // gelar, VIP/SVIP, level. Katalog store (item unggahan) tidak disentuh,
  // jadi item tetap tampil di store dan bisa dibeli lagi.
  if (op === "reset_user") {
    const ada = await adminWallet(target);
    if (!ada) return json({ ok: 0, e: "User ini belum punya dompet" }, 200);
    const patch: Record<string, unknown> = { updated_at: new Date().toISOString() };
    const bagian: string[] = [];
    // Default: aset + VIP. Coin, diamond, dan level hanya jika diminta.
    if (p.assets !== false) {
      patch.inv = {};
      patch.equip = {};
      bagian.push("aset");
    }
    if (p.vip !== false) {
      patch.vip = 0;
      patch.vip_until = 0;
      bagian.push("vip");
    }
    if (p.level === true) {
      patch.exp = 0;
      patch.lv_claimed = 0;
      bagian.push("level");
    }
    if (p.coins === true) {
      patch.coins = 0;
      bagian.push("coin");
    }
    if (p.gems === true) {
      patch.diamonds = 0;
      bagian.push("diamond");
    }
    if (bagian.length === 0) return json({ ok: 0, e: "Tidak ada bagian yang dipilih" }, 400);
    const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(target)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
    if (!r.ok) return json({ ok: 0, e: "Gagal reset aset" }, 200);
    // Jejak audit supaya jelas siapa yang membersihkan aset user ini.
    await sbRest("money_tx", {
      method: "POST",
      body: JSON.stringify({
        uid: target,
        kind: "admin_reset_user",
        item_id: who.email,
        amount: 0,
      }),
    });
    return json({ ok: 1, bagian, w: (await adminWallet(target)) });
  }

  if (op === "reset") {
    const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(target)}`, {
      method: "PATCH",
      body: JSON.stringify({ coins: 0, updated_at: new Date().toISOString() }),
    });
    if (!r.ok) return json({ ok: 0, e: "Gagal reset" }, 200);
    return json({ ok: 1, w: (await adminWallet(target)) });
  }

  if (op === "reset_all") {
    // Satu perintah SQL di database. Cara lama (PATCH + return=representation)
    // mengembalikan seluruh isi tabel sekaligus sehingga fungsi kehabisan
    // tenaga (HTTP 546) waktu user sudah banyak.
    const n = await resetAllField("coins");
    if (n === null) return json({ ok: 0, e: "Gagal reset semua" }, 200);
    return json({ ok: 1, count: n });
  }

  // ---- reset diamond (panel developer) ----
  if (op === "reset_gem") {
    const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(target)}`, {
      method: "PATCH",
      body: JSON.stringify({ diamonds: 0, updated_at: new Date().toISOString() }),
    });
    if (!r.ok) return json({ ok: 0, e: "Gagal reset diamond" }, 200);
    return json({ ok: 1, w: (await adminWallet(target)) });
  }

  if (op === "reset_gem_all") {
    const n = await resetAllField("diamonds");
    if (n === null) return json({ ok: 0, e: "Gagal reset semua diamond" }, 200);
    // audit trail: catat siapa yang mengosongkan diamond semua user
    await sbRest("money_tx", {
      method: "POST",
      body: JSON.stringify({
        uid: target || who.uid,
        kind: "admin_gem_reset_all",
        item_id: who.email,
        amount: 0,
      }),
    });
    return json({ ok: 1, count: n });
  }

  return json({ ok: 0, e: "Operasi tidak dikenal" }, 400);
}


// ================= TOPUP LEWAT PAYMENT GATEWAY (Midtrans) =================
// Alur:
//  1. App panggil aksi "topup_create" -> server membuat order + transaksi Snap,
//     lalu mengembalikan URL pembayaran. Harga ditentukan server, bukan app.
//  2. User bayar di DANA/OVO/ShopeePay/QRIS lewat halaman Midtrans.
//  3. Midtrans kirim webhook ke fungsi ini (path mengandung "midtrans").
//     Tanda tangan diverifikasi, baru coin ditambahkan.
//  4. App memanggil "topup_status" untuk tahu order sudah lunas atau belum.
// Kunci yang dibutuhkan (Supabase secrets):
//   MIDTRANS_SERVER_KEY  = Server Key dari dashboard Midtrans
//   MIDTRANS_PRODUCTION  = "true" untuk produksi, kosong/false untuk sandbox

const TOPUP_PACKS: Record<string, number> = {
  "1000000": 6000,
  "3000000": 18000,
  "10000000": 58000,
  "30000000": 170000,
};

function midtransKey(): string {
  return Deno.env.get("MIDTRANS_SERVER_KEY") ?? "";
}

function midtransBase(): string {
  const prod = (Deno.env.get("MIDTRANS_PRODUCTION") ?? "false").toLowerCase() === "true";
  return prod ? "https://app.midtrans.com/snap/v1/transactions" : "https://app.sandbox.midtrans.com/snap/v1/transactions";
}

/** SHA-512 heksadesimal untuk tanda tangan webhook Midtrans. */
async function sha512(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  // crypto.subtle belum punya SHA-512 di semua runtime Deno lama -> pakai implementasi ringkas
  const K = [
    0x428a2f98d728ae22n, 0x7137449123ef65cdn, 0xb5c0fbcfec4d3b2fn, 0xe9b5dba58189dbbcn,
    0x3956c25bf348b538n, 0x59f111f1b605d019n, 0x923f82a4af194f9bn, 0xab1c5ed5da6d8118n,
    0xd807aa98a3030242n, 0x12835b0145706fben, 0x243185be4ee4b28cn, 0x550c7dc3d5ffb4e2n,
    0x72be5d74f27b896fn, 0x80deb1fe3b1696b1n, 0x9bdc06a725c71235n, 0xc19bf174cf692694n,
    0xe49b69c19ef14ad2n, 0xefbe4786384f25e3n, 0x0fc19dc68b8cd5b5n, 0x240ca1cc77ac9c65n,
    0x2de92c6f592b0275n, 0x4a7484aa6ea6e483n, 0x5cb0a9dcbd41fbd4n, 0x76f988da831153b5n,
    0x983e5152ee66dfabn, 0xa831c66d2db43210n, 0xb00327c898fb213fn, 0xbf597fc7beef0ee4n,
    0xc6e00bf33da88fc2n, 0xd5a79147930aa725n, 0x06ca6351e003826fn, 0x142929670a0e6e70n,
    0x27b70a8546d22ffcn, 0x2e1b21385c26c926n, 0x4d2c6dfc5ac42aedn, 0x53380d139d95b3dfn,
    0x650a73548baf63den, 0x766a0abb3c77b2a8n, 0x81c2c92e47edaee6n, 0x92722c851482353bn,
    0xa2bfe8a14cf10364n, 0xa81a664bbc423001n, 0xc24b8b70d0f89791n, 0xc76c51a30654be30n,
    0xd192e819d6ef5218n, 0xd69906245565a910n, 0xf40e35855771202an, 0x106aa07032bbd1b8n,
    0x19a4c116b8d2d0c8n, 0x1e376c085141ab53n, 0x2748774cdf8eeb99n, 0x34b0bcb5e19b48a8n,
    0x391c0cb3c5c95a63n, 0x4ed8aa4ae3418acbn, 0x5b9cca4f7763e373n, 0x682e6ff3d6b2b8a3n,
    0x748f82ee5defb2fcn, 0x78a5636f43172f60n, 0x84c87814a1f0ab72n, 0x8cc702081a6439ecn,
    0x90befffa23631e28n, 0xa4506cebde82bde9n, 0xbef9a3f7b2c67915n, 0xc67178f2e372532bn,
    0xca273eceea26619cn, 0xd186b8c721c0c207n, 0xeada7dd6cde0eb1en, 0xf57d4f7fee6ed178n,
    0x06f067aa72176fban, 0x0a637dc5a2c898a6n, 0x113f9804bef90daen, 0x1b710b35131c471bn,
    0x28db77f523047d84n, 0x32caab7b40c72493n, 0x3c9ebe0a15c9bebcn, 0x431d67c49c100d4cn,
    0x4cc5d4becb3e42b6n, 0x597f299cfc657e2an, 0x5fcb6fab3ad6faecn, 0x6c44198c4a475817n,
  ];
  const H = [
    0x6a09e667f3bcc908n, 0xbb67ae8584caa73bn, 0x3c6ef372fe94f82bn, 0xa54ff53a5f1d36f1n,
    0x510e527fade682d1n, 0x9b05688c2b3e6c1fn, 0x1f83d9abfb41bd6bn, 0x5be0cd19137e2179n,
  ];
  const M = (1n << 64n) - 1n;
  const rotr = (x: bigint, n: bigint) => ((x >> n) | (x << (64n - n))) & M;
  const bitlen = BigInt(data.length) * 8n;
  const withOne = new Uint8Array((((data.length + 9) + 127) / 128 | 0) * 128);
  withOne.set(data);
  withOne[data.length] = 0x80;
  const dv = new DataView(withOne.buffer);
  dv.setBigUint64(withOne.length - 8, bitlen & M);
  for (let off = 0; off < withOne.length; off += 128) {
    const W: bigint[] = [];
    for (let i = 0; i < 16; i++) W.push(dv.getBigUint64(off + i * 8));
    for (let i = 16; i < 80; i++) {
      const s0 = rotr(W[i - 15], 1n) ^ rotr(W[i - 15], 8n) ^ (W[i - 15] >> 7n);
      const s1 = rotr(W[i - 2], 19n) ^ rotr(W[i - 2], 61n) ^ (W[i - 2] >> 6n);
      W.push((W[i - 16] + s0 + W[i - 7] + s1) & M);
    }
    let [a, b, c, d, e, f, g, h] = H;
    for (let i = 0; i < 80; i++) {
      const S1 = rotr(e, 14n) ^ rotr(e, 18n) ^ rotr(e, 41n);
      const ch = (e & f) ^ (~e & M & g);
      const t1 = (h + S1 + ch + K[i] + W[i]) & M;
      const S0 = rotr(a, 28n) ^ rotr(a, 34n) ^ rotr(a, 39n);
      const mj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + mj) & M;
      h = g; g = f; f = e; e = (d + t1) & M;
      d = c; c = b; b = a; a = (t1 + t2) & M;
    }
    H[0] = (H[0] + a) & M; H[1] = (H[1] + b) & M; H[2] = (H[2] + c) & M; H[3] = (H[3] + d) & M;
    H[4] = (H[4] + e) & M; H[5] = (H[5] + f) & M; H[6] = (H[6] + g) & M; H[7] = (H[7] + h) & M;
  }
  return H.map((x) => x.toString(16).padStart(16, "0")).join("");
}

async function topupCreate(uid: string, body: Record<string, unknown>): Promise<Response> {
  const coins = String(body.coins ?? "").replace(/[^0-9]/g, "");
  const amount = TOPUP_PACKS[coins];
  if (!amount) return json({ ok: 0, e: "Paket coin tidak dikenal" }, 200);
  const name = String(body.name ?? "Player").slice(0, 40);
  if (xenditKey()) return await xenditTopup(uid, coins, amount, name);

  const key = midtransKey();
  if (!key) return json({ ok: 0, e: "Pembayaran belum diaktifkan admin" }, 200);

  const orderId = "RZ" + Date.now().toString(36).toUpperCase() + Math.random().toString(36).slice(2, 6).toUpperCase();

  const ins = await sbRest("topup_orders", {
    method: "POST",
    headers: { Prefer: "return=minimal" },
    body: JSON.stringify({
      id: orderId,
      uid,
      coins: Number(coins),
      amount,
      provider: "midtrans",
      status: "pending",
      created_at: Date.now(),
    }),
  });
  if (!ins.ok) return json({ ok: 0, e: "Gagal membuat pesanan" }, 200);

  const payload = {
    transaction_details: { order_id: orderId, gross_amount: amount },
    item_details: [{ id: "coin", price: amount, quantity: 1, name: `${coins} coin` }],
    customer_details: { first_name: name },
    enabled_payments: ["dana", "ovo", "shopeepay", "other_qris", "gopay", "bank_transfer"],
  };

  let snap: Record<string, unknown> = {};
  try {
    const r = await fetch(midtransBase(), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: "Basic " + btoa(key + ":"),
      },
      body: JSON.stringify(payload),
    });
    snap = await r.json().catch(() => ({}));
    if (!r.ok) {
      const msg = String((snap as { error_messages?: string[] }).error_messages?.[0] ?? "Gateway menolak transaksi");
      await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}`, { method: "PATCH", body: JSON.stringify({ status: "failed" }) });
      return json({ ok: 0, e: msg }, 200);
    }
  } catch (e) {
    return json({ ok: 0, e: "Tidak bisa menghubungi gateway: " + (e instanceof Error ? e.message : "gagal") }, 200);
  }

  const url = String(snap.redirect_url ?? "");
  const token = String(snap.token ?? "");
  if (!url) return json({ ok: 0, e: "Gateway tidak memberi alamat pembayaran" }, 200);
  await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}`, {
    method: "PATCH",
    body: JSON.stringify({ token }),
  });
  return json({ ok: 1, order: orderId, url, coins: Number(coins), amount });
}

/** Tambah coin sekali saja untuk order yang sudah lunas. */
async function creditOrder(order: Record<string, unknown>): Promise<boolean> {
  const id = String(order.id);
  const up = await sbRest(`topup_orders?id=eq.${encodeURIComponent(id)}&status=eq.pending`, {
    method: "PATCH",
    headers: { Prefer: "return=representation" },
    body: JSON.stringify({ status: "paid", paid_at: Date.now() }),
  });
  if (!up.ok) return false;
  const rows = await up.json().catch(() => null);
  if (!Array.isArray(rows) || rows.length === 0) return false; // sudah pernah diproses
  const r = await rpc("w_topup", { p_uid: String(order.uid), p_coins: Number(order.coins) });
  if (!r.ok) {
    // coin gagal ditambah -> kembalikan ke pending supaya bisa dicoba lagi
    await sbRest(`topup_orders?id=eq.${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ status: "pending", paid_at: null }),
    });
    return false;
  }
  return true;
}


// ================= XENDIT (Invoice) =================
// Dipakai kalau secret XENDIT_SERVER_KEY diisi. Pembeli bisa bayar dengan
// DANA, OVO, ShopeePay, QRIS, VA bank, dan lainnya lewat halaman invoice.
// Webhook: POST /wallet-api/xendit dengan header x-callback-token.

function xenditKey(): string {
  return Deno.env.get("XENDIT_SERVER_KEY") ?? Deno.env.get("XENDIT_SECRET_KEY") ?? "";
}

function xenditCallbackToken(): string {
  return Deno.env.get("XENDIT_CALLBACK_TOKEN") ?? "";
}

async function xenditTopup(uid: string, coins: string, amount: number, name: string): Promise<Response> {
  const key = xenditKey();
  const orderId = "RZ" + Date.now().toString(36).toUpperCase() + Math.random().toString(36).slice(2, 6).toUpperCase();

  const ins = await sbRest("topup_orders", {
    method: "POST",
    headers: { Prefer: "return=minimal" },
    body: JSON.stringify({
      id: orderId,
      uid,
      coins: Number(coins),
      amount,
      provider: "xendit",
      status: "pending",
      created_at: Date.now(),
    }),
  });
  if (!ins.ok) return json({ ok: 0, e: "Gagal membuat pesanan" }, 200);

  const site = "https://exbottod.github.io/voice-chat-room-real-time/";
  const payload = {
    external_id: orderId,
    amount,
    currency: "IDR",
    description: `${coins} coin REZO CHAT VOICE`,
    invoice_duration: 3600,
    success_redirect_url: site,
    failure_redirect_url: site,
    customer: { given_names: name || "Player" },
    customer_notification: { invoice_created: [] },
  };

  let inv: Record<string, unknown> = {};
  try {
    const r = await fetch("https://api.xendit.co/v2/invoices", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Basic " + btoa(key + ":"),
      },
      body: JSON.stringify(payload),
    });
    inv = await r.json().catch(() => ({}));
    if (!r.ok) {
      const msg = String(
        (inv as { message?: string }).message ??
          (Array.isArray(inv) ? "Gateway menolak transaksi" : "Gateway menolak transaksi"),
      );
      await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}`, {
        method: "PATCH",
        body: JSON.stringify({ status: "failed" }),
      });
      return json({ ok: 0, e: msg }, 200);
    }
  } catch (e) {
    return json({ ok: 0, e: "Tidak bisa menghubungi Xendit: " + (e instanceof Error ? e.message : "gagal") }, 200);
  }

  const url = String(inv.invoice_url ?? "");
  if (!url) return json({ ok: 0, e: "Xendit tidak memberi alamat pembayaran" }, 200);
  await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}`, {
    method: "PATCH",
    body: JSON.stringify({ token: String(inv.id ?? "") }),
  });
  return json({ ok: 1, order: orderId, url, coins: Number(coins), amount });
}

async function xenditNotify(req: Request): Promise<Response> {
  const token = xenditCallbackToken();
  if (!token) return json({ ok: 0, e: "Callback token belum diatur" }, 200);
  const got = req.headers.get("x-callback-token") ?? "";
  if (got !== token) return json({ ok: 0, e: "Token callback salah" }, 401);

  let b: Record<string, unknown>;
  try {
    b = await req.json();
  } catch {
    return json({ ok: 0, e: "Body tidak valid" }, 200);
  }
  const orderId = String(b.external_id ?? "");
  const status = String(b.status ?? "").toUpperCase();
  const paidAmount = Number(b.paid_amount ?? b.amount ?? 0);
  if (!orderId) return json({ ok: 0, e: "Data tidak lengkap" }, 200);

  const r = await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}&select=*`);
  const rows = r.ok ? await r.json().catch(() => null) : null;
  const order = Array.isArray(rows) && rows.length ? rows[0] : null;
  if (!order) return json({ ok: 0, e: "Pesanan tidak ada" }, 200);

  if (status === "PAID" || status === "SETTLED") {
    if (Math.round(Number(order.amount)) !== Math.round(paidAmount)) {
      return json({ ok: 0, e: "Nominal tidak cocok" }, 200);
    }
    const okCredit = await creditOrder(order);
    return json({ ok: okCredit ? 1 : 0 }, 200);
  }
  if (status === "EXPIRED" || status === "FAILED") {
    await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}&status=eq.pending`, {
      method: "PATCH",
      body: JSON.stringify({ status: status === "EXPIRED" ? "expired" : "failed" }),
    });
  }
  return json({ ok: 1 }, 200);
}

async function midtransNotify(req: Request): Promise<Response> {
  const key = midtransKey();
  if (!key) return json({ ok: 0, e: "Gateway belum diaktifkan" }, 200);
  let b: Record<string, unknown>;
  try {
    b = await req.json();
  } catch {
    return json({ ok: 0, e: "Body tidak valid" }, 200);
  }
  const orderId = String(b.order_id ?? "");
  const statusCode = String(b.status_code ?? "");
  const gross = String(b.gross_amount ?? "");
  const sig = String(b.signature_key ?? "");
  const state = String(b.transaction_status ?? "");
  const fraud = String(b.fraud_status ?? "");
  if (!orderId || !sig) return json({ ok: 0, e: "Data tidak lengkap" }, 200);

  const expect = await sha512(orderId + statusCode + gross + key);
  if (expect !== sig) return json({ ok: 0, e: "Tanda tangan tidak cocok" }, 401);

  const r = await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}&select=*`);
  const rows = r.ok ? await r.json().catch(() => null) : null;
  const order = Array.isArray(rows) && rows.length ? rows[0] : null;
  if (!order) return json({ ok: 0, e: "Pesanan tidak ada" }, 200);

  const paid = (state === "capture" || state === "settlement") && fraud !== "challenge";
  if (paid) {
    if (Math.round(Number(order.amount)) !== Math.round(Number(gross))) {
      return json({ ok: 0, e: "Nominal tidak cocok" }, 200);
    }
    const okCredit = await creditOrder(order);
    return json({ ok: okCredit ? 1 : 0 }, 200);
  }
  if (state === "deny" || state === "cancel" || state === "expire" || state === "failure") {
    await sbRest(`topup_orders?id=eq.${encodeURIComponent(orderId)}&status=eq.pending`, {
      method: "PATCH",
      body: JSON.stringify({ status: state === "expire" ? "expired" : "failed" }),
    });
  }
  return json({ ok: 1 }, 200);
}


// ================= TOPUP MANUAL DANA =================
// Tanpa payment gateway: user transfer ke DANA admin, admin setujui di panel.
// Coin hanya masuk saat admin menekan Setujui (w_manual_decide -> w_topup).

async function payInfo(): Promise<Response> {
  const r = await rpc("w_pay_info", {});
  const v = r.ok ? await r.json().catch(() => ({})) : {};
  return json({ ok: 1, pay: v ?? {} });
}

async function manualCreate(uid: string, body: Record<string, unknown>): Promise<Response> {
  const coins = String(body.coins ?? "").replace(/[^0-9]/g, "");
  const amount = TOPUP_PACKS[coins];
  if (!amount) return json({ ok: 0, e: "Paket coin tidak dikenal" }, 200);
  const payer = String(body.payer ?? "").trim();
  if (payer.length < 3) return json({ ok: 0, e: "Isi nama pengirim" }, 200);
  const method = String(body.method ?? "dana").toLowerCase();
  if (!["dana", "gopay", "qris"].includes(method)) return json({ ok: 0, e: "Metode tidak dikenal" }, 200);
  const id = "MN" + Date.now().toString(36).toUpperCase() + Math.random().toString(36).slice(2, 5).toUpperCase();
  let r = await rpc("w_manual_create_v2", {
    p_id: id,
    p_uid: uid,
    p_coins: Number(coins),
    p_amount: amount,
    p_payer: payer.slice(0, 40),
    p_method: method,
  });
  if (!r.ok) {
    // fungsi versi lama (belum dijalankan SQL terbaru) -> tetap simpan pengajuan
    r = await rpc("w_manual_create", {
      p_id: id,
      p_uid: uid,
      p_coins: Number(coins),
      p_amount: amount,
      p_payer: payer.slice(0, 40),
    });
  }
  if (!r.ok) {
    let msg = "Gagal mengajukan topup";
    try {
      const j = await r.json();
      msg = String(j.message ?? msg).replace(/^.*?:\s*/, "");
    } catch { /* biarkan default */ }
    return json({ ok: 0, e: msg }, 200);
  }
  return json({ ok: 1, order: id, status: "pending" });
}

async function manualHistory(uid: string): Promise<Response> {
  const r = await rpc("w_manual_history", { p_uid: uid, p_limit: 20 });
  const rows = r.ok ? await r.json().catch(() => []) : [];
  return json({ ok: 1, list: rows });
}

async function topupStatus(uid: string, body: Record<string, unknown>): Promise<Response> {
  const id = String(body.order ?? "");
  const r = await sbRest(
    `topup_orders?id=eq.${encodeURIComponent(id)}&uid=eq.${encodeURIComponent(uid)}&select=id,coins,amount,status,created_at`,
  );
  const rows = r.ok ? await r.json().catch(() => null) : null;
  const o = Array.isArray(rows) && rows.length ? rows[0] : null;
  if (!o) return json({ ok: 0, e: "Pesanan tidak ditemukan" }, 200);
  return json({ ok: 1, order: o });
}

async function topupHistory(uid: string): Promise<Response> {
  const r = await rpc("w_topup_list", { p_uid: uid, p_limit: 30 });
  const rows = r.ok ? await r.json().catch(() => []) : [];
  return json({ ok: 1, list: rows });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const wpath = new URL(req.url).pathname.replace(/\/+$/, "").toLowerCase();
  // Webhook pembayaran: tanpa token login, keaslian dicek lewat signature Midtrans.
  if (wpath.includes("midtrans")) return await midtransNotify(req);
  if (wpath.includes("xendit")) return await xenditNotify(req);
  if (wpath.includes("userinfo")) return await waveUserInfo(req);
  if (wpath.includes("submitflow")) return await waveSubmitFlow(req);
  if (req.method !== "POST") return json({ ok: 0, e: "Method tidak didukung" }, 405);

  let body: { a?: string; t?: string; p?: Record<string, unknown> };
  try {
    body = await req.json();
  } catch {
    return json({ ok: 0, e: "Permintaan tidak valid" }, 400);
  }

  const action = body.a ?? "";
  if (action === "admin") return await handleAdmin(body);
  // Aksi topup manual/gateway ditangani langsung di bawah, jadi tidak wajib
  // punya padanan fungsi SQL di ACTIONS. Tanpa pengecualian ini, semua
  // permintaan topup ditolak "Permintaan tidak valid" sebelum diproses.
  const DIRECT = new Set([
    "topup_create",
    "pay_info",
    "manual_create",
    "manual_history",
    "topup_status",
    "topup_history",
    "rocket_claim",
    "gift_cashback",
  ]);
  const fn = ACTIONS[action];
  if ((!fn && !DIRECT.has(action)) || !body.t) {
    return json({ ok: 0, e: "Permintaan tidak valid" }, 400);
  }

  let uid: string;
  try {
    uid = await verifyFirebase(body.t);
  } catch (e) {
    return json({ ok: 0, e: e instanceof Error ? e.message : "Token tidak valid" }, 401);
  }

  const p = { ...(body.p ?? {}) };
  if ("uid" in p) delete p.uid; // uid selalu dari token, tidak bisa dipalsukan
  // Daftar penerima gift + nilai per penerima (diamond yang harus masuk ke mereka).
  const giftTo: string[] = Array.isArray(p.to) ? (p.to as unknown[]).map((x) => String(x)) : [];
  const giftEach = Number(p.each ?? 0);

  // Topup lewat payment gateway (tidak memakai RPC SQL langsung).
  if (action === "topup_create") return await topupCreate(uid, p);
  if (action === "pay_info") return await payInfo();
  if (action === "manual_create") return await manualCreate(uid, p);
  if (action === "manual_history") return await manualHistory(uid);
  if (action === "topup_status") return await topupStatus(uid, p);
  if (action === "topup_history") return await topupHistory(uid);
  if (action === "rocket_claim") return await rocketClaim(uid, p);
  if (action === "gift_cashback") return await giftCashback(uid, p);
  // Nama parameter HARUS persis dengan definisi fungsi SQL (p_*).
  let params: Record<string, unknown>;
  switch (action) {
    case "migrate":
      params = {
        p_uid: uid, p_coins: p.coins, p_diamonds: p.diamonds, p_exp: p.exp,
        p_vip: p.vip, p_vip_until: p.vipUntil, p_lv_claimed: p.lvClaimed,
        p_inv: p.inv ?? {}, p_equip: p.equip ?? {},
      };
      break;
    case "wave_prepare":
      params = { p_uid: uid, p_name: p.name ?? "Player", p_avatar: p.avatar ?? "" };
      break;
    case "topup":
      params = { p_uid: uid, p_coins: p.coins };
      break;
    case "send_gift":
      params = { p_uid: uid, p_price: p.price, p_item_id: p.item_id ?? null, p_room_id: p.room_id ?? null };
      break;
    case "exchange":
      // p_req = nomor permintaan dari app, supaya penukaran yang diulang
      // (misal karena jaringan putus) tidak memotong berlian dua kali.
      params = { p_uid: uid, p_diamonds: p.diamonds };
      if (p.req) params.p_req = String(p.req);
      break;
    case "buy_vip":
      params = { p_uid: uid, p_tier: p.tier };
      break;
    case "buy_item":
      params = { p_uid: uid, p_item_id: p.item_id, p_price: p.price, p_days: p.days };
      break;
    case "equip":
      params = { p_uid: uid, p_slot: p.slot, p_id: p.id };
      break;
    default:
      params = { p_uid: uid };
  }

  try {
    let r = await rpc(fn, params);
    let text = await r.text();
    // Kalau database belum memakai versi fungsi bertanda p_req, ulangi tanpa p_req.
    if (!r.ok && params.p_req && /Could not find the function|does not exist|PGRST202/i.test(text)) {
      delete params.p_req;
      r = await rpc(fn, params);
      text = await r.text();
    }
    if (!r.ok) {
      // WALLET_NOT_FOUND (kode 404/P0001) -> app akan migrasi dari Firestore.
      let msg = "Server database sibuk, coba lagi";
      let code = "";
      try {
        const j = JSON.parse(text);
        code = j.code ?? "";
        msg = j.message ?? msg;
      } catch { /* biarkan default */ }
      return json({ ok: 0, e: msg, code }, r.status === 400 ? 200 : r.status);
    }
    const data = text ? JSON.parse(text) : null;
    if (action === "send_gift" && giftTo.length > 0 && giftEach > 0) {
      const credited = await giftCredit(giftTo, giftEach, String(p.item_id ?? ""), String(p.room_id ?? ""));
      return json({ ok: 1, w: data, credited });
    }
    return json({ ok: 1, w: data });
  } catch {
    return json({ ok: 0, e: "Tidak bisa menghubungi database, coba lagi" }, 200);
  }
});
