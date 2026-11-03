-- ============================================================
-- Kolom room & ronde untuk transaksi Hive Play
-- Jalankan SEKALI di Supabase > SQL Editor.
-- Tanpa kolom ini function tetap jalan (data ronde tidak tersimpan),
-- hanya saja peringkat jatuh ke mode 24 jam.
-- ============================================================

alter table public.wave_tx add column if not exists room_id  text;
alter table public.wave_tx add column if not exists round_id text;

create index if not exists wave_tx_hive_round_idx
  on public.wave_tx (game_name, round_id, created_at desc);

-- Cek hasilnya:
-- select column_name, data_type
--   from information_schema.columns
--  where table_name = 'wave_tx';
