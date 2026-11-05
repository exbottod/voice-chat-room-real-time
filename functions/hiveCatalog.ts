// Cermin katalog game Hive Play untuk aplikasi REZO.
// Selalu bertanya langsung ke Hive (cache pendek 60 detik) supaya game yang
// baru ditambahkan atau dihapus di console Hive langsung terbaca aplikasi,
// tanpa menunggu cron GitHub Pages yang sering tertunda.

const APP = "100198";
const KEY = "eb09c56d97454d43ab2698ad548697ab";
const URL_INFO = "https://api2023.duolepoker.com/v1/h5-game/third/game_info";

// MD5 kecil tanpa dependensi (dipakai untuk tanda tangan permintaan Hive).
function md5(input: string): string {
  const toBytes = (s: string) => {
    const out: number[] = [];
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return out;
  };
  const bytes = toBytes(input);
  const bitLen = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  for (let i = 0; i < 4; i++) bytes.push((bitLen >>> (8 * i)) & 0xff);
  for (let i = 0; i < 4; i++) bytes.push(0);

  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  const S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
  const K: number[] = [];
  for (let i = 0; i < 64; i++) K.push(Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296));

  for (let off = 0; off < bytes.length; off += 64) {
    const M: number[] = [];
    for (let i = 0; i < 16; i++) {
      const j = off + i * 4;
      M.push(bytes[j] | (bytes[j + 1] << 8) | (bytes[j + 2] << 16) | (bytes[j + 3] << 24));
    }
    let A = a0, B = b0, C = c0, D = d0;
    for (let i = 0; i < 64; i++) {
      let F: number, g: number;
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i) % 16; }
      F = (F + A + K[i] + M[g]) | 0;
      A = D; D = C; C = B;
      const sh = S[i];
      B = (B + ((F << sh) | (F >>> (32 - sh)))) | 0;
    }
    a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
  }
  const hex = (n: number) => {
    let s = "";
    for (let i = 0; i < 4; i++) s += ((n >>> (8 * i)) & 0xff).toString(16).padStart(2, "0");
    return s;
  };
  return hex(a0) + hex(b0) + hex(c0) + hex(d0);
}

let cache: { at: number; body: any } | null = null;

async function ambilHive() {
  const ts = String(Date.now());
  const nonce = ts + String(Math.floor(1000 + Math.random() * 9000));
  const signature = md5(APP + KEY + nonce + ts);
  const res = await fetch(URL_INFO, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ appId: APP, timestamp: ts, nonce, signature, env: "prod" }),
  });
  const txt = await res.text();
  let d: any;
  try { d = JSON.parse(txt); } catch { throw new Error("Jawaban Hive tidak terbaca: " + txt.slice(0, 120)); }
  if (String(d?.code) !== "200" || !Array.isArray(d?.data)) {
    throw new Error("Katalog Hive kosong: " + (d?.msg || d?.code || "tidak diketahui"));
  }
  return { code: "200", msg: "success", data: d.data, diambil: Date.now(), sumber: "hive" };
}

export default async function hiveCatalog(req: any) {
  const cors = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Cache-Control": "no-store",
  };
  if (req?.method === "OPTIONS") return new Response("", { status: 204, headers: cors });

  const segar = Date.now() - (cache?.at || 0) > 60_000;
  if (segar || !cache) {
    try {
      const body = await ambilHive();
      cache = { at: Date.now(), body };
    } catch (e: any) {
      // Kalau Hive sedang tidak bisa dihubungi, kirim salinan terakhir (kalau ada).
      if (cache) {
        return new Response(JSON.stringify({ ...cache.body, basi: true }), {
          status: 200, headers: { ...cors, "Content-Type": "application/json" },
        });
      }
      return new Response(JSON.stringify({ code: "500", msg: String(e?.message || e), data: [] }), {
        status: 200, headers: { ...cors, "Content-Type": "application/json" },
      });
    }
  }
  return new Response(JSON.stringify(cache!.body), {
    status: 200, headers: { ...cors, "Content-Type": "application/json" },
  });
}
