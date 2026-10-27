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
create or replace function public.w_exchange(p_uid text, p_diamonds bigint)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; gain bigint;
begin
  if coalesce(p_diamonds,0) <= 0 then raise exception 'Jumlah tidak valid'; end if;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
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
declare w public.wallets; price bigint; until bigint; nowms bigint;
  active boolean; inv jsonb; eq jsonb; cur text;
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
  if active and w.vip > p_tier then raise exception('VIP% kamu masih aktif', w.vip); end if;
  if w.coins < price then raise exception 'Coin tidak cukup. Tekan Topup'; end if;
  until := (case when active and w.vip = p_tier then w.vip_until else nowms end) + 30 * 86400000;
  inv := w.inv || jsonb_build_object(
    'vipt_' || p_tier, until, 'vipf_' || p_tier, until,
    'vipb_' || p_tier, until, 'vipe_' || p_tier, until, 'vipbn_' || p_tier, until);
  eq := w.equip;
  eq := jsonb_set(eq, '{frame}', to_jsonb('vipf_' || p_tier));
  cur := coalesce(eq ->> 'bubble', '');
  if cur = '' or left(cur, 5) = 'vipb_' then eq := jsonb_set(eq, '{bubble}', to_jsonb('vipb_' || p_tier)); end if;
  cur := coalesce(eq ->> 'entry', '');
  if cur = '' or left(cur, 5) = 'vipe_' then eq := jsonb_set(eq, '{entry}', to_jsonb('vipe_' || p_tier)); end if;
  cur := coalesce(eq ->> 'banner', '');
  if cur = '' or left(cur, 6) = 'vipbn_' then eq := jsonb_set(eq, '{banner}', to_jsonb('vipbn_' || p_tier)); end if;
  update public.wallets set
    coins = coins - price, vip = p_tier, vip_until = until,
    inv = inv, equip = eq, updated_at = now()
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
declare w public.wallets; inv jsonb; id text;
begin
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  if not (w.vip between 1 and 9 and w.vip_until > (extract(epoch from now())*1000)::bigint) then
    return w;
  end if;
  inv := w.inv;
  foreach id in array array['vipt_','vipf_','vipb_','vipe_','vipbn_'] loop
    id := id || w.vip;
    if coalesce((inv ->> id)::bigint, 0) < w.vip_until then
      inv := inv || jsonb_build_object(id, w.vip_until);
    end if;
  end loop;
  update public.wallets set inv = inv, updated_at = now()
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
revoke all on function public.w_exchange(text,bigint) from public, anon, authenticated;
revoke all on function public.w_buy_vip(text,int) from public, anon, authenticated;
revoke all on function public.w_buy_item(text,text,bigint,bigint) from public, anon, authenticated;
revoke all on function public.w_equip(text,text,text) from public, anon, authenticated;
revoke all on function public.w_claim_level(text) from public, anon, authenticated;
revoke all on function public.w_backfill_vip(text) from public, anon, authenticated;
grant execute on function public.w_get(text) to service_role;
grant execute on function public.w_migrate(text,bigint,bigint,bigint,int,bigint,bigint,jsonb,jsonb) to service_role;
grant execute on function public.w_topup(text,bigint) to service_role;
grant execute on function public.w_send_gift(text,bigint,text,text) to service_role;
grant execute on function public.w_exchange(text,bigint) to service_role;
grant execute on function public.w_buy_vip(text,int) to service_role;
grant execute on function public.w_buy_item(text,text,bigint,bigint) to service_role;
grant execute on function public.w_equip(text,text,text) to service_role;
grant execute on function public.w_claim_level(text) to service_role;
grant execute on function public.w_backfill_vip(text) to service_role;
revoke all on public.money_tx from anon, authenticated;
grant select, insert on public.money_tx to service_role;
