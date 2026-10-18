import { RtcTokenBuilder, RtcRole } from 'agora-token';

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
  return p.sub;
}

export default {
  async fetch(req, env) {
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
      return json({ token, expiresIn: TTL });
    } catch (e) {
      if (e instanceof AuthError) return json({ error: e.message }, 401);
      return json({ error: 'server error: ' + (e && e.message) }, 500);
    }
  },
};
