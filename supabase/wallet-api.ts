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
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("Token tidak valid");
  const header = b64urlJson(parts[0]);
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

  const p = b64urlJson(parts[1]);
  const now = Math.floor(Date.now() / 1000);
  if (p.aud !== FIREBASE_PROJECT_ID) throw new Error("Token tidak valid");
  if (p.iss !== `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`) throw new Error("Token tidak valid");
  if (typeof p.exp !== "number" || p.exp <= now) throw new Error("Sesi habis, buka ulang aplikasinya");
  if (typeof p.sub !== "string" || !p.sub) throw new Error("Token tidak valid");
  return p.sub;
}

const ACTIONS: Record<string, string> = {
  get: "w_get",
  migrate: "w_migrate",
  topup: "w_topup",
  send_gift: "w_send_gift",
  exchange: "w_exchange",
  buy_vip: "w_buy_vip",
  buy_svip: "w_buy_svip",
  buy_item: "w_buy_item",
  equip: "w_equip",
  claim_level: "w_claim_level",
  backfill: "w_backfill_vip",
};

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
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

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ ok: 0, e: "Method tidak didukung" }, 405);

  let body: { a?: string; t?: string; p?: Record<string, unknown> };
  try {
    body = await req.json();
  } catch {
    return json({ ok: 0, e: "Permintaan tidak valid" }, 400);
  }

  const action = body.a ?? "";
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
    case "buy_svip":
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
