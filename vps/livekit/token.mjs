// Layanan token LiveKit. Memverifikasi login Firebase, lalu membuat JWT LiveKit.
// Tanpa library tambahan: JWT HS256 dibuat dengan modul crypto bawaan Node.
import http from "node:http";
import crypto from "node:crypto";

const PROJECT = process.env.FIREBASE_PROJECT_ID || "arboreal-cat-511705-p6";
const KEY = process.env.LIVEKIT_API_KEY || "";
const SECRET = process.env.LIVEKIT_API_SECRET || "";
const PORT = Number(process.env.PORT || 8091);
const JWKS = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

let cacheKunci = { at: 0, keys: [] };
async function kunciFirebase() {
  if (Date.now() - cacheKunci.at < 3600_000 && cacheKunci.keys.length) return cacheKunci.keys;
  const r = await fetch(JWKS);
  const { keys } = await r.json();
  cacheKunci = { at: Date.now(), keys };
  return keys;
}
const b64u = (b) => Buffer.from(b).toString("base64url");

async function verifikasi(idToken) {
  const [h, p, s] = idToken.split(".");
  if (!h || !p || !s) throw new Error("tiket login tidak valid");
  const head = JSON.parse(Buffer.from(h, "base64url").toString());
  const isi = JSON.parse(Buffer.from(p, "base64url").toString());
  if (head.alg !== "RS256") throw new Error("algoritma salah");
  const jwk = (await kunciFirebase()).find((k) => k.kid === head.kid);
  if (!jwk) throw new Error("kunci tidak dikenal");
  const ok = crypto.verify("RSA-SHA256", Buffer.from(h + "." + p), crypto.createPublicKey({ key: jwk, format: "jwk" }), Buffer.from(s, "base64url"));
  if (!ok) throw new Error("tanda tangan salah");
  const now = Math.floor(Date.now() / 1000);
  if (isi.exp <= now) throw new Error("tiket kedaluwarsa");
  if (isi.aud !== PROJECT || isi.iss !== "https://securetoken.google.com/" + PROJECT) throw new Error("tiket bukan untuk proyek ini");
  if (!isi.sub) throw new Error("sub kosong");
  return isi;
}

// Pemain boleh bicara hanya kalau diminta (bicara=true); izin sebenarnya
// diatur di klien lewat canPublish. Server tetap memberi canPublish agar
// pindah dari pendengar ke penyiar tidak perlu minta token baru.
function buatToken(identitas, nama, ruang) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64u(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = b64u(JSON.stringify({
    iss: KEY, sub: identitas, name: nama || identitas, nbf: now - 10, exp: now + 6 * 3600,
    video: { room: ruang, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true },
  }));
  const sig = crypto.createHmac("sha256", SECRET).update(head + "." + body).digest("base64url");
  return head + "." + body + "." + sig;
}

const kirim = (res, kode, o) => { res.writeHead(kode, { "content-type": "application/json; charset=utf-8" }); res.end(JSON.stringify(o)); };

http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url.startsWith("/lktoken/health")) return kirim(res, 200, { ok: true, siap: !!(KEY && SECRET) });
    if (req.method !== "POST") return kirim(res, 405, { error: "pakai POST" });
    if (!KEY || !SECRET) return kirim(res, 500, { error: "kunci LiveKit belum diisi" });
    const auth = req.headers["authorization"] || "";
    let user;
    try { user = await verifikasi(auth.startsWith("Bearer ") ? auth.slice(7) : ""); }
    catch (e) { return kirim(res, 401, { error: String(e.message || e) }); }
    const potong = []; for await (const c of req) { potong.push(c); if (Buffer.concat(potong).length > 4096) break; }
    let o = {}; try { o = JSON.parse(Buffer.concat(potong).toString() || "{}"); } catch {}
    const ruang = String(o.channel || o.room || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64);
    if (!ruang) return kirim(res, 400, { error: "nama room kosong" });
    const token = buatToken(user.sub, user.name || user.email || "", ruang);
    console.log(`token ${ruang} ${user.sub.slice(0, 6)}…`);
    kirim(res, 200, { token, url: "wss://" + (req.headers["x-forwarded-host"] || req.headers.host) + "/livekit", expiresIn: 6 * 3600 });
  } catch (e) {
    console.error("gagal:", e?.message || e);
    kirim(res, 500, { error: "server token sibuk" });
  }
}).listen(PORT, "127.0.0.1", () => console.log("token LiveKit jalan di " + PORT));
