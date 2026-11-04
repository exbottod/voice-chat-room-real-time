# Dompet REZO di Node (tanpa Cloudflare)

Kode yang sama dengan worker Cloudflare, dibungkus satu server HTTP.
Dipisah agar bisa jalan di Render gratis (tanpa kartu) atau VPS mana pun.

## Variabel lingkungan
- TURSO_URL   = libsql://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io
- TURSO_TOKEN = token Turso (Database > Create Token)
- PORT        = diisi otomatis oleh Render

## Jalan lokal
```
npm start
```
Cek: GET /health -> {"ok":1,"turso":true,"token":true}
