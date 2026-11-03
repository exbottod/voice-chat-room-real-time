// =====================================================================
//  REZO x Hive Play: hive-api (Supabase Edge Function)
//  Tiga callback yang diminta Hive Play (lihat doc.html bagian 5.2):
//    1) /play/get_sstoken    -> code  => ss_token
//    2) /play/get_user_info  -> ss_token => profil + saldo
//    3) /play/change_balance -> bet/win/refund => saldo baru
//  Deploy dengan nama fungsi: hive-api   (Verify JWT: OFF)
//  URL yang dipakai di console Hive:
//    https://<PROJECT>.supabase.co/functions/v1/hive-api/play/get_sstoken
//    https://<PROJECT>.supabase.co/functions/v1/hive-api/play/get_user_info
//    https://<PROJECT>.supabase.co/functions/v1/hive-api/play/change_balance
//  ENV opsional: HIVE_APP_ID, HIVE_APP_KEY, HIVE_CODE_SALT
// =====================================================================

const APP_ID = Deno.env.get("HIVE_APP_ID") ?? "100198";
const APP_KEY = Deno.env.get("HIVE_APP_KEY") ?? "eb09c56d97454d43ab2698ad548697ab";
const CODE_SALT = Deno.env.get("HIVE_CODE_SALT") ?? "rezo-hive-2026";
const MAX_SKEW_SEC = 300; // toleransi jam; Hive menolak > 15 detik di sisi mereka

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

// ---------- util ----------
async function md5hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("MD5", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function ok(data: unknown) {
  return json({
    code: 0,
    message: "succeed",
    unique_id: "rezo_" + crypto.randomUUID(),
    data,
  });
}

function fail(message: string, code = 500) {
  return json({ code, message, unique_id: "rezo_" + crypto.randomUUID(), data: null });
}

async function rpc<T>(fn: string, args: Record<string, unknown>): Promise<T> {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      apikey: SERVICE_KEY,
      Authorization: `Bearer ${SERVICE_KEY}`,
    },
    body: JSON.stringify(args),
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`RPC ${fn} gagal: ${r.status} ${text}`);
  return (text ? JSON.parse(text) : null) as T;
}

// ---------- token ----------
// ss_token = base64url(uid) + "." + md5(uid + app_key)
async function makeSsToken(uid: string): Promise<string> {
  const b = btoa(uid).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b}.${await md5hex(uid + APP_KEY)}`;
}

async function readSsToken(token: unknown): Promise<string | null> {
  const s = String(token ?? "");
  const i = s.lastIndexOf(".");
  if (i < 1) return null;
  const b = s.slice(0, i);
  const sig = s.slice(i + 1);
  let uid = "";
  try {
    const pad = b.length % 4 ? "=".repeat(4 - (b.length % 4)) : "";
    uid = atob(b.replace(/-/g, "+").replace(/_/g, "/") + pad);
  } catch {
    return null;
  }
  if (!uid) return null;
  return (await md5hex(uid + APP_KEY)) === sig ? uid : null;
}

// code dari app: "<uid>" polos, atau "<uid>.<expDetik>.<md5(uid.exp.salt)>"
async function readCode(code: unknown): Promise<string | null> {
  const s = String(code ?? "").trim();
  if (!s) return null;
  const parts = s.split(".");
  if (parts.length === 1) return parts[0]; // fallback: uid polos
  if (parts.length !== 3) return null;
  const [uid, exp, sig] = parts;
  if (!uid || !exp || !sig) return null;
  if (await md5hex(`${uid}.${exp}.${CODE_SALT}`) !== sig) return null;
  const expMs = Number(exp) * 1000;
  if (Number.isFinite(expMs) && expMs > 0 && Date.now() > expMs) return null;
  return uid;
}

// ---------- handler ----------
Deno.serve(async (req) => {
  try {
    if (req.method !== "POST") return fail("POST only", 405);
    const path = new URL(req.url).pathname;

    let body: Record<string, unknown> = {};
    try {
      body = await req.json();
    } catch {
      return fail("body bukan JSON", 400);
    }

    // verifikasi signature: md5(signature_nonce + app_key + timestamp)
    const nonce = String(body.signature_nonce ?? "");
    const ts = Number(body.timestamp ?? 0);
    const sig = String(body.signature ?? "");
    if (!nonce || !ts || !sig) return fail("signature tidak lengkap", 400);
    if (Math.abs(Date.now() / 1000 - ts) > MAX_SKEW_SEC) return fail("timestamp kadaluarsa", 400);
    if (await md5hex(nonce + APP_KEY + ts) !== sig) return fail("signature salah", 400);

    if (String(body.app_id ?? "") !== String(APP_ID)) return fail("app_id tidak dikenal", 400);

    // 1) code -> ss_token
    if (path.endsWith("/get_sstoken")) {
      const uid = await readCode(body.code);
      if (!uid) return fail("code tidak valid", 400);
      return ok({
        ss_token: await makeSsToken(uid),
        expire_date: Date.now() + 365 * 24 * 3600 * 1000,
      });
    }

    // 2) ss_token -> profil + saldo
    if (path.endsWith("/get_user_info")) {
      const uid = await readSsToken(body.ss_token);
      if (!uid) return fail("ss_token tidak valid", 401);
      if (String(body.user_id ?? "") && String(body.user_id) !== uid) {
        return fail("user_id tidak cocok", 401);
      }
      const rows = await rpc<Array<{ id: string; name: string; balance: number; avatar: string }>>(
        "w_wave_player",
        { p_uid: uid },
      );
      const row = rows?.[0];
      return ok({
        user_id: uid,
        user_name: row?.name ?? "Player",
        user_avatar: row?.avatar ?? "",
        balance: Number(row?.balance ?? 0),
      });
    }

    // 3) perubahan saldo (bet / win / refund)
    if (path.endsWith("/change_balance")) {
      const uid = await readSsToken(body.ss_token);
      if (!uid) return fail("ss_token tidak valid", 401);

      const orderId = String(body.order_id ?? "");
      if (!orderId) return fail("order_id kosong", 400);

      const diff = Math.trunc(Number(body.currency_diff ?? 0));
      if (!Number.isFinite(diff)) return fail("currency_diff tidak valid", 400);

      // diff 0 = tidak ada perubahan, cukup kembalikan saldo
      if (diff === 0) {
        const rows = await rpc<Array<{ balance: number }>>("w_wave_player", { p_uid: uid });
        return ok({ currency_balance: Number(rows?.[0]?.balance ?? 0) });
      }

      const kind = diff < 0 ? 1 : 2; // 1 = potong (bet), 2 = tambah (menang/refund)
      const amount = Math.abs(diff);
      const gameName = `hive:${String(body.game_id ?? "")}`;

      const rows = await rpc<Array<{ coins: number; duplicated: boolean }>>("w_wave_flow", {
        p_uid: uid,
        p_amount: amount,
        p_kind: kind,
        p_tx: `hive_${orderId}`,
        p_game: gameName,
      });
      const row = rows?.[0];
      if (!row) return fail("gagal memperbarui saldo", 500);
      return ok({ currency_balance: Number(row.coins ?? 0) });
    }

    return fail("endpoint tidak dikenal", 404);
  } catch (e) {
    return fail(String((e as Error)?.message ?? e), 500);
  }
});
