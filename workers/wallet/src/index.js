/**
 * Dompet REZO di Cloudflare Workers + Turso.
 *
 * Menggantikan smart-processor Supabase. Bentuk jawabannya sama persis
 * ({"ok":1,"w":{...}} atau {"ok":0,"e":"..."}), jadi aplikasi tidak perlu
 * diubah selain alamat server.
 *
 * Semua operasi uang memakai satu transaksi SQLite (BEGIN IMMEDIATE) dan
 * penanda permintaan, jadi permintaan yang terkirim dua kali tidak
 * memotong coin dua kali.
 */

const PROJ = "arboreal-cat-511705-p6"; // project Firebase (aud token)

// ---------- Turso lewat HTTP pipeline ----------
const T = (v) => ({ type: "text", value: String(v) });
const I = (v) => ({ type: "integer", value: String(Math.trunc(Number(v) || 0)) });

async function pipe(env, stmts) {
  const body = {
    requests: [
      ...stmts.map((s) => ({
        type: "execute",
        stmt: { sql: s.sql, args: (s.args ?? []).map((a) => (a?.type ? a : T(a))) },
      })),
      { type: "close" },
    ],
  };
  const r = await fetch(`${env.TURSO_URL}/v2/pipeline`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.TURSO_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  const j = await r.json().catch(() => null);
  if (!r.ok || !j?.results) throw new Error(`Turso ${r.status}`);
  const out = [];
  let err = "";
  for (const res of j.results) {
    if (res.type === "error") {
      err = String(res.error?.message ?? "turso error");
      out.push({ rows: [], affected: 0 });
      continue;
    }
    const r2 = res.response?.result ?? {};
    const cols = (r2.cols ?? []).map((c) => c.name);
    const rows = (r2.rows ?? []).map((row) => {
      const o = {};
      row.forEach((cell, i) => {
        const v = cell?.value;
        o[cols[i]] = cell?.type === "integer" || cell?.type === "float" ? Number(v) : v;
      });
      return o;
    });
    out.push({ rows, affected: Number(r2.affected_row_count ?? 0) });
  }
  if (err) throw new Error(err);
  return out;
}

// ---------- Verifikasi token login Firebase ----------
let jwks = { keys: [], at: 0 };

async function kunci(kid) {
  const now = Date.now();
  if (!jwks.keys.length || now - jwks.at > 3600_000) {
    const r = await fetch(
      "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com",
    );
    const j = await r.json();
    jwks = { keys: j.keys ?? [], at: now };
  }
  return jwks.keys.find((k) => k.kid === kid) ?? null;
}

function b64d(s) {
  const n = s.replace(/-/g, "+").replace(/_/g, "/");
  const p = n + "=".repeat((4 - (n.length % 4)) % 4);
  return Uint8Array.from(atob(p), (c) => c.charCodeAt(0));
}

/** Kembalikan {uid, email} kalau token sah, null kalau tidak. */
async function sesiDariToken(token) {
  try {
    const [h, c, s] = token.split(".");
    if (!h || !c || !s) return null;
    const head = JSON.parse(new TextDecoder().decode(b64d(h)));
    const jwk = await kunci(head.kid);
    if (!jwk) return null;
    const key = await crypto.subtle.importKey(
      "jwk",
      { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
    const ok = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      key,
      b64d(s),
      new TextEncoder().encode(`${h}.${c}`),
    );
    if (!ok) return null;
    const body = JSON.parse(new TextDecoder().decode(b64d(c)));
    const now = Math.floor(Date.now() / 1000);
    if (String(body.aud) !== PROJ) return null;
    if (String(body.iss) !== `https://securetoken.google.com/${PROJ}`) return null;
    if (Number(body.exp ?? 0) <= now) return null;
    const uid = String(body.sub ?? body.user_id ?? "");
    if (!uid) return null;
    return { uid, email: String(body.email ?? "") };
  } catch {
    return null;
  }
}

/** uid saja, dipakai jalur dompet. */
const uidDariToken = async (token) => (await sesiDariToken(token))?.uid ?? null;

// ---------- Bentuk jawaban ----------
const ok = (w) => json({ ok: 1, w: w ?? null });
const gagal = (e) => json({ ok: 0, e });
// Panel developer (exbottod.github.io) memanggil dari browser, jadi wajib ada header CORS.
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, content-type, apikey, x-client-info, accept",
  "Access-Control-Max-Age": "86400",
};
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });

const KOLOM =
  "uid, coins, diamonds, exp, vip, vip_until, lv_claimed, inv, equip";

function rapikan(r) {
  if (!r) return null;
  let inv = {}, eq = {};
  try { inv = JSON.parse(r.inv || "{}"); } catch { /* biarkan kosong */ }
  try { eq = JSON.parse(r.equip || "{}"); } catch { /* biarkan kosong */ }
  return {
    uid: r.uid,
    coins: r.coins ?? 0,
    diamonds: r.diamonds ?? 0,
    exp: r.exp ?? 0,
    vip: r.vip ?? 0,
    vipUntil: r.vip_until ?? 0,
    lvClaimed: r.lv_claimed ?? 0,
    inv,
    equip: eq,
  };
}

const saldo = (env, uid) =>
  pipe(env, [{ sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] }]);

async function pastikan(env, uid) {
  const r = await saldo(env, uid);
  if (r[0].rows.length) return rapikan(r[0].rows[0]);
  await pipe(env, [
    { sql: "insert or ignore into wallets (uid) values (?)", args: [T(uid)] },
  ]);
  const r2 = await saldo(env, uid);
  return rapikan(r2[0].rows[0]);
}

// ---------- Aksi dompet ----------
async function kirimGift(env, uid, p) {
  await pastikan(env, uid);
  const price = Math.trunc(Number(p.price ?? 0));
  if (price <= 0) return gagal("Gift tidak valid");
  const req = String(p.req ?? `g-${uid}-${Date.now()}`);
  const now = Date.now();
  const item = p.itemId != null ? String(p.itemId) : null;
  const room = p.roomId != null ? String(p.roomId) : null;

  // Satu transaksi: potong coin, tambah diamond dan exp (bonus VIP),
  // lalu catat. Penanda req membuat pengulangan tidak memotong dua kali.
  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set
              coins      = coins - ?,
              diamonds   = diamonds + ?,
              exp        = exp + (? * (case when vip between 1 and 9 and vip_until > ?
                                            then 100 + 5 * vip else 100 end)) / 100,
              updated_at = ?
            where uid = ? and coins >= ?
              and not exists (select 1 from gift_req where req_id = ?)`,
      args: [I(price), I(price), I(price), I(now), I(now), T(uid), I(price), T(req)],
    },
    {
      sql: "insert into gift_req (req_id, uid, at) select ?, ?, ? where changes() > 0",
      args: [T(req), T(uid), I(now)],
    },
    {
      sql: `insert into money_tx (uid, kind, item_id, amount, room_id, created_at)
            select ?, 'gift', ?, ?, ?, ? where changes() > 0`,
      args: [T(uid), item, I(price), room, I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);

  const berubah = r[1].affected > 0;
  const w = rapikan(r[4].rows[0]);
  if (berubah) return ok(w);
  // Tidak berubah: bisa karena coin kurang atau permintaan sudah diproses.
  const cek = await pipe(env, [
    { sql: "select 1 as ada from gift_req where req_id = ?", args: [T(req)] },
  ]);
  if (cek[0].rows.length) return ok(w); // sudah pernah diproses: aman
  return gagal("Coin tidak cukup. Tekan Topup");
}

async function topup(env, uid, p) {
  await pastikan(env, uid);
  const coins = Math.trunc(Number(p.coins ?? 0));
  if (coins <= 0) return gagal("Jumlah tidak valid");
  const req = String(p.req ?? `t-${uid}-${Date.now()}`);
  const now = Date.now();
  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set coins = coins + ?, updated_at = ?
            where uid = ? and not exists (select 1 from topup_req where req_id = ?)`,
      args: [I(coins), I(now), T(uid), T(req)],
    },
    {
      sql: "insert into topup_req (req_id, uid, at) select ?, ?, ? where changes() > 0",
      args: [T(req), T(uid), I(now)],
    },
    {
      sql: `insert into money_tx (uid, kind, amount, created_at)
            select ?, 'topup', ?, ? where changes() > 0`,
      args: [T(uid), I(coins), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);
  return r[1].affected > 0 ? ok(rapikan(r[4].rows[0])) : ok(rapikan(r[4].rows[0]));
}

/** Hadiah cek in harian: sekali per tanggal, tidak bisa dobel. */
async function klaimSignin(env, uid, p) {
  const coins = Math.trunc(Number(p.coins ?? 0));
  const date = String(p.date ?? "").slice(0, 10);
  if (!(coins > 0) || coins > 500000000) return gagal("Jumlah hadiah cek in tidak valid");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return gagal("Tanggal cek in tidak valid");
  await pastikan(env, uid);
  const req = `signin-${uid}-${date}`;
  const now = Date.now();
  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set coins = coins + ?, updated_at = ?
            where uid = ? and not exists (select 1 from topup_req where req_id = ?)`,
      args: [I(coins), I(now), T(uid), T(req)],
    },
    {
      sql: "insert into topup_req (req_id, uid, at) select ?, ?, ? where changes() > 0",
      args: [T(req), T(uid), I(now)],
    },
    {
      sql: `insert into money_tx (uid, kind, amount, created_at)
            select ?, 'signin', ?, ? where changes() > 0`,
      args: [T(uid), I(coins), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);
  return ok(rapikan(r[4].rows[0]));
}

async function tukar(env, uid, p) {
  await pastikan(env, uid);
  const d = Math.trunc(Number(p.diamonds ?? 0));
  if (d <= 0) return gagal("Jumlah tidak valid");
  const req = String(p.req ?? `x-${uid}-${Date.now()}`);
  const now = Date.now();
  const gain = Math.floor(d / 2);
  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set diamonds = diamonds - ?, coins = coins + ?, updated_at = ?
            where uid = ? and diamonds >= ?
              and not exists (select 1 from exchange_req where req_id = ?)`,
      args: [I(d), I(gain), I(now), T(uid), I(d), T(req)],
    },
    {
      sql: "insert into exchange_req (req_id, uid, at) select ?, ?, ? where changes() > 0",
      args: [T(req), T(uid), I(now)],
    },
    {
      sql: `insert into money_tx (uid, kind, amount, created_at)
            select ?, 'tukar', ?, ? where changes() > 0`,
      args: [T(uid), I(gain), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);
  if (r[1].affected > 0) return ok(rapikan(r[4].rows[0]));
  const cek = await pipe(env, [
    { sql: "select 1 as ada from exchange_req where req_id = ?", args: [T(req)] },
  ]);
  if (cek[0].rows.length) return ok(rapikan(r[4].rows[0]));
  return gagal("Diamond tidak cukup");
}

/** Bet/win dari game Hive. kind 1 = bet (potong), 2 = win (tambah). */
async function hiveUang(env, uid, p) {
  await pastikan(env, uid);
  const kind = Math.trunc(Number(p.kind ?? 0));
  const amount = Math.trunc(Number(p.amount ?? 0));
  const oid = String(p.orderId ?? "");
  if (!oid || amount <= 0) return gagal("order tidak valid");
  const delta = kind === 1 ? -amount : amount;
  const now = Date.now();
  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set coins = coins + ?, updated_at = ?
            where uid = ? and (? > 0 or coins >= ?)
              and not exists (select 1 from hive_order where order_id = ?)`,
      args: [I(delta), I(now), T(uid), I(delta), I(amount), T(oid)],
    },
    {
      sql: `insert into hive_order (order_id, uid, kind, amount, round_id, game, at)
            select ?, ?, ?, ?, ?, ?, ? where changes() > 0`,
      args: [T(oid), T(uid), I(kind), I(amount), T(String(p.roundId ?? "")), T(String(p.game ?? "")), I(now)],
    },
    {
      sql: `insert into money_tx (uid, kind, amount, created_at)
            select ?, ?, ?, ? where changes() > 0`,
      args: [T(uid), kind === 1 ? T("hive_bet") : T("hive_win"), I(delta), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);
  if (r[1].affected > 0) return ok(rapikan(r[4].rows[0]));
  const cek = await pipe(env, [
    { sql: "select 1 as ada from hive_order where order_id = ?", args: [T(oid)] },
  ]);
  if (cek[0].rows.length) return ok(rapikan(r[4].rows[0])); // sudah diproses
  return gagal("Coin tidak cukup");
}


// ---------- Level dan item ----------
const expLevel = (n) => (n <= 1 ? 0 : Math.round((3932550000 * Math.pow(n / 39, 2.5)) / 10000) * 10000);

function levelDariExp(exp) {
  let l = 1;
  while (l < 250 && exp >= expLevel(l + 1)) l++;
  return l;
}

const HARGA_VIP = [0, 500000, 1500000, 3000000, 6000000, 12000000, 25000000, 50000000, 100000000, 250000000];
const BULAN = 30 * 86400000;
const AWALAN_VIP = ["vipt_", "vipf_", "vipb_", "vipe_", "vipbn_"];

/** Beli VIP: coin dipotong, item VIP 1..tier masuk My Item, level naik kalau lebih tinggi. */
async function beliVip(env, uid, p) {
  const tier = Math.trunc(Number(p.tier ?? 0));
  if (!(tier >= 1 && tier <= 9)) return gagal("Level VIP tidak valid");
  const harga = HARGA_VIP[tier];
  const now = Date.now();
  const req = String(p.req ?? `v-${uid}-${tier}-${now}`);
  const w0 = await pastikan(env, uid);
  if (w0.coins < harga) return gagal("Coin tidak cukup. Tekan Topup");

  const aktif = w0.vip >= 1 && w0.vip <= 9 && w0.vipUntil > now;
  const lama = Number(w0.inv?.[`vipt_${tier}`] ?? 0);
  const vuntil = (lama > now ? lama : now) + BULAN;

  const inv = { ...w0.inv };
  for (let i = 1; i <= tier; i++) for (const a of AWALAN_VIP) inv[`${a}${i}`] = vuntil;

  const eq = { ...w0.equip };
  let vip = w0.vip, vipUntil = w0.vipUntil;
  if (tier > w0.vip || !aktif) { vip = tier; vipUntil = vuntil; }
  else if (tier === w0.vip) { vipUntil = vuntil; }
  if (tier >= w0.vip || !aktif) {
    eq.frame = `vipf_${tier}`;
    if (!eq.bubble || /^vipb_/.test(eq.bubble)) eq.bubble = `vipb_${tier}`;
    if (!eq.entry || /^vipe_/.test(eq.entry)) eq.entry = `vipe_${tier}`;
    if (!eq.banner || /^vipbn_/.test(eq.banner)) eq.banner = `vipbn_${tier}`;
  }

  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set coins = coins - ?, vip = ?, vip_until = ?, inv = ?, equip = ?, updated_at = ?
            where uid = ? and coins >= ? and not exists (select 1 from vip_req where req_id = ?)`,
      args: [I(harga), I(vip), I(vipUntil), T(JSON.stringify(inv)), T(JSON.stringify(eq)), I(now), T(uid), I(harga), T(req)],
    },
    { sql: "insert into vip_req (req_id, uid, at) select ?, ?, ? where changes() > 0", args: [T(req), T(uid), I(now)] },
    {
      sql: `insert into money_tx (uid, kind, item_id, amount, created_at)
            select ?, 'buy_vip', ?, ?, ? where changes() > 0`,
      args: [T(uid), T("vip" + tier), I(harga), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);
  return ok(rapikan(r[4].rows[0]));
}

/** Beli item store: masa aktif ditambah dari sisa yang masih ada. */
async function beliItem(env, uid, p) {
  const id = String(p.itemId ?? "");
  const harga = Math.trunc(Number(p.price ?? 0));
  const hari = Math.trunc(Number(p.days ?? 0));
  if (!id) return gagal("Item tidak valid");
  if (hari <= 0) return gagal("Masa aktif item tidak valid");
  const now = Date.now();
  const w0 = await pastikan(env, uid);
  if (w0.coins < harga) return gagal("Coin tidak cukup. Tekan Topup");
  const lama = Number(w0.inv?.[id] ?? 0);
  const akhir = Math.max(now, lama) + hari * 86400000;
  const inv = { ...w0.inv, [id]: akhir };
  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set coins = coins - ?, inv = ?, updated_at = ?
            where uid = ? and coins >= ?`,
      args: [I(harga), T(JSON.stringify(inv)), I(now), T(uid), I(harga)],
    },
    {
      sql: `insert into money_tx (uid, kind, item_id, amount, created_at)
            select ?, 'buy_item', ?, ?, ? where changes() > 0`,
      args: [T(uid), T(id), I(harga), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);
  return ok(rapikan(r[3].rows[0]));
}

/** Pasang atau lepas item: id kosong berarti lepas. */
async function pasangItem(env, uid, p) {
  const slot = String(p.slot ?? "");
  const id = String(p.itemId ?? p.id ?? "");
  if (!slot) return gagal("Slot tidak valid");
  const now = Date.now();
  const w0 = await pastikan(env, uid);
  const eq = { ...w0.equip, [slot]: id };
  const r = await pipe(env, [
    {
      sql: "update wallets set equip = ?, updated_at = ? where uid = ?",
      args: [T(JSON.stringify(eq)), I(now), T(uid)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
  ]);
  return ok(rapikan(r[1].rows[0]));
}

/** Klaim hadiah level: tiap level baru memberi 243.000 x level. */
async function klaimLevel(env, uid) {
  const now = Date.now();
  const w0 = await pastikan(env, uid);
  const lvl = levelDariExp(w0.exp);
  if (lvl <= w0.lvClaimed) return gagal("Belum ada hadiah level untuk diklaim");
  let gain = 0;
  for (let l = w0.lvClaimed + 1; l <= lvl; l++) gain += l * 243000;
  // Dijaga oleh lv_claimed lama, jadi dua permintaan bersamaan tidak dua kali cair.
  const r = await pipe(env, [
    { sql: "begin immediate" },
    {
      sql: `update wallets set coins = coins + ?, lv_claimed = ?, updated_at = ?
            where uid = ? and lv_claimed = ?`,
      args: [I(gain), I(lvl), I(now), T(uid), I(w0.lvClaimed)],
    },
    {
      sql: `insert into money_tx (uid, kind, amount, created_at)
            select ?, 'claim_level', ?, ? where changes() > 0`,
      args: [T(uid), I(gain), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
    { sql: "commit" },
  ]);
  const w = rapikan(r[3].rows[0]);
  if (r[1].affected > 0) return ok(w);
  return gagal("Hadiah level sudah diklaim");
}

/** Lengkapi item VIP yang belum masuk My Item untuk VIP yang aktif. */
async function backfillVip(env, uid) {
  const now = Date.now();
  const w0 = await pastikan(env, uid);
  if (!(w0.vip >= 1 && w0.vip <= 9 && w0.vipUntil > now)) return ok(w0);
  const inv = { ...w0.inv };
  for (let i = 1; i <= w0.vip; i++)
    for (const a of AWALAN_VIP) {
      const k = `${a}${i}`;
      if (Number(inv[k] ?? 0) < w0.vipUntil) inv[k] = w0.vipUntil;
    }
  const r = await pipe(env, [
    { sql: "update wallets set inv = ?, updated_at = ? where uid = ?", args: [T(JSON.stringify(inv)), I(now), T(uid)] },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
  ]);
  return ok(rapikan(r[1].rows[0]));
}

/** Siapkan profil pemain untuk game (nama dan avatar dipakai Hive). */
async function wavePrepare(env, uid, p) {
  const nama = String(p.name ?? "").trim();
  const avatar = String(p.avatar ?? "");
  const now = Date.now();
  await pastikan(env, uid);
  const r = await pipe(env, [
    {
      sql: `insert into wave_players (uid, name, avatar, updated_at) values (?, ?, ?, ?)
            on conflict (uid) do update set
              name   = case when excluded.name <> '' then excluded.name else wave_players.name end,
              avatar = case when excluded.avatar <> '' then excluded.avatar else wave_players.avatar end,
              updated_at = excluded.updated_at`,
      args: [T(uid), T(nama || "Player"), T(avatar), I(now)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
  ]);
  return ok(rapikan(r[1].rows[0]));
}

// ---------- Pengaturan dari Realtime Database (tanpa build aplikasi) ----------
const RTDB = "https://arboreal-cat-511705-p6-default-rtdb.asia-southeast1.firebasedatabase.app";
const ADMIN_EMAIL = "developerex100@gmail.com";
const cache = new Map();

async function cfgRtdb(path) {
  const now = Date.now();
  const lama = cache.get(path);
  if (lama && now - lama.at < 60_000) return lama.data;
  try {
    const r = await fetch(`${RTDB}/${path}.json`);
    if (!r.ok) throw new Error("cfg " + r.status);
    const d = await r.json();
    const data = d && typeof d === "object" ? d : {};
    cache.set(path, { at: now, data });
    return data;
  } catch {
    return lama?.data ?? {};
  }
}

// ---------- Hadiah: coin, VIP, item VIP ----------
async function beriCoin(env, uid, coins, kind, itemId) {
  const n = Math.trunc(Number(coins) || 0);
  if (n <= 0) return 0;
  const now = Date.now();
  await pastikan(env, uid);
  await pipe(env, [
    { sql: "begin immediate" },
    { sql: "update wallets set coins = coins + ?, updated_at = ? where uid = ?", args: [I(n), I(now), T(uid)] },
    {
      sql: `insert into money_tx (uid, kind, item_id, amount, created_at) values (?, ?, ?, ?, ?)`,
      args: [T(uid), T(kind), itemId == null ? null : T(String(itemId)), I(n), I(now)],
    },
    { sql: "commit" },
  ]);
  return n;
}

async function beriVip(env, uid, tier, days) {
  const t = Math.trunc(Number(tier) || 0);
  if (!(t >= 1 && t <= 9) || days <= 0) return null;
  const now = Date.now();
  const w0 = await pastikan(env, uid);
  const aktif = w0.vip >= 1 && w0.vip <= 9 && w0.vipUntil > now;
  const vuntil = (aktif ? w0.vipUntil : now) + Math.trunc(Number(days)) * 86400000;
  const inv = { ...w0.inv };
  for (let i = 1; i <= t; i++) for (const a of AWALAN_VIP) inv[`${a}${i}`] = vuntil;
  const vip = Math.max(w0.vip, t);
  const r = await pipe(env, [
    {
      sql: "update wallets set vip = ?, vip_until = ?, inv = ?, updated_at = ? where uid = ?",
      args: [I(vip), I(vip >= w0.vip ? vuntil : w0.vipUntil), T(JSON.stringify(inv)), I(now), T(uid)],
    },
    { sql: `select ${KOLOM} from wallets where uid = ?`, args: [T(uid)] },
  ]);
  return rapikan(r[1].rows[0]);
}

// ---------- Roket ----------
const slotHadiah = (node, days) => {
  if (!node || typeof node !== "object") return null;
  const coins = Math.max(0, Math.floor(Number(node.coins ?? 0)));
  const vip = Math.max(0, Math.min(9, Math.floor(Number(node.vip ?? 0))));
  if (coins <= 0 && vip <= 0) return null;
  return { coins, vip, days: vip > 0 ? days : 0 };
};

function undiLevel(cfg, lv, days) {
  const node = cfg["lv" + lv];
  if (!node || typeof node !== "object") return null;
  const isi = ["r1", "r2", "r3", "r4", "r5", "r6"]
    .map((k) => slotHadiah(node[k] ?? node[k.replace("r", "p")], days))
    .filter(Boolean);
  if (!isi.length) return null;
  return isi[Math.floor(Math.random() * isi.length)];
}

function undiAny(cfg, levels, days) {
  for (const lv of levels) {
    const p = undiLevel(cfg, lv, days);
    if (p) return p;
  }
  return null;
}

/** Klaim hadiah roket: peluncur, penonton, atau papan peringkat. */
async function klaimRoket(env, uid, p) {
  const mode = String(p.mode ?? "launch");
  const cfg = await cfgRtdb("pub/_rocketCfg");
  let hadiah = null;
  if (mode === "rank") {
    const rank = Math.floor(Number(p.rank ?? 99));
    const key = rank >= 1 && rank <= 4 ? "t" + rank : "t4";
    hadiah = slotHadiah((cfg.rank ?? {})[key], 30);
    if (!hadiah) {
      if (rank === 1) hadiah = { coins: 100000000, vip: 9, days: 30 };
      else if (rank === 2) hadiah = { coins: 50000000, vip: 8, days: 30 };
      else if (rank === 3) hadiah = { coins: 10000000, vip: 5, days: 30 };
      else hadiah = { coins: 10000000, vip: 0, days: 0 };
    }
  } else if (mode === "lucky") {
    hadiah = undiAny(cfg, [1, 2, 3], 7) ?? { coins: 500000, vip: 0, days: 0 };
  } else {
    const lv = Math.max(1, Math.min(5, Math.floor(Number(p.lv ?? 5))));
    hadiah = undiLevel(cfg, lv, 30) ?? undiAny(cfg, [5, 4, 3, 2, 1], 30) ?? { coins: 10000000, vip: 0, days: 0 };
  }
  await beriCoin(env, uid, hadiah.coins, "rocket", null);
  if (hadiah.vip > 0 && hadiah.days > 0) await beriVip(env, uid, hadiah.vip, hadiah.days);
  const w = await pastikan(env, uid);
  return json({ ok: 1, coins: hadiah.coins, vip: hadiah.vip, days: hadiah.days, w, img: "", eff: "", badge: "" });
}

// ---------- Cashback gift ----------
const CB_LADDER = [10000, 9000, 8000, 6000, 4000, 3000, 2000, 1000, 888, 666, 500, 400, 300, 200, 100, 88, 66, 50, 30, 20, 10];

/** Bulatkan ke 2 angka penting (mis. 200.000.000 tetap, 173.456 -> 170.000). */
function bulat2(v) {
  if (!(v > 0)) return 0;
  const p = Math.pow(10, Math.max(0, Math.floor(Math.log10(v)) - 1));
  return Math.round(v / p) * p;
}

/**
 * Pecah total cashback menjadi beberapa angka yang JUMLAHNYA TEPAT sama dengan
 * total (mis. 100.000 x 10.000 = 1.000.000.000). Maksimal 5 pecahan supaya
 * animasinya ringan. Bagian terakhir menutup selisih pembulatan.
 */
function pecahCashback(total) {
  const n = Math.floor(total);
  if (!(n > 0)) return [];
  if (n <= 50000) {
    // Total kecil tetap memakai angka cantik yang tersedia di aplikasi.
    const out = [];
    let sisa = n, jaga = 0;
    while (sisa >= 10 && out.length < 5 && jaga++ < 16) {
      const v = CB_LADDER.find((x) => x <= sisa);
      if (!v) break;
      out.push(v);
      sisa -= v;
    }
    if (sisa > 0 && out.length) out[out.length - 1] += sisa;
    return out;
  }
  const jumlah = n >= 100000000 ? 3 : 5;
  const out = [];
  let sisa = n;
  for (let i = 0; i < jumlah - 1; i++) {
    const v = Math.max(10, bulat2(Math.floor(sisa / (jumlah - i))));
    out.push(v);
    sisa -= v;
  }
  out.push(sisa);
  return out.filter((v) => v > 0);
}

function undiBaris(rows) {
  const total = rows.reduce((a, b) => a + b.w, 0);
  if (total <= 0) return rows[rows.length - 1];
  let x = Math.random() * total;
  for (const r of rows) {
    x -= r.w;
    if (x <= 0) return r;
  }
  return rows[rows.length - 1];
}

function barisTier(cfg, value) {
  let best = [], minBest = -1;
  for (const k of ["t1", "t2", "t3", "t4"]) {
    const node = cfg[k];
    if (!node || typeof node !== "object") continue;
    const min = Math.floor(Number(node.min ?? 0));
    const rows = (Array.isArray(node.rows) ? node.rows : [])
      .map((r) => ({ v: Math.max(0, Math.floor(Number(r?.v ?? 0))), w: Math.max(0, Number(r?.w ?? 0)) }))
      .filter((r) => r.w > 0);
    if (!rows.length) continue;
    if (value >= min && min >= minBest) { best = rows; minBest = min; }
  }
  return best.length ? best : [{ v: 0, w: 100 }];
}

/** Undian cashback: pengirim dapat pengali, penerima dapat persen bonus. */
// Nilai cadangan kalau pengaturan panel tidak terbaca (mis. aturan database
// menolak akses tanpa login). Tanpa ini pengali jadi 0 dan pecahan cashback
// tidak pernah muncul di layar.
const CB_DEFAULT = {
  on: true,
  t1: { min: 0, rows: [{ v: 1, w: 60 }, { v: 2, w: 25 }, { v: 3, w: 10 }, { v: 5, w: 5 }] },
  recvPct: 5,
  jpMin: 1000,
};

async function cashbackGift(env, uid, p) {
  let cfg = await cfgRtdb("pub/_cashbackCfg");
  const adaTier = ["t1", "t2", "t3", "t4"].some((k) => cfg && typeof cfg[k] === "object" && cfg[k]);
  if (!adaTier) cfg = CB_DEFAULT;
  if (cfg.on === false) return json({ ok: 1, amount: 0, off: 1 });
  const price = Math.max(0, Math.floor(Number(p.price ?? 0)));
  const count = Math.max(1, Math.min(999, Math.floor(Number(p.count ?? 1))));
  const nilai = price * count;
  if (nilai <= 0) return gagal("Nilai gift tidak valid");

  const baris = undiBaris(barisTier(cfg, nilai));
  const mult = Math.max(0, Math.min(500, Math.floor(baris.v)));
  // Total cashback = harga gift x pengali dari panel, TANPA dipotong.
  // Pecahan hanya untuk animasi (aset angka maksimal 10.000), jadi isinya
  // potongan dari total dan jumlah akhirnya tetap total penuh.
  const target = Math.max(0, nilai * mult);
  const parts = pecahCashback(target);
  const amount = target;

  const paid = await beriCoin(env, uid, amount, "cashback", null);
  const pct = Math.max(0, Math.min(100, Number(cfg.recvPct ?? 5)));
  const to = String(p.to ?? "").trim();
  const bonus = to ? Math.floor((nilai * pct) / 100) : 0;
  const recv = bonus > 0 && to !== uid ? await beriCoin(env, to, bonus, "cashback_recv", null) : 0;
  const jpMin = Math.max(0, Math.floor(Number(cfg.jpMin ?? 1000)));
  return json({ ok: 1, amount: paid, mult, parts: parts.slice(0, 5), pecahan: parts[0] ?? baris.v,
    recv, jp: paid >= jpMin ? 1 : 0, asset: "" });
}

// ---------- Topup manual DANA ----------
async function infoBayar(env) {
  const r = await pipe(env, [{ sql: "select dana, nama, gopay, qris from pay_info where id = 1" }]);
  const d = r[0].rows[0] ?? {};
  return { dana: d.dana ?? "", name: d.nama ?? "", gopay: d.gopay ?? "", qris: d.qris ?? "" };
}

// Harga ditentukan server dari paket, bukan dari app, supaya tidak bisa dimurahkan.
const PAKET_TOPUP = { 1000000: 6000, 3000000: 18000, 10000000: 58000, 30000000: 170000 };

async function topupManualBuat(env, uid, p) {
  const id = String(p.id ?? `TM${Date.now()}${Math.floor(Math.random() * 1000)}`);
  const coins = Math.trunc(Number(String(p.coins ?? "").replace(/[^0-9]/g, "")));
  const amount = PAKET_TOPUP[coins];
  const payer = String(p.payer ?? "").trim().slice(0, 60);
  const method = String(p.method ?? "dana").trim() || "dana";
  if (!amount) return gagal("Paket coin tidak dikenal");
  if (payer.length < 3) return gagal("Nama pengirim terlalu pendek");
  const r = await pipe(env, [
    { sql: "select count(*) as n from topup_manual where uid = ? and status = 'pending'", args: [T(uid)] },
  ]);
  if (Number(r[0].rows[0]?.n ?? 0) >= 3) return gagal("Masih ada pengajuan yang menunggu persetujuan");
  await pipe(env, [
    {
      sql: `insert into topup_manual (id, uid, coins, amount, payer, method, status, created_at)
            values (?, ?, ?, ?, ?, ?, 'pending', ?)`,
      args: [T(id), T(uid), I(coins), I(amount), T(payer), T(method), I(Date.now())],
    },
  ]);
  return json({ ok: 1, order: id, id, status: "pending" });
}

async function topupManualRiwayat(env, uid) {
  const r = await pipe(env, [
    {
      sql: `select id, coins, amount, payer, method, status, created_at
            from topup_manual where uid = ? order by created_at desc limit 20`,
      args: [T(uid)],
    },
  ]);
  return json({ ok: 1, list: r[0].rows });
}

// ---------- Panel admin ----------
// ================= PANEL MANAGER & ADMIN =================
// Hak staf disimpan di Turso supaya bisa dicabut kapan saja dari panel
// developer, dan semua aksi saldo tetap dihitung server.

async function stafDari(env, uid) {
  if (!uid) return null;
  const r = await pipe(env, [
    { sql: "select uid, role, manager, active from panel_staff where uid = ?", args: [T(uid)] },
  ]);
  const row = r[0].rows[0];
  if (!row || Number(row.active) !== 1) return null;
  return { uid: String(row.uid), role: String(row.role), manager: String(row.manager ?? "") };
}

async function stokStaf(env, uid) {
  const r = await pipe(env, [
    {
      sql: `select item_id as id, kind, name, days from panel_stock
            where owner_uid = ? order by created_at desc limit 300`,
      args: [T(uid)],
    },
  ]);
  return r[0].rows.map((x) => ({ id: String(x.id), kind: String(x.kind ?? ""), name: String(x.name ?? ""), days: Number(x.days ?? 7) }));
}

async function panel(env, body) {
  const sesi = await sesiDariToken(body.t ?? "");
  if (!sesi) return json({ ok: 0, e: "Sesi tidak sah" }, 401);
  const uid = sesi.uid;
  const p = body.p ?? {};
  const aksi = String(p.a ?? p.op ?? "");
  const dev = sesi.email === ADMIN_EMAIL;

  if (aksi === "me") {
    const staf = await stafDari(env, uid);
    if (!staf) return json({ ok: 0, e: "Bukan staf", role: "" });
    return json({ ok: 1, role: staf.role, manager: staf.manager, stok: await stokStaf(env, uid) });
  }

  if (aksi === "ban_check") {
    const r = await pipe(env, [
      { sql: "select reason, oleh from panel_ban where uid = ?", args: [T(uid)] },
    ]);
    const row = r[0].rows[0];
    return json({ ok: 1, banned: !!row, reason: row ? String(row.reason ?? "") : "" });
  }

  const staf = await stafDari(env, uid);
  if (!staf && !dev) return json({ ok: 0, e: "Hanya manager atau admin" }, 403);
  const peran = dev ? "dev" : staf.role;
  const target = String(p.to ?? p.uid ?? "").trim();
  const butuhTarget = () => {
    if (!/^[A-Za-z0-9_-]{6,64}$/.test(target)) return "ID user tidak valid";
    return "";
  };

  if (aksi === "send_coin") {
    if (peran !== "manager" && peran !== "dev") return json({ ok: 0, e: "Hanya manager" }, 403);
    const salah = butuhTarget();
    if (salah) return gagal(salah);
    const coins = Math.trunc(Number(p.coins ?? 0));
    if (!(coins > 0) || coins > 100000000000) return gagal("Jumlah coin tidak valid");
    await beriCoin(env, target, coins, "panel_coin", null);
    const w = await pastikan(env, target);
    await pipe(env, [
      { sql: "insert into panel_log (id, oleh, aksi, target, jumlah, at) values (?, ?, 'send_coin', ?, ?, ?)",
        args: [T(`${uid}-${Date.now()}`), T(uid), T(target), I(coins), I(Date.now())] },
    ]).catch(() => {});
    return json({ ok: 1, coins: w.coins });
  }

  if (aksi === "send_vip") {
    const tier = Math.trunc(Number(p.tier ?? 0));
    const days = Math.trunc(Number(p.days ?? 30));
    if (!(tier >= 1 && tier <= 9)) return gagal("VIP harus 1 sampai 9");
    if (!(days > 0) || days > 3650) return gagal("Durasi VIP tidak valid");
    const salah = butuhTarget();
    if (salah) return gagal(salah);
    await beriVip(env, target, tier, days);
    return json({ ok: 1, vip: tier, days });
  }

  if (aksi === "send_item") {
    const itemId = String(p.itemId ?? "").trim();
    const salah = butuhTarget();
    if (salah) return gagal(salah);
    const stok = await stokStaf(env, uid);
    const ada = stok.find((x) => x.id === itemId);
    if (!ada && !dev) return gagal("Item ini tidak ada di stok panel Anda");
    const hari = Math.trunc(Number(p.days ?? ada?.days ?? 7));
    if (!(hari > 0) || hari > 3650) return gagal("Durasi item tidak valid");
    const w0 = await pastikan(env, target);
    const lama = Number(w0.inv?.[itemId] ?? 0);
    const akhir = Math.max(Date.now(), lama) + hari * 86400000;
    const inv = { ...w0.inv, [itemId]: akhir };
    await pipe(env, [
      { sql: "update wallets set inv = ?, updated_at = ? where uid = ?", args: [T(JSON.stringify(inv)), I(Date.now()), T(target)] },
    ]);
    return json({ ok: 1, itemId, days: hari });
  }

  if (aksi === "ban") {
    const salah = butuhTarget();
    if (salah) return gagal(salah);
    if (target === uid) return gagal("Tidak bisa banned diri sendiri");
    const korban = await stafDari(env, target);
    if (peran === "admin") {
      // Admin hanya boleh mengurus user biasa di bawah manajernya.
      if (!staf.manager) return gagal("Admin ini belum punya manager");
      if (korban) return gagal("Admin tidak bisa banned sesama staf atau manager");
    }
    if (korban && korban.role === "manager" && peran !== "dev") return gagal("Hanya developer yang bisa banned manager");
    await pipe(env, [
      {
        sql: `insert into panel_ban (uid, oleh, manager, reason, at) values (?, ?, ?, ?, ?)
              on conflict (uid) do update set oleh = excluded.oleh, manager = excluded.manager,
                reason = excluded.reason, at = excluded.at`,
        args: [T(target), T(uid), T(staf?.manager ?? ""), T(String(p.reason ?? "").slice(0, 140)), I(Date.now())],
      },
    ]);
    return json({ ok: 1, banned: target });
  }

  if (aksi === "unban") {
    const salah = butuhTarget();
    if (salah) return gagal(salah);
    await pipe(env, [{ sql: "delete from panel_ban where uid = ?", args: [T(target)] }]);
    return json({ ok: 1, unbanned: target });
  }

  if (aksi === "bans") {
    const r = await pipe(env, [
      { sql: "select uid, oleh, reason, at from panel_ban order by at desc limit 100" },
    ]);
    return json({ ok: 1, list: r[0].rows });
  }

  return json({ ok: 0, e: "Aksi panel tidak dikenal: " + aksi });
}

async function admin(env, body) {
  const sesi = await sesiDariToken(body.t ?? "");
  if (!sesi) return json({ ok: 0, e: "Token tidak valid" }, 401);
  if (sesi.email !== ADMIN_EMAIL) return json({ ok: 0, e: "Hanya admin" }, 403);
  const p = body.p ?? {};
  const op = String(p.op ?? "");
  const target = String(p.uid ?? "");

  // ---- Panel developer: atur panel manager/admin ----
  if (op === "panel_grant") {
    const who = String(p.uid ?? "").trim();
    const role = String(p.role ?? "").trim();
    if (!/^[A-Za-z0-9_-]{6,64}$/.test(who)) return gagal("UID tidak valid");
    if (role !== "manager" && role !== "admin") return gagal("Peran harus manager atau admin");
    const manager = role === "admin" ? String(p.manager ?? "").trim() : "";
    await pipe(env, [
      {
        sql: `insert into panel_staff (uid, role, manager, active, created_at) values (?, ?, ?, 1, ?)
              on conflict (uid) do update set role = excluded.role, manager = excluded.manager, active = 1`,
        args: [T(who), T(role), T(manager), I(Date.now())],
      },
    ]);
    return json({ ok: 1, uid: who, role, manager });
  }
  if (op === "panel_revoke") {
    const who = String(p.uid ?? "").trim();
    if (!who) return gagal("UID kosong");
    await pipe(env, [{ sql: "update panel_staff set active = 0 where uid = ?", args: [T(who)] }]);
    return json({ ok: 1, uid: who });
  }
  if (op === "panel_list") {
    const r = await pipe(env, [
      {
        sql: `select s.uid, s.role, s.manager, s.active, s.created_at,
                     (select count(*) from panel_stock k where k.owner_uid = s.uid) as stok
              from panel_staff s order by s.created_at desc limit 200`,
      },
    ]);
    return json({ ok: 1, list: r[0].rows });
  }
  if (op === "panel_stock_add") {
    const who = String(p.uid ?? "").trim();
    const itemId = String(p.itemId ?? p.item_id ?? "").trim();
    if (!who || !itemId) return gagal("UID dan item wajib diisi");
    await pipe(env, [
      {
        sql: `insert into panel_stock (owner_uid, item_id, kind, name, days, created_at) values (?, ?, ?, ?, ?, ?)
              on conflict (owner_uid, item_id) do update set kind = excluded.kind,
                name = excluded.name, days = excluded.days`,
        args: [T(who), T(itemId), T(String(p.kind ?? "")), T(String(p.name ?? "")), I(Math.trunc(Number(p.days ?? 7))), I(Date.now())],
      },
    ]);
    return json({ ok: 1, uid: who, itemId });
  }
  if (op === "panel_stock_del") {
    await pipe(env, [
      { sql: "delete from panel_stock where owner_uid = ? and item_id = ?", args: [T(String(p.uid ?? "")), T(String(p.itemId ?? ""))] },
    ]);
    return json({ ok: 1 });
  }
  if (op === "panel_stock_list") {
    const r = await pipe(env, [
      { sql: "select owner_uid, item_id, kind, name, days from panel_stock where owner_uid = ? order by created_at desc", args: [T(String(p.uid ?? ""))] },
    ]);
    return json({ ok: 1, list: r[0].rows });
  }
  if (op === "pay_get") return json({ ok: 1, pay: await infoBayar(env) });
  if (op === "pay_set") {
    await pipe(env, [
      {
        sql: `insert into pay_info (id, dana, nama, gopay, qris, updated_at) values (1, ?, ?, ?, ?, ?)
              on conflict (id) do update set dana = excluded.dana, nama = excluded.nama,
                gopay = excluded.gopay, qris = excluded.qris, updated_at = excluded.updated_at`,
        args: [T(p.dana ?? ""), T(p.name ?? ""), T(p.gopay ?? ""), T(p.qris ?? ""), I(Date.now())],
      },
    ]);
    return json({ ok: 1, pay: await infoBayar(env) });
  }
  if (op === "manual_pending" || op === "manual_all") {
    const semua = op === "manual_all";
    const r = await pipe(env, [
      {
        sql: `select id, uid, coins, amount, payer, method, status, created_at
              from topup_manual ${semua ? "" : "where status = 'pending'"}
              order by created_at desc limit ?`,
        args: [I(p.limit ?? 60)],
      },
    ]);
    return json({ ok: 1, list: r[0].rows });
  }
  if (op === "manual_decide") {
    const id = String(p.id ?? "");
    const setuju = p.ok === true || p.ok === "true";
    const cur = await pipe(env, [
      { sql: "select uid, coins, status from topup_manual where id = ?", args: [T(id)] },
    ]);
    const row = cur[0].rows[0];
    if (!row) return gagal("Pengajuan tidak ditemukan");
    if (row.status !== "pending") return gagal("Pengajuan sudah diproses");
    // Kunci baris dulu supaya klik ganda tidak menambah coin dua kali.
    const kunci = await pipe(env, [
      {
        sql: "update topup_manual set status = ?, decided_at = ? where id = ? and status = 'pending'",
        args: [T(setuju ? "ok" : "tolak"), I(Date.now()), T(id)],
      },
    ]);
    if (kunci[0].affected === 0) return gagal("Pengajuan sudah diproses");
    if (setuju) await beriCoin(env, String(row.uid), Number(row.coins), "topup", null);
    return json({ ok: 1, r: { id, uid: row.uid, coins: row.coins, status: setuju ? "ok" : "tolak" } });
  }
  if (op === "get") {
    const w = await pastikan(env, target);
    return json({ ok: 1, w });
  }
  if (op === "topup") {
    if (!target) return gagal("uid kosong");
    const coins = Math.trunc(Number(p.coins ?? 0));
    if (!(coins > 0)) return gagal("Jumlah coin tidak valid");
    await beriCoin(env, target, coins, "topup", null);
    return ok(await pastikan(env, target));
  }
  // Panel: kirim coin / exp, boleh minus untuk mengurangi.
  if (op === "add") {
    if (!target) return gagal("uid kosong");
    const coins = Math.trunc(Number(p.coins ?? 0));
    const exp = Math.trunc(Number(p.exp ?? 0));
    if (!coins && !exp) return gagal("Jumlah kosong");
    await pastikan(env, target);
    await pipe(env, [
      { sql: "begin immediate" },
      {
        sql: "update wallets set coins = max(0, coins + ?), exp = max(0, exp + ?), updated_at = ? where uid = ?",
        args: [I(coins), I(exp), I(Date.now()), T(target)],
      },
      { sql: "commit" },
    ]);
    return ok(await pastikan(env, target));
  }
  // Panel: reset coin (dan diamond kalau gem) untuk satu user / semua user.
  if (op === "reset" || op === "reset_all" || op === "reset_gem" || op === "reset_gem_all") {
    const gem = op.includes("gem");
    const semua = op.endsWith("_all");
    if (!semua && !target) return gagal("uid kosong");
    const kolom = gem ? "diamonds" : "coins";
    await pipe(env, [
      {
        sql: semua
          ? `update wallets set ${kolom} = 0, updated_at = ?`
          : `update wallets set ${kolom} = 0, updated_at = ? where uid = ?`,
        args: semua ? [I(Date.now())] : [I(Date.now()), T(target)],
      },
    ]);
    if (semua) return json({ ok: 1 });
    return ok(await pastikan(env, target));
  }

  // Panel: hapus akun. Akun resmi/admin/owner tidak boleh ikut terhapus.
  if (op === "delete_user" || op === "delete_users") {
    const daftar = op === "delete_user" ? [target] : (Array.isArray(p.uids) ? p.uids.map(String) : []);
    const lindungi = new Set([String(ADMIN_EMAIL).toLowerCase()]);
    if (Array.isArray(p.kecuali)) p.kecuali.forEach((x) => lindungi.add(String(x)));
    const hasil = [];
    for (const u of daftar) {
      if (!u) continue;
      if (lindungi.has(u.toLowerCase())) { hasil.push({ uid: u, lewat: 1 }); continue; }
      await pipe(env, [
        { sql: "delete from money_tx where uid = ?", args: [T(u)] },
        { sql: "delete from topup_manual where uid = ?", args: [T(u)] },
        { sql: "delete from hive_order where uid = ?", args: [T(u)] },
        { sql: "delete from wallets where uid = ?", args: [T(u)] },
      ]);
      hasil.push({ uid: u, hapus: 1 });
    }
    return json({ ok: 1, hasil });
  }

  // Panel: atur VIP (tier 0 = cabut).
  if (op === "vip") {
    if (!target) return gagal("uid kosong");
    const tier = Math.trunc(Number(p.tier ?? 0));
    const until = Math.trunc(Number(p.until ?? 0));
    if (!(tier >= 0 && tier <= 9)) return gagal("VIP harus 0 sampai 9");
    await pastikan(env, target);
    await pipe(env, [
      { sql: "begin immediate" },
      {
        sql: "update wallets set vip = ?, vip_until = ?, updated_at = ? where uid = ?",
        args: [I(tier), I(tier === 0 ? 0 : until), I(Date.now()), T(target)],
      },
      { sql: "commit" },
    ]);
    return ok(await pastikan(env, target));
  }
  return gagal("Perintah panel tidak dikenal");
}


// ================= HIVE PLAY (game pihak ketiga) =================
// Hive memanggil 3 alamat ini setiap pemain membuka game. Keaslian permintaan
// dicek lewat signature md5(signature_nonce + app_key + timestamp), karena Hive
// tidak membawa token login Firebase.
//   /hive/get_sstoken     -> code     => ss_token
//   /hive/get_user_info   -> ss_token => profil + saldo
//   /hive/change_balance  -> taruhan/menang => saldo terbaru
// Tanpa ini game Hive mentok di "error2" karena tidak bisa membaca saldo.
const HIVE_APP_ID = "100198";
const HIVE_KEY = "eb09c56d97454d43ab2698ad548697ab";
const HIVE_CODE_SALT = "rezo-hive-2026";
const HIVE_MAX_SKEW = 300;
const HIVE_MAX_SAFE = 9007199254740991;

function hiveSafe(n) {
  const v = Number(n ?? 0);
  if (!Number.isFinite(v) || v < 0) return 0;
  return v > HIVE_MAX_SAFE ? HIVE_MAX_SAFE : Math.trunc(v);
}

function md5hex(s) {
  const msg = new TextEncoder().encode(s);
  const len = msg.length;
  const words = [];
  for (let i = 0; i < len; i++) words[i >> 2] = (words[i >> 2] ?? 0) | (msg[i] << ((i % 4) * 8));
  words[len >> 2] = (words[len >> 2] ?? 0) | (0x80 << ((len % 4) * 8));
  const total = (((len + 8) >> 6) + 1) * 16;
  while (words.length < total) words.push(0);
  const bits = len * 8;
  words[total - 2] = bits & 0xffffffff;
  words[total - 1] = Math.floor(bits / 4294967296);

  const S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
             5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
             4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
             6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
  const K = [];
  for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0;

  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  const rotl = (x, c) => ((x << c) | (x >>> (32 - c))) | 0;

  for (let off = 0; off < total * 4; off += 64) {
    const M = [];
    for (let j = 0; j < 16; j++) M[j] = words[(off >> 2) + j] | 0;
    let A = a0, B = b0, C = c0, D = d0;
    for (let i = 0; i < 64; i++) {
      let F = 0, g = 0;
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i) % 16; }
      F = (F + A + K[i] + M[g]) | 0;
      A = D;
      D = C;
      C = B;
      B = (B + rotl(F, S[i])) | 0;
    }
    a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
  }

  const hex = (n) => {
    let out = "";
    for (let i = 0; i < 4; i++) out += ((n >>> (i * 8)) & 0xff).toString(16).padStart(2, "0");
    return out;
  };
  return hex(a0) + hex(b0) + hex(c0) + hex(d0);
}


const hiveOk = (data) =>
  json({ code: 0, message: "succeed", unique_id: "rezo_" + crypto.randomUUID(), data });
const hiveFail = (message, code = 500, data = null) =>
  json({ code, message, unique_id: "rezo_" + crypto.randomUUID(), data });

const b64urlEnc = (s) => btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

function hiveSsToken(uid) {
  return `${b64urlEnc(uid)}.${md5hex(uid + HIVE_KEY)}`;
}

function hiveReadToken(token) {
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
  return md5hex(uid + HIVE_KEY) === sig ? uid : null;
}

function hiveReadCode(code) {
  const s = String(code ?? "").trim();
  if (!s) return null;
  const parts = s.split(".");
  if (parts.length === 1) return parts[0];
  if (parts.length !== 3) return null;
  const [uid, exp, sig] = parts;
  if (!uid || !exp || !sig) return null;
  if (md5hex(`${uid}.${exp}.${HIVE_CODE_SALT}`) !== sig) return null;
  const expMs = Number(exp) * 1000;
  if (Number.isFinite(expMs) && expMs > 0 && Date.now() > expMs) return null;
  return uid;
}

async function hivePemain(env, uid) {
  const r = await pipe(env, [
    { sql: "select name, avatar from wave_players where uid = ?", args: [T(uid)] },
  ]);
  const row = r[0].rows[0] ?? {};
  const w = await pastikan(env, uid);
  return {
    id: uid,
    name: String(row.name ?? "").trim() || "Player",
    avatar: String(row.avatar ?? ""),
    balance: Number(w?.coins ?? 0),
  };
}

async function hivePlay(req, env) {
  const ep = new URL(req.url).pathname.toLowerCase();
  if (ep.includes("version")) {
    return json({ ok: 1, v: "hive-2026-11-03a", md5_abc: md5hex("abc") });
  }
  if (req.method !== "POST") return hiveFail("POST only", 405);
  let body = {};
  try { body = await req.json(); } catch { return hiveFail("body bukan JSON", 400); }

  const nonce = String(body.signature_nonce ?? "");
  const ts = Number(body.timestamp ?? 0);
  const sig = String(body.signature ?? "");
  if (!nonce || !ts || !sig) return hiveFail("signature tidak lengkap", 400);
  if (Math.abs(Date.now() / 1000 - ts) > HIVE_MAX_SKEW) return hiveFail("timestamp kadaluarsa", 400);
  if (md5hex(nonce + HIVE_KEY + ts) !== sig) return hiveFail("signature salah", 400);
  if (String(body.app_id ?? "") !== HIVE_APP_ID) return hiveFail("app_id tidak dikenal", 400);

  if (ep.includes("get_sstoken")) {
    const uid = hiveReadCode(body.code);
    if (!uid) return hiveFail("code tidak valid", 400);
    await pastikan(env, uid);
    return hiveOk({ ss_token: hiveSsToken(uid), expire_date: Date.now() + 365 * 24 * 3600 * 1000 });
  }

  if (ep.includes("get_user_info")) {
    const uid = hiveReadToken(body.ss_token);
    if (!uid) return hiveFail("ss_token tidak valid", 401);
    if (String(body.user_id ?? "") && String(body.user_id) !== uid) {
      return hiveFail("user_id tidak cocok", 401);
    }
    const pl = await hivePemain(env, uid);
    return hiveOk({
      user_id: uid,
      user_name: pl.name,
      user_avatar: pl.avatar,
      balance: hiveSafe(pl.balance),
      currency_balance: hiveSafe(pl.balance),
    });
  }

  if (ep.includes("change_balance")) {
    const uid = hiveReadToken(body.ss_token);
    if (!uid) return hiveFail("ss_token tidak valid", 401);
    const orderId = String(body.order_id ?? "");
    if (!orderId) return hiveFail("order_id kosong", 400);
    const diff = Math.trunc(Number(body.currency_diff ?? 0));
    if (!Number.isFinite(diff)) return hiveFail("currency_diff tidak valid", 400);
    if (diff === 0) {
      const pl = await hivePemain(env, uid);
      return hiveOk({ currency_balance: hiveSafe(pl.balance) });
    }
    const resp = await hiveUang(env, uid, {
      kind: diff < 0 ? 1 : 2,
      amount: Math.abs(diff),
      orderId: `hive_${orderId}`,
      roundId: String(body.game_round_id ?? body.room_id ?? ""),
      game: `hive:${String(body.game_id ?? "")}`,
    });
    // hiveUang mengembalikan Response, jadi hasilnya dibaca sebagai JSON dulu.
    const r = await resp.json().catch(() => null);
    if (!r || r.ok !== 1) {
      // Saldo kurang atau order bermasalah: laporkan angka saldo terbaru.
      const pl = await hivePemain(env, uid);
      return hiveFail(r?.e ?? "gagal memperbarui saldo", 500, { currency_balance: hiveSafe(pl.balance) });
    }
    return hiveOk({ currency_balance: hiveSafe(r.w?.coins ?? r.coins) });
  }

  return hiveFail("endpoint tidak dikenal", 404);
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (url.pathname === "/panel") {
      const b = await req.json().catch(() => null);
      if (!b) return json({ ok: 0, e: "Body tidak valid" }, 400);
      return await panel(env, b);
    }
    if (url.pathname === "/admin") {
      const b = await req.json().catch(() => null);
      if (!b) return json({ ok: 0, e: "Body tidak valid" }, 400);
      return await admin(env, b);
    }
    if (url.pathname.toLowerCase().startsWith("/hive/")) {
      return await hivePlay(req, env);
    }
    if (url.pathname === "/health") {
      // Sekaligus buktikan sambungan ke Turso hidup.
      try {
        await pipe(env, [{ sql: "select 1 as ok" }]);
        return json({ ok: 1, v: "turso-2026-11-03b", db: 1 });
      } catch (e) {
        return json({ ok: 0, v: "turso-2026-11-03b", db: String(e?.message ?? e) }, 500);
      }
    }
    if (req.method !== "POST") return gagal("Gunakan POST");

    let b;
    try { b = await req.json(); } catch { return gagal("Isi permintaan tidak terbaca"); }

    const aksi = String(b.a ?? "");
    const p = b.p ?? {};
    // App mengirim nama bersnake_case; samakan supaya satu jalur.
    p.itemId ??= p.item_id;
    p.roomId ??= p.room_id;

    // Pemanggilan dari app wajib membawa token login Firebase.
    const uidToken = await uidDariToken(String(b.t ?? ""));
    if (!uidToken) return json({ ok: 0, e: "Sesi tidak sah" }, 401);

    // uid hanya boleh dirinya sendiri, kecuali deep link lama yang kosong.
    const uid = String(p.uid ?? uidToken);
    if (uid !== uidToken) return json({ ok: 0, e: "Sesi tidak sah" }, 401);

    try {
      switch (aksi) {
        case "get":
          return ok(await pastikan(env, uid));
        case "migrate":
          return ok(await pastikan(env, uid));
        case "send_gift":
          return await kirimGift(env, uid, p);
        case "topup":
          return await topup(env, uid, p);
        case "signin_claim":
          return await klaimSignin(env, uid, p);
        case "exchange":
          return await tukar(env, uid, p);
        case "buy_vip":
          return await beliVip(env, uid, p);
        case "buy_item":
          return await beliItem(env, uid, p);
        case "equip":
          return await pasangItem(env, uid, p);
        case "claim_level":
          return await klaimLevel(env, uid);
        case "backfill":
          return await backfillVip(env, uid);
        case "wave_prepare":
          return await wavePrepare(env, uid, p);
        case "gift_cashback":
          return await cashbackGift(env, uid, p);
        case "rocket_claim":
          return await klaimRoket(env, uid, p);
        case "pay_info":
          return json({ ok: 1, pay: await infoBayar(env) });
        case "manual_create":
          return await topupManualBuat(env, uid, p);
        case "topup_status": {
          const id = String(p.order ?? p.id ?? "");
          const r = await pipe(env, [
            { sql: "select status from topup_manual where id = ? and uid = ?", args: [T(id), T(uid)] },
          ]);
          const st = r[0].rows[0]?.status;
          if (!st) return json({ ok: 0, e: "Pesanan tidak ditemukan" });
          const map = { pending: "pending", ok: "paid", tolak: "rejected" };
          return json({ ok: 1, order: { id, status: map[st] ?? st } });
        }
        case "manual_history":
          return await topupManualRiwayat(env, uid);
        case "hive_uang":
          return await hiveUang(env, uid, p);
        default:
          return gagal(`aksi ${aksi} belum dipindah ke Turso`);
      }
    } catch (e) {
      return gagal(String(e?.message ?? "Server dompet sibuk"));
    }
  },
};
