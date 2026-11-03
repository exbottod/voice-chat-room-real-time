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
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), {
    status,
    headers: { "Content-Type": "application/json" },
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

function pecahCashback(total) {
  if (!(total > 0) || total > 50000) return [];
  const out = [];
  let sisa = Math.floor(total), jaga = 0;
  while (sisa >= 10 && out.length < 8 && jaga++ < 16) {
    const v = CB_LADDER.find((x) => x <= sisa);
    if (!v) break;
    out.push(v);
    sisa -= v;
  }
  return out;
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
async function cashbackGift(env, uid, p) {
  const cfg = await cfgRtdb("pub/_cashbackCfg");
  if (cfg.on === false) return json({ ok: 1, amount: 0, off: 1 });
  const price = Math.max(0, Math.floor(Number(p.price ?? 0)));
  const count = Math.max(1, Math.min(999, Math.floor(Number(p.count ?? 1))));
  const nilai = price * count;
  if (nilai <= 0) return gagal("Nilai gift tidak valid");

  const baris = undiBaris(barisTier(cfg, nilai));
  const mult = Math.max(0, Math.min(500, Math.floor(baris.v)));
  const target = nilai * mult;
  const parts = pecahCashback(target);
  const amount = parts.length ? parts.reduce((a, b) => a + b, 0) : target;

  const paid = await beriCoin(env, uid, amount, "cashback", null);
  const pct = Math.max(0, Math.min(100, Number(cfg.recvPct ?? 5)));
  const to = String(p.to ?? "").trim();
  const bonus = to ? Math.floor((nilai * pct) / 100) : 0;
  const recv = bonus > 0 && to !== uid ? await beriCoin(env, to, bonus, "cashback_recv", null) : 0;
  const jpMin = Math.max(0, Math.floor(Number(cfg.jpMin ?? 1000)));
  return json({ ok: 1, amount: paid, mult, parts: parts.slice(0, 8), pecahan: parts[0] ?? baris.v,
    recv, jp: paid >= jpMin ? 1 : 0, asset: "" });
}

// ---------- Topup manual DANA ----------
async function infoBayar(env) {
  const r = await pipe(env, [{ sql: "select dana, nama, gopay, qris from pay_info where id = 1" }]);
  const d = r[0].rows[0] ?? {};
  return { dana: d.dana ?? "", name: d.nama ?? "", gopay: d.gopay ?? "", qris: d.qris ?? "" };
}

async function topupManualBuat(env, uid, p) {
  const id = String(p.id ?? `TM${Date.now()}`);
  const coins = Math.trunc(Number(p.coins ?? 0));
  const amount = Math.trunc(Number(p.amount ?? 0));
  const payer = String(p.payer ?? "").trim();
  const method = String(p.method ?? "dana").trim() || "dana";
  if (coins <= 0 || amount <= 0) return gagal("Nominal tidak valid");
  if (payer.length < 3) return gagal("Nama pengirim terlalu pendek");
  const r = await pipe(env, [
    {
      sql: "select count(*) as n from topup_manual where uid = ? and status = 'pending'",
      args: [T(uid)],
    },
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
async function admin(env, body) {
  const sesi = await sesiDariToken(body.t ?? "");
  if (!sesi) return json({ ok: 0, e: "Token tidak valid" }, 401);
  if (sesi.email !== ADMIN_EMAIL) return json({ ok: 0, e: "Hanya admin" }, 403);
  const p = body.p ?? {};
  const op = String(p.op ?? "");
  const target = String(p.uid ?? "");

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
  return gagal("Perintah panel tidak dikenal");
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return json({ ok: 1 });
    if (url.pathname === "/admin") {
      const b = await req.json().catch(() => null);
      if (!b) return json({ ok: 0, e: "Body tidak valid" }, 400);
      return await admin(env, b);
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
