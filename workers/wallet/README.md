# Worker dompet REZO (Cloudflare + Turso)

Berkas utama: `src/index.js` (satu berkas, tanpa build).

## Isi yang sudah pindah
- `get` / `migrate` : baca saldo, buat dompet kalau belum ada
- `send_gift` : potong coin, tambah diamond dan exp (bonus VIP), catat ke `money_tx`
- `topup` : tambah coin
- `exchange` : 2 diamond = 1 coin
- `hive_uang` : bet (potong) dan win (tambah) dari Hive

Semua memakai satu transaksi `BEGIN IMMEDIATE` plus penanda permintaan,
jadi kiriman ganda tidak memotong coin dua kali.

## Secret yang dibutuhkan
- `TURSO_URL` contoh `https://namadb-org.turso.io`
- `TURSO_TOKEN` token dari dashboard Turso

## Langkah pasang
1. Buat database di Turso, jalankan `turso/schema.sql`.
2. Cloudflare dashboard, Workers, Create, tempel isi `src/index.js`.
3. Settings, Variables, tambahkan `TURSO_URL` dan `TURSO_TOKEN` sebagai secret.
4. Deploy, lalu tes `GET /health`.

## Belum dipindah
`buy_item`, `buy_vip`, `equip`, `claim_level`, `wave_prepare`, admin panel.
Sengaja bertahap supaya gift dan saldo dulu yang stabil.
