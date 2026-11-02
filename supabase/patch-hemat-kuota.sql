-- Bersih-bersih supaya kuota Supabase tidak jebol.
-- 1. Cek ukuran tabel (yang paling besar biasanya money_tx).
select relname as tabel,
       pg_size_pretty(pg_total_relation_size(relid)) as ukuran
from pg_catalog.pg_statio_user_tables
order by pg_total_relation_size(relid) desc
limit 10;

-- 2. Hapus riwayat transaksi lebih tua dari 30 hari (saldo TIDAK terpengaruh,
--    money_tx hanya catatan audit).
delete from public.money_tx where created_at < now() - interval '30 days';

-- 3. Kalau masih besar, sisakan 7 hari saja.
-- delete from public.money_tx where created_at < now() - interval '7 days';

-- 4. Rapikan berkas yang sudah dihapus (jalankan terpisah).
vacuum (full, analyze) public.money_tx;
