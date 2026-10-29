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

async function adminWallet(uid: string): Promise<Record<string, unknown> | null> {
  const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(uid)}&select=*`);
  if (!r.ok) return null;
  const rows = await r.json().catch(() => null);
  return Array.isArray(rows) && rows.length ? rows[0] : null;
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
  if (op !== "reset_all" && !target) return json({ ok: 0, e: "uid kosong" }, 400);

  const num = (v: unknown) => {
    const n = Math.trunc(Number(v));
    return Number.isSafeInteger(n) ? n : NaN;
  };

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

  if (op === "reset") {
    const r = await sbRest(`wallets?uid=eq.${encodeURIComponent(target)}`, {
      method: "PATCH",
      body: JSON.stringify({ coins: 0, updated_at: new Date().toISOString() }),
    });
    if (!r.ok) return json({ ok: 0, e: "Gagal reset" }, 200);
    return json({ ok: 1, w: (await adminWallet(target)) });
  }

  if (op === "reset_all") {
    const r = await sbRest("wallets?coins=neq.0", {
      method: "PATCH",
      headers: { Prefer: "return=representation" },
      body: JSON.stringify({ coins: 0, updated_at: new Date().toISOString() }),
    });
    if (!r.ok) return json({ ok: 0, e: "Gagal reset semua" }, 200);
    const rows = await r.json().catch(() => []);
    return json({ ok: 1, count: Array.isArray(rows) ? rows.length : 0 });
  }

  return json({ ok: 0, e: "Operasi tidak dikenal" }, 400);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const wpath = new URL(req.url).pathname.replace(/\/+$/, "").toLowerCase();
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
  const fn = ACTIONS[action];
  if (!fn || !body.t) return json({ ok: 0, e: "Permintaan tidak valid" }, 400);

  let uid: string;
  try {
    uid = await verifyFirebase(body.t);
  } catch (e) {
    return json({ ok: 0, e: e instanceof Error ? e.message : "Token tidak valid" }, 401);
  }

  const p = { ...(body.p ?? {}) };
  if ("uid" in p) delete p.uid; // uid selalu dari token, tidak bisa dipalsukan
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
      params = { p_uid: uid, p_diamonds: p.diamonds };
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
    const r = await rpc(fn, params);
    const text = await r.text();
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
    return json({ ok: 1, w: data });
  } catch {
    return json({ ok: 0, e: "Tidak bisa menghubungi database, coba lagi" }, 200);
  }
});
