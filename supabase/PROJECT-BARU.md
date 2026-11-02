# Pindah ke Project Supabase Baru

Tujuan: project gratis baru di organisasi baru, isi dompet dibawa, app tetap sama.

## 1. Buat project
1. Supabase, pojok kiri atas, ganti organisasi, pilih **New organization** (jangan pakai organisasi lama, kuota dihitung per organisasi).
2. Di organisasi baru, **New project**. Catat dua nilai dari Settings > API:
   - Project URL, bentuknya `https://xxxxx.supabase.co`
   - Kunci **publishable** (dulu namanya anon)

## 2. Siapkan database
1. Buka SQL Editor project baru.
2. Tempel seluruh isi `supabase/PROJECT-BARU.sql`, Run. Berkas ini membuat 9 tabel (wallets, money_tx, exchange_req, topup_orders, topup_manual, topup_price, app_cfg, wave_players, bucket chatimg) dan 30 fungsi.
3. Cek cepat: Table Editor harus menampilkan tabel `wallets` dan `money_tx`.

## 3. Deploy wallet-api
1. Edge Functions > Deploy new function, nama **wallet-api**.
2. Tempel isi `supabase/functions/wallet-api/index.ts`.
3. Matikan **Verify JWT** (wajib, karena app memakai apikey + token Firebase sendiri).
4. Isi secret (Edge Functions > Secrets):
   - `MIDTRANS_SERVER_KEY` (kalau topup Midtrans dipakai)
   - `MIDTRANS_PRODUCTION` = `true` kalau sudah produksi
   - `XENDIT_SERVER_KEY`, `XENDIT_CALLBACK_TOKEN` (kalau Xendit dipakai)
   - `SUPABASE_URL` dan `SUPABASE_SERVICE_ROLE_KEY` biasanya sudah otomatis.

## 4. Bawa data dari project lama
Table Editor project lama, per tabel: pilih semua baris, **Export > CSV**, lalu di project baru **Import data from CSV**. Urutan penting:
1. `wallets` (saldo semua user, paling penting)
2. `wave_players` (opsional)
3. `topup_orders`, `topup_manual` (opsional, riwayat topup)
4. `money_tx` tidak perlu, isinya 195 MB dan sudah dikosongkan. Kalau mau, ekspor 7 hari terakhir saja.
5. Bucket `chatimg` dibuat otomatis oleh SQL, isi gambarnya tidak perlu dibawa.

## 5. Ganti alamat di app (3 tempat)
1. `app/src/main/java/com/example/googlelogin/SupaRepo.kt` baris 14-16: `BASE` dan `KEY`.
2. `app/src/main/java/com/example/googlelogin/RoomImages.kt`: `BASE` dan `KEY` (sama).
3. `docs/panel-v6.html`: `WALLET_API` / `BASE` dan kunci publishable.
4. Midtrans: daftarkan ulang URL notifikasi ke `https://<project-baru>.supabase.co/functions/v1/wallet-api/midtrans`.

## 6. Setelah itu
1. Build APK baru, pasang, tes: login, buka dompet, kirim gift, tukar diamond, topup.
2. Jangan hapus project lama sampai semuanya terbukti jalan. Kalau ada data yang tertinggal, masih bisa diekspor dari sana.

## Catatan
- Firebase (login, chat, room), Cloudinary, dan worker Agora tidak berubah.
- Kuota gratis dihitung per organisasi, jadi jangan membuat project baru di organisasi lama.
