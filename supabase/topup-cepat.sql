-- =====================================================================
--  Topup manual versi cepat (tanpa perlu deploy ulang Edge Function)
--  Jalankan seluruh file ini di Supabase -> SQL Editor -> Run.
--  Aman diulang berkali-kali.
--
--  Isi:
--   1. Tabel harga paket (server yang menentukan harga, bukan app)
--   2. Wrapper publik yang boleh dipanggil aplikasi:
--        w_app_pay()            -> nomor DANA / GoPay / QRIS admin
--        w_app_topup_ask(...)   -> membuat pengajuan topup (status pending)
--        w_app_topup_state(...) -> status pengajuan (pending / paid / rejected)
--   3. Coin tetap hanya masuk setelah admin menekan Setujui di panel.
-- =====================================================================

-- 1. Harga paket coin ---------------------------------------------------
create table if not exists public.topup_price (
  coins  bigint primary key check (coins > 0),
  amount bigint not null check (amount > 0)
);
alter table public.topup_price enable row level security;
revoke all on table public.topup_price from public, anon, authenticated;

insert into public.topup_price (coins, amount) values
  (1000000, 6000),
  (3000000, 18000),
  (10000000, 58000),
  (30000000, 170000)
on conflict (coins) do update set amount = excluded.amount;

-- 2. Info pembayaran untuk aplikasi ------------------------------------
create or replace function public.w_app_pay()
returns jsonb language sql security definer set search_path = public as $$
  select coalesce((select v from public.app_cfg where k = 'pay'), '{}'::jsonb);
$$;

-- 3. Ajukan topup manual ----------------------------------------------
create or replace function public.w_app_topup_ask(
  p_uid text, p_coins bigint, p_payer text, p_method text default 'dana'
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_amount bigint;
  v_id     text;
  v_payer  text;
  v_method text;
  v_pending int;
begin
  if coalesce(length(trim(p_uid)), 0) < 3 then
    return jsonb_build_object('ok', 0, 'e', 'Sesi login tidak valid');
  end if;

  select amount into v_amount from public.topup_price where coins = p_coins;
  if v_amount is null then
    return jsonb_build_object('ok', 0, 'e', 'Paket coin tidak dikenal');
  end if;

  v_payer := trim(coalesce(p_payer, ''));
  if length(v_payer) < 3 then
    return jsonb_build_object('ok', 0, 'e', 'Isi nama pengirim');
  end if;

  v_method := lower(coalesce(nullif(trim(p_method), ''), 'dana'));
  if v_method not in ('dana', 'gopay', 'qris') then
    return jsonb_build_object('ok', 0, 'e', 'Metode tidak dikenal');
  end if;

  select count(*) into v_pending from public.topup_manual
   where uid = p_uid and status = 'pending';
  if v_pending >= 3 then
    return jsonb_build_object('ok', 0, 'e', 'Masih ada pengajuan yang menunggu persetujuan');
  end if;

  v_id := 'MN' || upper(to_hex((extract(epoch from now()) * 1000)::bigint))
             || upper(substr(md5(random()::text), 1, 3));

  insert into public.topup_manual (id, uid, coins, amount, payer, status, created_at, method)
  values (v_id, p_uid, p_coins, v_amount, v_payer, 'pending',
          (extract(epoch from now()) * 1000)::bigint, v_method);

  return jsonb_build_object('ok', 1, 'order', v_id, 'status', 'pending', 'amount', v_amount);
end;
$$;

-- 4. Cek status pengajuan ---------------------------------------------
create or replace function public.w_app_topup_state(p_uid text, p_id text)
returns jsonb language sql security definer set search_path = public as $$
  select coalesce(
    (select jsonb_build_object('ok', 1, 'status', t.status)
       from public.topup_manual t
      where t.id = p_id and t.uid = p_uid),
    jsonb_build_object('ok', 0, 'e', 'Pesanan tidak ditemukan')
  );
$$;

-- 5. Izin panggil untuk aplikasi --------------------------------------
revoke all on function public.w_app_pay() from public;
revoke all on function public.w_app_topup_ask(text, bigint, text, text) from public;
revoke all on function public.w_app_topup_state(text, text) from public;

grant execute on function public.w_app_pay() to anon, authenticated;
grant execute on function public.w_app_topup_ask(text, bigint, text, text) to anon, authenticated;
grant execute on function public.w_app_topup_state(text, text) to anon, authenticated;
