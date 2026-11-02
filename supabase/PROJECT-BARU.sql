-- =====================================================================
--  REZO CHAT: PERSIAPAN PROJECT SUPABASE BARU
--  Jalankan SELURUH berkas ini di project baru > SQL Editor > Run.
--  Aman diulang berkali-kali (semua pakai if not exists / create or replace).
-- =====================================================================

-- ================= schema.sql =================

-- =====================================================================
--  REZO CHAT: skema dompet/gift/store di Supabase
--  Dompet pindah dari Firestore ke sini; login & room tetap di Firebase.
--  Semua fungsi hanya bisa dipanggil lewat Edge Function (service_role),
--  yang memverifikasi token login Firebase sebelum mengeksekusi.
-- =====================================================================

create table if not exists public.wallets (
  uid        text primary key,
  coins      bigint not null default 0,
  diamonds   bigint not null default 0,
  exp        bigint not null default 0,
  vip        int    not null default 0,
  vip_until  bigint not null default 0,
  lv_claimed bigint not null default 0,
  inv        jsonb  not null default '{}'::jsonb,
  equip      jsonb  not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- Riwayat semua pergerakan uang (gift, topup, tukar, beli) untuk audit.
create table if not exists public.money_tx (
  id         bigserial primary key,
  uid        text not null,
  kind       text not null,
  item_id    text,
  amount     bigint not null default 0,
  room_id    text,
  created_at timestamptz not null default now()
);

alter table public.wallets   enable row level security;
alter table public.money_tx  enable row level security;
-- Tidak ada policy: aplikasi (anon) tidak bisa baca/tulis langsung.
-- Hanya service_role (Edge Function) yang bisa.

-- ================== FUNGSI LEVEL (rumus sama dengan app) ==================

create or replace function public.exp_for_level(n int)
returns bigint language sql immutable as $$
  select case when n <= 1 then 0
    else (round(3932550000.0 * power(n::double precision / 39.0, 2.5) / 10000.0))::bigint * 10000
  end
$$;

create or replace function public.level_from_exp(p_exp bigint)
returns int language plpgsql immutable as $$
declare l int := 1;
begin
  while l < 250 and p_exp >= public.exp_for_level(l + 1) loop
    l := l + 1;
  end loop;
  return l;
end
$$;

-- ================== FUNGSI DOMPET ==================

create or replace function public.w_get(p_uid text)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets;
begin
  select * into w from public.wallets where uid = p_uid;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  return w;
end
$$;

-- Migrasi saldo lama dari Firestore (dipanggil sekali per user, aman diulang).
create or replace function public.w_migrate(
  p_uid text, p_coins bigint, p_diamonds bigint, p_exp bigint,
  p_vip int, p_vip_until bigint, p_lv_claimed bigint,
  p_inv jsonb, p_equip jsonb)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets;
begin
  insert into public.wallets (uid, coins, diamonds, exp, vip, vip_until, lv_claimed, inv, equip)
  values (p_uid, greatest(coalesce(p_coins,0),0), greatest(coalesce(p_diamonds,0),0),
          greatest(coalesce(p_exp,0),0), greatest(coalesce(p_vip,0),0), coalesce(p_vip_until,0),
          coalesce(p_lv_claimed,0), coalesce(p_inv,'{}'), coalesce(p_equip,'{}'))
  on conflict (uid) do nothing;
  select * into w from public.wallets where uid = p_uid;
  return w;
end
$$;

create or replace function public.w_topup(p_uid text, p_coins bigint)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets;
begin
  if coalesce(p_coins,0) <= 0 then raise exception 'Jumlah tidak valid'; end if;
  update public.wallets set coins = coins + p_coins, updated_at = now()
    where uid = p_uid returning * into w;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  insert into public.money_tx (uid, kind, amount) values (p_uid, 'topup', p_coins);
  return w;
end
$$;

-- Kirim gift: coin berkurang, diamond pengirim bertambah sebesar harga gift.
create or replace function public.w_send_gift(
  p_uid text, p_price bigint, p_item_id text default null, p_room_id text default null)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; boost bigint;
begin
  if coalesce(p_price,0) <= 0 then raise exception 'Gift tidak valid'; end if;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  if w.coins < p_price then raise exception 'Coin tidak cukup. Tekan Topup'; end if;
  boost := case when w.vip between 1 and 9 and w.vip_until > (extract(epoch from now())*1000)::bigint
                then 100 + 5 * w.vip else 100 end;
  update public.wallets set
    coins    = coins - p_price,
    diamonds = diamonds + p_price,
    exp      = exp + (p_price * boost / 100),
    updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, item_id, amount, room_id)
    values (p_uid, 'gift', p_item_id, p_price, p_room_id);
  return w;
end
$$;

-- Tukar diamond ke coin: 2 diamond = 1 coin (sisa 1 diamond tetap tersimpan).
-- Penanda permintaan: satu nomor hanya diproses sekali, jadi app boleh
-- mengulang kalau jaringan putus tanpa risiko berlian terpotong dua kali.
create table if not exists public.exchange_req (
  req_id text primary key,
  uid text,
  at timestamptz not null default now()
);
revoke all on public.exchange_req from public, anon, authenticated;

drop function if exists public.w_exchange(text, bigint);

create or replace function public.w_exchange(p_uid text, p_diamonds bigint, p_req text default null)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; gain bigint;
begin
  if coalesce(p_diamonds,0) <= 0 then raise exception 'Jumlah tidak valid'; end if;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  -- Permintaan yang sama sudah pernah diproses: kembalikan saldo tanpa memotong lagi.
  if p_req is not null then
    insert into public.exchange_req (req_id, uid) values (p_req, p_uid)
      on conflict (req_id) do nothing;
    if not found then
      return w;
    end if;
  end if;
  if p_diamonds > w.diamonds then raise exception 'Diamond tidak cukup'; end if;
  gain := p_diamonds / 2;
  if gain < 1 then raise exception 'Minimal 2 diamond'; end if;
  update public.wallets set
    coins = coins + gain,
    diamonds = diamonds - gain * 2,
    updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, amount) values (p_uid, 'exchange', p_diamonds);
  return w;
end
$$;

-- Beli / perpanjang / upgrade VIP (harga tetap dari server, tidak bisa dimanipulasi app).
create or replace function public.w_buy_vip(p_uid text, p_tier int)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; price bigint; vuntil bigint; nowms bigint;
  active boolean; ninv jsonb; neq jsonb; cur text; i int; pre text; newvip int; newvipuntil bigint;
begin
  if p_tier not between 1 and 9 then raise exception 'Level VIP tidak valid'; end if;
  price := case p_tier
    when 1 then 500000 when 2 then 1500000 when 3 then 3000000 when 4 then 6000000
    when 5 then 12000000 when 6 then 25000000 when 7 then 50000000
    when 8 then 100000000 else 250000000 end;
  nowms := (extract(epoch from now())*1000)::bigint;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  active := w.vip between 1 and 9 and w.vip_until > nowms;
  if w.coins < price then raise exception 'Coin tidak cukup. Tekan Topup'; end if;
  -- BOLEH BELI LEVEL BERAPA PUN walau level lebih tinggi sudah aktif.
  vuntil := coalesce((w.inv ->> ('vipt_' || p_tier))::bigint, 0);
  if vuntil <= nowms then vuntil := nowms; end if;
  vuntil := vuntil + 30::bigint * 86400000::bigint;
  ninv := w.inv;
  -- BELI VIP N = SEMUA ITEM VIP 1..N OTOMATIS MASUK MY ITEM
  for i in 1..p_tier loop
    foreach pre in array array['vipt_','vipf_','vipb_','vipe_','vipbn_'] loop
      ninv := ninv || jsonb_build_object(pre || i, vuntil);
    end loop;
  end loop;
  -- Level dompet = level TERTINGGI; membeli level rendah tidak menurunkan level.
  newvip := w.vip;
  newvipuntil := w.vip_until;
  if p_tier > w.vip or not active then
    newvip := p_tier;
    newvipuntil := vuntil;
  elsif p_tier = w.vip then
    newvipuntil := vuntil;
  end if;
  -- Perlengkapan otomatis hanya dipasang kalau level naik / belum ada.
  neq := w.equip;
  if p_tier >= w.vip or not active then
    neq := jsonb_set(neq, '{frame}', to_jsonb('vipf_' || p_tier));
    cur := coalesce(neq ->> 'bubble', '');
    if cur = '' or left(cur, 5) = 'vipb_' then neq := jsonb_set(neq, '{bubble}', to_jsonb('vipb_' || p_tier)); end if;
    cur := coalesce(neq ->> 'entry', '');
    if cur = '' or left(cur, 5) = 'vipe_' then neq := jsonb_set(neq, '{entry}', to_jsonb('vipe_' || p_tier)); end if;
    cur := coalesce(neq ->> 'banner', '');
    if cur = '' or left(cur, 6) = 'vipbn_' then neq := jsonb_set(neq, '{banner}', to_jsonb('vipbn_' || p_tier)); end if;
  end if;
  update public.wallets set
    coins = coins - price, vip = newvip, vip_until = newvipuntil,
    inv = ninv, equip = neq, updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, item_id, amount) values (p_uid, 'buy_vip', 'vip' || p_tier, price);
  return w;
end
$$;

-- Beli item store (lencana, frame, dsb; harga dari katalog app, dicatat untuk audit).
create or replace function public.w_buy_item(
  p_uid text, p_item_id text, p_price bigint, p_days bigint)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; base bigint; nowms bigint;
begin
  if p_item_id is null or p_item_id = '' then raise exception 'Item tidak valid'; end if;
  if coalesce(p_days,0) <= 0 then raise exception 'Masa aktif item tidak valid'; end if;
  nowms := (extract(epoch from now())*1000)::bigint;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  if w.coins < p_price then raise exception 'Coin tidak cukup. Tekan Topup'; end if;
  base := greatest(nowms, coalesce((w.inv ->> p_item_id)::bigint, 0));
  update public.wallets set
    coins = coins - p_price,
    inv = w.inv || jsonb_build_object(p_item_id, base + p_days * 86400000),
    updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, item_id, amount) values (p_uid, 'buy_item', p_item_id, p_price);
  return w;
end
$$;

-- Pasang / lepas item: equip {slot: id} (id kosong = lepas).
create or replace function public.w_equip(p_uid text, p_slot text, p_id text)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets;
begin
  if p_slot is null or p_slot = '' then raise exception 'Slot tidak valid'; end if;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  update public.wallets set
    equip = w.equip || jsonb_build_object(p_slot, coalesce(p_id, '')),
    updated_at = now()
  where uid = p_uid returning * into w;
  return w;
end
$$;

-- Klaim hadiah level: reward = jumlah level baru x 243.000 coin.
create or replace function public.w_claim_level(p_uid text)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; lvl int; gain bigint := 0; l int;
begin
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  lvl := public.level_from_exp(w.exp);
  if lvl <= w.lv_claimed then raise exception 'Belum ada hadiah level untuk diklaim'; end if;
  for l in (w.lv_claimed + 1)..lvl loop
    gain := gain + l * 243000;
  end loop;
  update public.wallets set
    coins = coins + gain, lv_claimed = lvl, updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, amount) values (p_uid, 'claim_level', gain);
  return w;
end
$$;

-- Lengkapi item VIP yang belum masuk My Item (untuk VIP yang sedang aktif).
create or replace function public.w_backfill_vip(p_uid text)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; ninv jsonb; item text; i int;
begin
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  if not (w.vip between 1 and 9 and w.vip_until > (extract(epoch from now())*1000)::bigint) then
    return w;
  end if;
  ninv := w.inv;
  for i in 1..w.vip loop
    foreach item in array array['vipt_','vipf_','vipb_','vipe_','vipbn_'] loop
      if coalesce((ninv ->> (item || i))::bigint, 0) < w.vip_until then
        ninv := ninv || jsonb_build_object(item || i, w.vip_until);
      end if;
    end loop;
  end loop;
  update public.wallets set inv = ninv, updated_at = now()
    where uid = p_uid returning * into w;
  return w;
end
$$;

-- ================== HAK AKSES ==================
-- Hanya service_role (Edge Function) yang boleh menjalankan fungsi ini.
revoke all on function public.w_get(text) from public, anon, authenticated;
revoke all on function public.w_migrate(text,bigint,bigint,bigint,int,bigint,bigint,jsonb,jsonb) from public, anon, authenticated;
revoke all on function public.w_topup(text,bigint) from public, anon, authenticated;
revoke all on function public.w_send_gift(text,bigint,text,text) from public, anon, authenticated;
revoke all on function public.w_exchange(text,bigint,text) from public, anon, authenticated;
revoke all on function public.w_buy_vip(text,int) from public, anon, authenticated;
revoke all on function public.w_buy_item(text,text,bigint,bigint) from public, anon, authenticated;
revoke all on function public.w_equip(text,text,text) from public, anon, authenticated;
revoke all on function public.w_claim_level(text) from public, anon, authenticated;
revoke all on function public.w_backfill_vip(text) from public, anon, authenticated;
grant execute on function public.w_get(text) to service_role;
grant execute on function public.w_migrate(text,bigint,bigint,bigint,int,bigint,bigint,jsonb,jsonb) to service_role;
grant execute on function public.w_topup(text,bigint) to service_role;
grant execute on function public.w_send_gift(text,bigint,text,text) to service_role;
grant execute on function public.w_exchange(text,bigint,text) to service_role;
grant execute on function public.w_buy_vip(text,int) to service_role;
grant execute on function public.w_buy_item(text,text,bigint,bigint) to service_role;
grant execute on function public.w_equip(text,text,text) to service_role;
grant execute on function public.w_claim_level(text) to service_role;

-- Reset saldo semua user (dipakai tombol panel). Satu perintah, aman untuk
-- jumlah user besar; mengembalikan jumlah baris yang berubah.
create or replace function public.w_admin_reset_all(p_field text)
returns integer language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  if p_field = 'coins' then
    update public.wallets set coins = 0, updated_at = now() where coins <> 0;
  elsif p_field = 'diamonds' then
    update public.wallets set diamonds = 0, updated_at = now() where diamonds <> 0;
  else
    raise exception 'Kolom tidak dikenal';
  end if;
  get diagnostics n = row_count;
  return n;
end
$$;

revoke all on function public.w_admin_reset_all(text) from public, anon, authenticated;
grant execute on function public.w_admin_reset_all(text) to service_role;


grant execute on function public.w_backfill_vip(text) to service_role;
revoke all on public.money_tx from anon, authenticated;
grant select, insert on public.money_tx to service_role;

-- ================= topup.sql =================

-- =====================================================================
--  Topup coin lewat payment gateway (Midtrans)
--  Satu baris = satu transaksi. Coin hanya ditambah setelah webhook
--  pembayaran terverifikasi (lihat wallet-api.ts, aksi midtrans_notify).
-- =====================================================================

create table if not exists public.topup_orders (
  id          text primary key,
  uid         text not null,
  coins       bigint not null check (coins > 0),
  amount      bigint not null check (amount > 0),   -- rupiah
  provider    text not null default 'midtrans',
  status      text not null default 'pending',      -- pending | paid | failed | expired
  token       text,
  paid_at     bigint,
  created_at  bigint not null,
  raw         jsonb
);

create index if not exists topup_orders_uid_idx on public.topup_orders (uid, created_at desc);
create index if not exists topup_orders_status_idx on public.topup_orders (status);

-- Tabel hanya disentuh oleh edge function (service_role). Aplikasi tidak punya akses langsung.
alter table public.topup_orders enable row level security;
revoke all on table public.topup_orders from public, anon, authenticated;

-- Daftar order user (dipanggil lewat wallet-api dengan token login).
create or replace function public.w_topup_list(p_uid text, p_limit int default 30)
returns setof public.topup_orders
language sql security definer set search_path = public as $$
  select * from public.topup_orders
  where uid = p_uid
  order by created_at desc
  limit greatest(1, least(coalesce(p_limit, 30), 100));
$$;

revoke all on function public.w_topup_list(text,int) from public, anon, authenticated;
grant execute on function public.w_topup_list(text,int) to service_role;

-- ================= topup-manual.sql =================

-- =====================================================================
--  Topup manual lewat DANA (tanpa payment gateway, tanpa rekening bank)
--  User transfer ke nomor DANA admin, lalu admin menyetujui di panel
--  developer. Coin baru masuk setelah disetujui.
-- =====================================================================

create table if not exists public.app_cfg (
  k text primary key,
  v jsonb not null,
  updated_at timestamptz not null default now()
);
alter table public.app_cfg enable row level security;
revoke all on table public.app_cfg from public, anon, authenticated;

create table if not exists public.topup_manual (
  id          text primary key,
  uid         text not null,
  coins       bigint not null check (coins > 0),
  amount      bigint not null check (amount > 0),
  payer       text not null,
  status      text not null default 'pending',   -- pending | paid | rejected
  created_at  bigint not null,
  decided_at  bigint,
  method      text not null default 'dana'          -- dana | gopay | qris
);
create index if not exists topup_manual_status_idx on public.topup_manual (status, created_at desc);
create index if not exists topup_manual_uid_idx on public.topup_manual (uid, created_at desc);
alter table public.topup_manual enable row level security;
revoke all on table public.topup_manual from public, anon, authenticated;

-- Info pembayaran untuk aplikasi (nomor DANA + nama pemilik).
create or replace function public.w_pay_info()
returns jsonb language sql security definer set search_path = public as $$
  select coalesce((select v from public.app_cfg where k = 'pay'), '{}'::jsonb);
$$;

-- Simpan info pembayaran (dipanggil panel developer).
create or replace function public.w_pay_set(p_dana text, p_name text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v jsonb;
begin
  v := jsonb_build_object('dana', coalesce(p_dana, ''), 'name', coalesce(p_name, ''));
  insert into public.app_cfg (k, v, updated_at) values ('pay', v, now())
    on conflict (k) do update set v = excluded.v, updated_at = now();
  return v;
end;
$$;

-- User mengajukan topup manual.
create or replace function public.w_manual_create(
  p_id text, p_uid text, p_coins bigint, p_amount bigint, p_payer text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare jumlah int;
begin
  if coalesce(p_coins, 0) <= 0 or coalesce(p_amount, 0) <= 0 then
    raise exception 'Nominal tidak valid';
  end if;
  if coalesce(length(trim(p_payer)), 0) < 3 then
    raise exception 'Nama pengirim terlalu pendek';
  end if;
  select count(*) into jumlah from public.topup_manual
   where uid = p_uid and status = 'pending';
  if jumlah >= 3 then
    raise exception 'Masih ada pengajuan yang menunggu persetujuan';
  end if;
  insert into public.topup_manual (id, uid, coins, amount, payer, status, created_at)
  values (p_id, p_uid, p_coins, p_amount, trim(p_payer), 'pending',
          (extract(epoch from now()) * 1000)::bigint);
  return jsonb_build_object('id', p_id, 'status', 'pending');
end;
$$;

-- Riwayat topup manual milik user.
create or replace function public.w_manual_history(p_uid text, p_limit int default 20)
returns setof public.topup_manual
language sql security definer set search_path = public as $$
  select * from public.topup_manual
   where uid = p_uid
   order by created_at desc
   limit greatest(1, least(coalesce(p_limit, 20), 50));
$$;

alter table public.topup_manual add column if not exists method text not null default 'dana';

-- Daftar pengajuan untuk panel developer.
-- Nama pemain tidak disimpan di Supabase (ada di Firebase), jadi panel
-- mengambilnya sendiri dari Realtime Database.
drop function if exists public.w_manual_list(text, int);
create or replace function public.w_manual_list(p_status text default 'pending', p_limit int default 60)
returns table (id text, uid text, coins bigint, amount bigint, payer text,
               status text, created_at bigint, method text)
language sql security definer set search_path = public as $$
  select m.id, m.uid, m.coins, m.amount, m.payer, m.status, m.created_at,
         coalesce(m.method, 'dana') as method
    from public.topup_manual m
   where (coalesce(p_status, 'pending') = 'all' or m.status = p_status)
   order by m.created_at asc
   limit greatest(1, least(coalesce(p_limit, 60), 200));
$$;

-- Setujui / tolak pengajuan. Kalau disetujui, coin langsung ditambahkan.
create or replace function public.w_manual_decide(p_id text, p_ok boolean)
returns jsonb language plpgsql security definer set search_path = public as $$
declare r public.topup_manual;
begin
  select * into r from public.topup_manual where id = p_id for update;
  if not found then raise exception 'Pengajuan tidak ada'; end if;
  if r.status <> 'pending' then
    return jsonb_build_object('ok', false, 'e', 'Sudah diproses sebelumnya');
  end if;
  if p_ok then
    perform public.w_topup(r.uid, r.coins);
    update public.topup_manual
       set status = 'paid', decided_at = (extract(epoch from now()) * 1000)::bigint
     where id = p_id;
    return jsonb_build_object('ok', true, 'status', 'paid');
  else
    update public.topup_manual
       set status = 'rejected', decided_at = (extract(epoch from now()) * 1000)::bigint
     where id = p_id;
    return jsonb_build_object('ok', true, 'status', 'rejected');
  end if;
end;
$$;

revoke all on function public.w_pay_info() from public, anon, authenticated;
revoke all on function public.w_pay_set(text,text) from public, anon, authenticated;
revoke all on function public.w_manual_create(text,text,bigint,bigint,text) from public, anon, authenticated;
revoke all on function public.w_manual_history(text,int) from public, anon, authenticated;
revoke all on function public.w_manual_list(text,int) from public, anon, authenticated;
revoke all on function public.w_manual_decide(text,boolean) from public, anon, authenticated;

grant execute on function public.w_pay_info() to service_role;
grant execute on function public.w_pay_set(text,text) to service_role;
grant execute on function public.w_manual_create(text,text,bigint,bigint,text) to service_role;
grant execute on function public.w_manual_history(text,int) to service_role;
grant execute on function public.w_manual_list(text,int) to service_role;
grant execute on function public.w_manual_decide(text,boolean) to service_role;

-- Kalau tabel sudah pernah dibuat sebelum ada kolom metode, tambahkan tanpa menghapus data.
alter table public.topup_manual add column if not exists method text not null default 'dana';

-- Simpan 3 metode pembayaran sekaligus (DANA, GoPay, QRIS berupa gambar).
create or replace function public.w_pay_set_v2(p_dana text, p_name text, p_gopay text, p_qris text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v jsonb;
begin
  v := jsonb_build_object(
    'dana', coalesce(p_dana, ''),
    'name', coalesce(p_name, ''),
    'gopay', coalesce(p_gopay, ''),
    'qris', coalesce(p_qris, '')
  );
  insert into public.app_cfg (k, v, updated_at) values ('pay', v, now())
    on conflict (k) do update set v = excluded.v, updated_at = now();
  return v;
end;
$$;

-- Pengajuan dengan metode pembayaran.
create or replace function public.w_manual_create_v2(
  p_id text, p_uid text, p_coins bigint, p_amount bigint, p_payer text, p_method text
) returns jsonb language plpgsql security definer set search_path = public as $$
declare jumlah int;
begin
  if coalesce(p_coins, 0) <= 0 or coalesce(p_amount, 0) <= 0 then
    raise exception 'Nominal tidak valid';
  end if;
  if coalesce(length(trim(p_payer)), 0) < 3 then
    raise exception 'Nama pengirim terlalu pendek';
  end if;
  select count(*) into jumlah from public.topup_manual
   where uid = p_uid and status = 'pending';
  if jumlah >= 3 then
    raise exception 'Masih ada pengajuan yang menunggu persetujuan';
  end if;
  insert into public.topup_manual (id, uid, coins, amount, payer, status, created_at, method)
  values (p_id, p_uid, p_coins, p_amount, trim(p_payer), 'pending',
          (extract(epoch from now()) * 1000)::bigint,
          coalesce(nullif(trim(p_method), ''), 'dana'));
  return jsonb_build_object('id', p_id, 'status', 'pending');
end;
$$;

revoke all on function public.w_pay_set_v2(text,text,text,text) from public, anon, authenticated;
revoke all on function public.w_manual_create_v2(text,text,bigint,bigint,text,text) from public, anon, authenticated;
grant execute on function public.w_pay_set_v2(text,text,text,text) to service_role;
grant execute on function public.w_manual_create_v2(text,text,bigint,bigint,text,text) to service_role;

-- ================= topup-cepat.sql =================

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

-- ================= wave-game.sql =================

-- =====================================================================
--  REZO CHAT: integrasi game WaveGames (Teen Patti, Dragon Vs Tiger, dll)
--  Coin tetap di tabel wallets (Supabase). WaveGames hanya memanggil dua
--  endpoint di Edge Function wallet-api: userInfo dan submitFlow.
--  Jalankan file ini SEKALI di Supabase > SQL Editor.
-- =====================================================================

-- Profil pemain untuk WaveGames (nama + foto). Disimpan di sini supaya
-- Edge Function tidak perlu membaca Realtime Database.
create table if not exists public.wave_players (
  uid        text primary key,
  name       text not null default 'Player',
  avatar     text not null default '',
  updated_at timestamptz not null default now()
);

-- Catatan setiap transaksi game (idempotensi: transactionId unik).
create table if not exists public.wave_tx (
  tx_id      text primary key,
  uid        text not null,
  kind       int  not null,          -- 1 = potong (bet), 2 = tambah (menang)
  amount     bigint not null,
  game_name  text,
  balance    bigint,                 -- saldo setelah transaksi
  created_at timestamptz not null default now()
);

create index if not exists wave_tx_uid_idx on public.wave_tx (uid, created_at desc);

alter table public.wave_players enable row level security;
alter table public.wave_tx      enable row level security;
-- Tanpa policy: hanya service_role (Edge Function) yang bisa akses.

-- ---------- Profil pemain (dipanggil app saat membuka game) ----------
create or replace function public.w_wave_prepare(p_uid text, p_name text, p_avatar text)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets;
begin
  insert into public.wallets (uid) values (p_uid) on conflict (uid) do nothing;
  insert into public.wave_players (uid, name, avatar, updated_at)
  values (p_uid, coalesce(nullif(p_name, ''), 'Player'), coalesce(p_avatar, ''), now())
  on conflict (uid) do update
    set name = coalesce(nullif(excluded.name, ''), public.wave_players.name),
        avatar = coalesce(excluded.avatar, public.wave_players.avatar),
        updated_at = now();
  select * into w from public.wallets where uid = p_uid;
  return w;
end
$$;

-- ---------- Profil pemain untuk userInfo WaveGames ----------
-- Mengembalikan satu baris: id, name, balance, avatar, exists.
create or replace function public.w_wave_player(p_uid text)
returns table (id text, name text, balance bigint, avatar text, exists_flag boolean)
language plpgsql security definer set search_path = public as $$
declare w public.wallets;
declare pl public.wave_players;
begin
  select * into w from public.wallets where uid = p_uid;
  select * into pl from public.wave_players where uid = p_uid;
  return query select
    p_uid,
    coalesce(pl.name, 'Player'),
    coalesce(w.coins, 0),
    coalesce(pl.avatar, ''),
    (w.uid is not null);
end
$$;

-- ---------- Potong / tambah coin dari game ----------
-- p_kind: 1 = bet (potong), 2 = menang (tambah). p_tx = transactionId unik
-- dari WaveGames, dipakai agar satu transaksi tidak diproses dua kali.
create or replace function public.w_wave_flow(
  p_uid text, p_amount bigint, p_kind int, p_tx text, p_game text default null
)
returns table (coins bigint, duplicated boolean)
language plpgsql security definer set search_path = public as $$
#variable_conflict use_column
declare w public.wallets;
      amt bigint := coalesce(p_amount, 0);
      prev public.wave_tx;
begin
  if amt < 0 then raise exception 'NOMINAL_TIDAK_VALID'; end if;
  if p_kind not in (1, 2) then raise exception 'TIPE_TIDAK_VALID'; end if;
  if coalesce(p_tx, '') = '' then raise exception 'TX_KOSONG'; end if;

  insert into public.wallets as wl (uid) values (p_uid) on conflict (uid) do nothing;

  -- Sudah pernah diproses? kembalikan saldo terakhir tanpa mengubah apa pun.
  select * into prev from public.wave_tx t where t.tx_id = p_tx;
  if found then
    select * into w from public.wallets x where x.uid = p_uid;
    return query select w.coins::bigint, true;
    return;
  end if;

  if p_kind = 1 then
    update public.wallets as wl
       set coins = wl.coins - amt, updated_at = now()
     where wl.uid = p_uid and wl.coins >= amt
    returning wl.* into w;
    if w.uid is null then raise exception 'SALDO_KURANG'; end if;
  else
    update public.wallets as wl
       set coins = wl.coins + amt, updated_at = now()
     where wl.uid = p_uid
    returning wl.* into w;
  end if;

  insert into public.wave_tx (tx_id, uid, kind, amount, game_name, balance)
  values (p_tx, p_uid, p_kind, amt, p_game, w.coins)
  on conflict (tx_id) do nothing;

  insert into public.money_tx (uid, kind, item_id, amount)
  values (p_uid, case when p_kind = 1 then 'game_bet' else 'game_win' end,
          coalesce(p_game, ''), case when p_kind = 1 then -amt else amt end);

  return query select w.coins::bigint, false;
end
$$;

-- ================= gift-credit.sql =================

-- Tambah diamond + EXP ke dompet PENERIMA gift.
-- Jalankan di Supabase > SQL Editor (sekali saja). Kalau belum dijalankan,
-- edge function wallet-api tetap jalan memakai cara cadangan.
create or replace function public.w_gift_credit(p_to text, p_amount bigint)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets;
begin
  if coalesce(p_amount,0) <= 0 then raise exception 'Jumlah tidak valid'; end if;
  select * into w from public.wallets where uid = p_to for update;
  if not found then
    insert into public.wallets (uid, diamonds, exp) values (p_to, p_amount, p_amount)
      returning * into w;
    return w;
  end if;
  update public.wallets set
    diamonds = diamonds + p_amount,
    exp      = exp + p_amount,
    updated_at = now()
  where uid = p_to returning * into w;
  return w;
end
$$;

revoke all on function public.w_gift_credit(text,bigint) from public, anon, authenticated;
grant execute on function public.w_gift_credit(text,bigint) to service_role;

-- ================= rocket-reward.sql =================

-- Hadiah roket: tambah coin dan/atau VIP langsung ke dompet pemain.
-- Jalankan sekali di Supabase > SQL Editor.
create or replace function public.w_rocket_reward(
  p_uid text, p_coins bigint, p_vip int, p_days int)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; until_ts bigint;
begin
  until_ts := (extract(epoch from now())*1000)::bigint
            + greatest(coalesce(p_days,0),0)::bigint * 86400000;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then
    insert into public.wallets (uid) values (p_uid) returning * into w;
  end if;
  update public.wallets set
    coins     = coins + coalesce(p_coins,0),
    vip       = case when coalesce(p_vip,0) > vip then p_vip else vip end,
    vip_until = case when coalesce(p_vip,0) > 0 then greatest(vip_until, until_ts) else vip_until end,
    updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, amount) values (p_uid, 'rocket', coalesce(p_coins,0));
  return w;
end
$$;

revoke all on function public.w_rocket_reward(text,bigint,int,int) from public, anon, authenticated;
grant execute on function public.w_rocket_reward(text,bigint,int,int) to service_role;

-- ================= FIX-GAME-COIN.sql =================

create or replace function public.w_wave_flow(
  p_uid text, p_amount bigint, p_kind int, p_tx text, p_game text default null
)
returns table (coins bigint, duplicated boolean)
language plpgsql security definer set search_path = public as $$
#variable_conflict use_column
declare w public.wallets;
      amt bigint := coalesce(p_amount, 0);
      prev public.wave_tx;
begin
  if amt < 0 then raise exception 'NOMINAL_TIDAK_VALID'; end if;
  if p_kind not in (1, 2) then raise exception 'TIPE_TIDAK_VALID'; end if;
  if coalesce(p_tx, '') = '' then raise exception 'TX_KOSONG'; end if;

  insert into public.wallets as wl (uid) values (p_uid) on conflict (uid) do nothing;

  -- Sudah pernah diproses? kembalikan saldo terakhir tanpa mengubah apa pun.
  select * into prev from public.wave_tx t where t.tx_id = p_tx;
  if found then
    select * into w from public.wallets x where x.uid = p_uid;
    return query select w.coins::bigint, true;
    return;
  end if;

  if p_kind = 1 then
    update public.wallets as wl
       set coins = wl.coins - amt, updated_at = now()
     where wl.uid = p_uid and wl.coins >= amt
    returning wl.* into w;
    if w.uid is null then raise exception 'SALDO_KURANG'; end if;
  else
    update public.wallets as wl
       set coins = wl.coins + amt, updated_at = now()
     where wl.uid = p_uid
    returning wl.* into w;
  end if;

  insert into public.wave_tx (tx_id, uid, kind, amount, game_name, balance)
  values (p_tx, p_uid, p_kind, amt, p_game, w.coins)
  on conflict (tx_id) do nothing;

  insert into public.money_tx (uid, kind, item_id, amount)
  values (p_uid, case when p_kind = 1 then 'game_bet' else 'game_win' end,
          coalesce(p_game, ''), case when p_kind = 1 then -amt else amt end);

  return query select w.coins::bigint, false;
end
$$;

-- ================= room-image.sql =================

-- =====================================================================
--  Kirim gambar di chat room (Supabase Storage)
--  Jalankan di Supabase -> SQL Editor -> Run. Aman diulang.
--  Bucket publik "chatimg": gambar chat diunggah app langsung ke sini.
-- =====================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chatimg', 'chatimg', true, 2097152, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update
  set public = true,
      file_size_limit = 2097152,
      allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp'];

-- Semua orang boleh melihat gambar (bucket publik).
drop policy if exists chatimg_baca on storage.objects;
create policy chatimg_baca on storage.objects
  for select using (bucket_id = 'chatimg');

-- Aplikasi (anon key) boleh mengunggah; ukuran dan jenis dibatasi di bucket.
drop policy if exists chatimg_unggah on storage.objects;
create policy chatimg_unggah on storage.objects
  for insert to anon, authenticated with check (bucket_id = 'chatimg');
