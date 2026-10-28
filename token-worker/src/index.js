import { RtcTokenBuilder, RtcRole } from 'agora-token';

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
  const u = new URL(req.url);
  const isSubmit = u.pathname.toLowerCase().includes('submitflow');
  const url = `${WAVE_SUPA}/wave/game/${isSubmit ? 'submitFlow' : 'userInfo'}`;
  const headers = { 'content-type': 'application/json', accept: 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  let status = 0;
  let text = '';
  let err = '';
  try {
    const resp = await fetch(url, {
      method: isSubmit ? 'POST' : 'GET',
      headers,
      body: isSubmit ? (bodyText || '{}') : undefined,
    });
    status = resp.status;
    text = await resp.text();
  } catch (e) {
    err = String((e && e.message) || e);
  }
  await logWave(env, {
    at: new Date().toISOString(),
    method: req.method,
    path: u.pathname,
    query: u.search.replace(/(token|t|ut)=[^&]+/gi, '$1=<hidden>').slice(0, 120),
    from: (req.headers.get('origin') || req.headers.get('referer') || '').slice(0, 60),
    ua: (req.headers.get('user-agent') || '').slice(0, 40),
    hasToken: Boolean(token),
    tokenEnd: token ? token.slice(-8) : '',
    status,
    reply: err ? 'FETCH: ' + err : text.slice(0, 220),
    body: bodyText.slice(0, 200),
  });
  if (err) return json({ success: false, message: 'proxy error: ' + err }, 502);
  return new Response(text, {
    status,
    headers: {
      'content-type': 'application/json',
      'access-control-allow-origin': '*',
      'access-control-allow-headers': '*',
    },
  });
}

export default {
  async fetch(req, env) {
    const path = new URL(req.url).pathname.replace(/\/+$/, '');
    const lowPath = path.toLowerCase();
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
      const token = RtcTokenBuilder.buildTokenWithUid(
        env.AGORA_APP_ID, env.AGORA_APP_CERTIFICATE, channel, 0, RtcRole.PUBLISHER, TTL, TTL,
      );
      // App memakai appId ini untuk init RtcEngine supaya tidak mismatch.
      return json({ token, appId: env.AGORA_APP_ID, expiresIn: TTL });
    } catch (e) {
      if (e instanceof AuthError) return json({ error: e.message }, 401);
      return json({ error: 'server error: ' + (e && e.message) }, 500);
    }
  },
};
