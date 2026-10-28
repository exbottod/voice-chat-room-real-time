// ---------- Agora AccessToken2 (tanpa dependensi npm) ----------
const _enc = new TextEncoder();
const _u16 = (n) => { const b = new Uint8Array(2); b[0] = n & 255; b[1] = (n >> 8) & 255; return b; };
const _u32 = (n) => { const b = new Uint8Array(4); b[0] = n & 255; b[1] = (n >> 8) & 255; b[2] = (n >> 16) & 255; b[3] = (n >>> 24) & 255; return b; };
const _cat = (arrs) => { const n = arrs.reduce((s, a) => s + a.length, 0); const o = new Uint8Array(n); let p = 0; for (const a of arrs) { o.set(a, p); p += a.length; } return o; };
const _str = (s) => { const b = _enc.encode(s); return _cat([_u16(b.length), b]); };
const _b64 = (bytes) => { let s = ''; for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode(...bytes.subarray(i, i + 8192)); return btoa(s); };

async function _hmac(keyBytes, msgBytes) {
  const k = await crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, msgBytes));
}

async function _deflate(bytes) {
  const cs = new CompressionStream('deflate');
  const w = cs.writable.getWriter();
  w.write(bytes);
  w.close();
  return new Uint8Array(await new Response(cs.readable).arrayBuffer());
}

/** Token RTC (role publisher, uid 0 = bebas). Sama persis dengan paket agora-token. */
async function buildRtcToken(appId, appCert, channel, uid, expireSec) {
  const issueTs = Math.floor(Date.now() / 1000);
  const expire = issueTs + expireSec;
  const salt = Math.floor(Math.random() * 99999999) + 1;
  let signing = await _hmac(_u32(issueTs), _enc.encode(appCert));
  signing = await _hmac(_u32(salt), signing);
  const privs = _cat([_u16(2), _u16(1), _u32(expire), _u16(2), _u32(expire)]);
  const svc = _cat([_u16(1), privs, _str(channel), _str(uid === 0 ? '' : String(uid))]);
  const info = _cat([_str(appId), _u32(issueTs), _u32(expire), _u32(salt), _u16(1), svc]);
  const sig = await _hmac(signing, info);
  const withSig = _cat([_u16(sig.length), sig, info]);
  return '007' + _b64(await _deflate(withSig));
}

const JWKS_URL = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
const TTL = 86400; // 24 jam (maksimum Agora)

const b64u = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
const txt = (s) => new TextDecoder().decode(b64u(s));
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json' } });

class AuthError extends Error {}

async function verifyFirebase(idToken, projectId) {
  const parts = idToken.split('.');
  if (parts.length !== 3) throw new AuthError('tiket login tidak valid');
  const header = JSON.parse(txt(parts[0]));
  const p = JSON.parse(txt(parts[1]));
  if (header.alg !== 'RS256') throw new AuthError('algoritma salah');
  const res = await fetch(JWKS_URL, { cf: { cacheTtl: 3600, cacheEverything: true } });
  const { keys } = await res.json();
  const jwk = keys.find((k) => k.kid === header.kid);
  if (!jwk) throw new AuthError('kunci tidak dikenal');
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64u(parts[2]), new TextEncoder().encode(parts[0] + '.' + parts[1]));
  if (!ok) throw new AuthError('tanda tangan salah');
  const now = Math.floor(Date.now() / 1000);
  if (p.exp <= now) throw new AuthError('tiket kedaluwarsa');
  if (p.iat > now + 60) throw new AuthError('tiket belum berlaku');
  if (p.aud !== projectId) throw new AuthError('aud salah');
  if (p.iss !== 'https://securetoken.google.com/' + projectId) throw new AuthError('iss salah');
  if (!p.sub) throw new AuthError('sub kosong');
  return p;
}

// ---------- Upload efek/frame ke Cloudinary (khusus admin) ----------
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
};
const withCors = (res) => {
  const h = new Headers(res.headers);
  for (const [k, v] of Object.entries(CORS)) h.set(k, v);
  return new Response(res.body, { status: res.status, headers: h });
};
const ALLOWED_EXT = { png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', mp4: 'video', svga: 'raw' };
const MAX_BYTES = 25 * 1024 * 1024;

async function sha1Hex(str) {
  const buf = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(str));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function handleUpload(req, env) {
  const auth = req.headers.get('Authorization') || '';
  const p = await verifyFirebase(auth.startsWith('Bearer ') ? auth.slice(7) : '', env.FIREBASE_PROJECT_ID);
  const admin = String(env.ADMIN_EMAIL || '').toLowerCase();
  const provider = p.firebase && p.firebase.sign_in_provider;
  if (!admin || String(p.email || '').toLowerCase() !== admin || provider !== 'google.com') {
    return json({ error: 'hanya admin (login: ' + provider + ', email: ' + (p.email || '-') + ')' }, 403);
  }
  if (!env.CLOUDINARY_CLOUD || !env.CLOUDINARY_API_KEY || !env.CLOUDINARY_API_SECRET) {
    return json({ error: 'secret Cloudinary belum diisi' }, 500);
  }
  const form = await req.formData();
  const file = form.get('file');
  const name = String(form.get('name') || 'asset').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40) || 'asset';
  if (!file || typeof file === 'string') return json({ error: 'file kosong' }, 400);
  const ext = (file.name || '').split('.').pop().toLowerCase();
  const type = ALLOWED_EXT[ext];
  if (!type) return json({ error: 'format tidak didukung (png, jpg, gif, webp, mp4, svga)' }, 400);
  if (file.size > MAX_BYTES) return json({ error: 'file terlalu besar (maks 25 MB)' }, 400);

  const timestamp = Math.floor(Date.now() / 1000);
  const publicId = 'rezo/' + name + '_' + timestamp.toString(36) + (type === 'raw' ? '.' + ext : '');
  // Tanda tangan: parameter diurutkan abjad (tanpa file/api_key/resource_type), lalu + API secret.
  const signature = await sha1Hex('public_id=' + publicId + '&timestamp=' + timestamp + env.CLOUDINARY_API_SECRET);
  const fd = new FormData();
  fd.append('file', file, file.name);
  fd.append('api_key', env.CLOUDINARY_API_KEY);
  fd.append('timestamp', String(timestamp));
  fd.append('public_id', publicId);
  fd.append('signature', signature);
  const res = await fetch('https://api.cloudinary.com/v1_1/' + encodeURIComponent(env.CLOUDINARY_CLOUD) + '/' + type + '/upload', { method: 'POST', body: fd });
  const out = await res.json().catch(() => ({}));
  if (!res.ok || !out.secure_url) return json({ error: 'Cloudinary: ' + ((out.error && out.error.message) || res.status) }, 502);
  return json({ url: out.secure_url, kind: ext === 'svga' ? 'svga' : ext === 'mp4' ? 'mp4' : 'image' });
}

// ---------- Database cadangan wallet (Cloudflare KV) ----------
// Dipakai otomatis oleh app saat kuota harian Firestore habis:
// beli SVIP, beli item store, kirim gift, tukar diamond, topup, klaim level.
// Wallet disimpan per-user: key 'w:<uid>'.
const BK_FIELDS = ['coins', 'diamonds', 'exp', 'vip', 'vipUntil', 'lvClaimed', 'bkSyncedAt'];
const bkBase = () => ({ coins: 0, diamonds: 0, exp: 0, vip: 0, vipUntil: 0, lvClaimed: 0, inv: {}, equip: {}, bkSyncedAt: 0, ver: 0, updatedAt: 0 });

async function bkRead(env, uid) {
  const raw = await env.BKW.get('w:' + uid);
  let w = null;
  try { w = raw ? JSON.parse(raw) : null; } catch (e) { w = null; }
  const base = bkBase();
  if (!w) return base;
  for (const k of BK_FIELDS) if (typeof w[k] !== 'number') w[k] = base[k];
  if (!w.inv || typeof w.inv !== 'object') w.inv = {};
  if (!w.equip || typeof w.equip !== 'object') w.equip = {};
  return w;
}

// Terapkan isi permintaan (patch / add) ke wallet cadangan.
async function bkApply(env, uid, body) {
  const cur = await bkRead(env, uid);
  if (body && body.op === 'add') {
    const add = body.add || {};
    for (const k of ['coins', 'diamonds', 'exp', 'lvClaimed'])
      if (typeof add[k] === 'number' && Number.isFinite(add[k])) cur[k] = (cur[k] || 0) + add[k];
  } else if (body && body.patch && typeof body.patch === 'object') {
    const p = body.patch;
    for (const k of BK_FIELDS) if (typeof p[k] === 'number' && Number.isFinite(p[k])) cur[k] = p[k];
    if (p.inv && typeof p.inv === 'object')
      for (const [id, until] of Object.entries(p.inv))
        if (typeof until === 'number' && Number.isFinite(until)) cur.inv[id] = Math.max(cur.inv[id] || 0, until);
    if (p.equip && typeof p.equip === 'object')
      for (const [slot, id] of Object.entries(p.equip)) cur.equip[slot] = String(id);
  }
  cur.ver = (cur.ver || 0) + 1;
  cur.updatedAt = Date.now();
  await env.BKW.put('w:' + uid, JSON.stringify(cur));
  return cur;
}

// GET/POST /w : wallet milik user yang login (uid dari tiket Firebase).
async function handleWallet(req, env, uid) {
  if (!env.BKW) return json({ error: 'KV backup belum di-binding (var BKW)' }, 500);
  if (req.method === 'GET') return json(await bkRead(env, uid));
  if (req.method !== 'POST') return json({ error: 'GET/POST saja' }, 405);
  const body = await req.json().catch(() => ({}));
  return json(await bkApply(env, uid, body));
}

// POST /w-admin : admin mengubah wallet user lain (topup coin/exp saat kuota habis).
async function handleWalletAdmin(req, env, p) {
  if (!env.BKW) return json({ error: 'KV backup belum di-binding (var BKW)' }, 500);
  if (req.method !== 'POST') return json({ error: 'POST saja' }, 405);
  const admin = String(env.ADMIN_EMAIL || '').toLowerCase();
  const provider = p.firebase && p.firebase.sign_in_provider;
  if (!admin || String(p.email || '').toLowerCase() !== admin || provider !== 'google.com')
    return json({ error: 'hanya admin' }, 403);
  const body = await req.json().catch(() => ({}));
  const uid = String(body.uid || '');
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(uid)) return json({ error: 'uid tidak valid' }, 400);
  const out = await bkApply(env, uid, { op: body.op, add: body.add, patch: body.patch });
  return json(out);
}

// ---------- Halaman link room: /r/<roomId> ----------
// Halaman publik untuk fitur "bagikan room". Kalau app terpasang, tombol
// membuka rezo://room/<roomId> (deep link). Kalau belum, tombol download APK.
function roomPage(roomId) {
  const safe = roomId.replace(/[^A-Za-z0-9_-]/g, '');
  const html = `<!doctype html><html lang="id"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>REZO CHAT VOICE - Undangan Room</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(180deg,#0b0b0b,#14100a);color:#fff;font-family:system-ui,sans-serif}
.c{max-width:400px;width:90%;background:#121212;border:1px solid #d4af37;border-radius:16px;padding:28px;text-align:center}
h1{color:#f6e3a1;font-size:22px;margin:0 0 6px}
p{color:#bdbdbd;font-size:14px;margin:0 0 20px}
a.b{display:block;text-decoration:none;margin:10px 0;padding:14px;border-radius:12px;font-weight:700;font-size:16px}
.open{background:#d4af37;color:#000}
.dl{background:#1e2a28;color:#2bd9c4;border:1px solid #2bd9c4}
small{color:#757575}
</style></head><body><div class="c">
<h1>&#128293; REZO CHAT VOICE</h1>
<p>Kamu diundang masuk room!</p>
<a class="b open" href="rezo://room/${safe}">Buka di aplikasi</a>
<a class="b dl" href="https://github.com/exbottod/voice-chat-room-real-time/releases/latest/download/REZO-CHAT-VOICE.apk">Belum punya app? Download APK</a>
<small>ID room: ${safe}</small>
</div>
<script>setTimeout(function(){location.href='rezo://room/${safe}';},800);</script>
</body></html>`;
  return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
}

// ---------- Proxy coin game (WaveGames) ----------
// WaveGames memanggil Base URL lalu meneruskan token pemain. Karena bentuk
// kiriman mereka tidak selalu sama (header/query/body), proxy ini menerima
// semuanya, mencatat permintaan terakhir, lalu meneruskan ke wallet-api.
const WAVE_SUPA = 'https://poeqhncfrxssilhjvibf.supabase.co/functions/v1/wallet-api';

function pickToken(req, bodyText) {
  const h = req.headers;
  const names = ['authorization', 'token', 'usertoken', 'user-token', 'x-user-token', 'x-auth-token', 'authtoken', 'x-access-token'];
  for (const n of names) {
    const v = h.get(n);
    if (v && v.trim()) return v.replace(/^Bearer\s+/i, '').trim();
  }
  const sp = new URL(req.url).searchParams;
  for (const k of ['token', 'userToken', 'user_token', 'ut', 't', 'authToken']) {
    const v = sp.get(k);
    if (v && v.trim()) return v.trim();
  }
  if (bodyText) {
    try {
      const j = JSON.parse(bodyText);
      for (const k of ['token', 'userToken', 'user_token', 'authToken']) {
        if (typeof j?.[k] === 'string' && j[k].trim()) return j[k].trim();
      }
    } catch { /* bukan JSON */ }
  }
  return '';
}

async function logWave(env, entry) {
  try {
    const raw = await env.BKW.get('wlog/last');
    const arr = raw ? JSON.parse(raw) : [];
    arr.unshift(entry);
    await env.BKW.put('wlog/last', JSON.stringify(arr.slice(0, 8)), { expirationTtl: 86400 });
  } catch { /* log gagal tidak boleh mematikan game */ }
}

async function proxyWave(req, env) {
  const bodyText = req.method === 'POST' ? await req.text() : '';
  const token = pickToken(req, bodyText);
  const isSubmit = new URL(req.url).pathname.toLowerCase().includes('submitflow');
  const url = `${WAVE_SUPA}/wave/game/${isSubmit ? 'submitFlow' : 'userInfo'}`;
  await logWave(env, {
    at: new Date().toISOString(),
    method: req.method,
    path: new URL(req.url).pathname,
    query: new URL(req.url).search.replace(/(token|t|ut)=[^&]+/gi, '$1=<hidden>').slice(0, 200),
    header: [...req.headers.keys()].join(','),
    hasToken: Boolean(token),
    tokenEnd: token ? token.slice(-8) : '',
    body: bodyText.slice(0, 400),
  });
  const headers = { 'content-type': 'application/json', accept: 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  let resp;
  try {
    resp = await fetch(url, {
      method: isSubmit ? 'POST' : 'GET',
      headers,
      body: isSubmit ? (bodyText || '{}') : undefined,
    });
  } catch (e) {
    return json({ success: false, message: 'proxy error: ' + (e && e.message) }, 502);
  }
  const text = await resp.text();
  return new Response(text, {
    status: resp.status,
    headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*', 'access-control-allow-headers': '*' },
  });
}

export default {
  async fetch(req, env) {
    const path = new URL(req.url).pathname.replace(/\/+$/, '');
    const lowPath = path.toLowerCase();
    // Catatan permintaan WaveGames (untuk diagnosis).
    // CORS preflight dari WebView game: jawab langsung.
    if (req.method === 'OPTIONS' && (lowPath.includes('userinfo') || lowPath.includes('submitflow') || lowPath.includes('/wave'))) {
      return new Response(null, { status: 204, headers: {
        'access-control-allow-origin': '*',
        'access-control-allow-methods': 'GET, POST, OPTIONS',
        'access-control-allow-headers': req.headers.get('access-control-request-headers') || '*',
        'access-control-max-age': '86400',
      } });
    }
    if (path === '/wlog') {
      const raw = await env.BKW.get('wlog/last');
      return json({ entries: raw ? JSON.parse(raw) : [] });
    }
    // Semua panggilan coin game diteruskan ke wallet-api Supabase.
    if (lowPath.includes('userinfo') || lowPath.includes('submitflow') || lowPath.startsWith('/wave')) {
      return await proxyWave(req, env);
    }
    // Link room publik (seperti link invite): /r/<roomId>
    if (path.startsWith('/r/')) return roomPage(path.slice(3));
    // Database cadangan wallet: /w (user) dan /w-admin (admin topup user lain).
    if (path === '/w' || path === '/w-admin') {
      if (req.method === 'OPTIONS') return withCors(new Response(null, { status: 204 }));
      try {
        const auth = req.headers.get('Authorization') || '';
        const p = await verifyFirebase(auth.startsWith('Bearer ') ? auth.slice(7) : '', env.FIREBASE_PROJECT_ID);
        if (path === '/w') return withCors(await handleWallet(req, env, p.sub));
        return withCors(await handleWalletAdmin(req, env, p));
      } catch (e) {
        if (e instanceof AuthError) return withCors(json({ error: e.message }, 401));
        return withCors(json({ error: 'server error: ' + (e && e.message) }, 500));
      }
    }
    const isUpload = path.endsWith('/upload');
    if (isUpload) {
      if (req.method === 'OPTIONS') return withCors(new Response(null, { status: 204 }));
      if (req.method !== 'POST') return withCors(json({ error: 'POST saja' }, 405));
      try {
        return withCors(await handleUpload(req, env));
      } catch (e) {
        if (e instanceof AuthError) return withCors(json({ error: e.message }, 401));
        return withCors(json({ error: 'server error: ' + (e && e.message) }, 500));
      }
    }
    if (req.method !== 'POST') return json({ error: 'POST saja' }, 405);
    try {
      const auth = req.headers.get('Authorization') || '';
      await verifyFirebase(auth.startsWith('Bearer ') ? auth.slice(7) : '', env.FIREBASE_PROJECT_ID);
      const { channel } = await req.json();
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(channel || '')) return json({ error: 'channel tidak valid' }, 400);
      if (!env.AGORA_APP_ID || !env.AGORA_APP_CERTIFICATE) return json({ error: 'secret Agora belum diisi' }, 500);
      const token = await buildRtcToken(env.AGORA_APP_ID, env.AGORA_APP_CERTIFICATE, channel, 0, TTL);
      // App memakai appId ini untuk init RtcEngine supaya tidak mismatch.
      return json({ token, appId: env.AGORA_APP_ID, expiresIn: TTL });
    } catch (e) {
      if (e instanceof AuthError) return json({ error: e.message }, 401);
      return json({ error: 'server error: ' + (e && e.message) }, 500);
    }
  },
};
