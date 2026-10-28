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
declare w public.wallets;
      amt bigint := coalesce(p_amount, 0);
      prev public.wave_tx;
begin
  if amt < 0 then raise exception 'NOMINAL_TIDAK_VALID'; end if;
  if p_kind not in (1, 2) then raise exception 'TIPE_TIDAK_VALID'; end if;
  if coalesce(p_tx, '') = '' then raise exception 'TX_KOSONG'; end if;

  insert into public.wallets (uid) values (p_uid) on conflict (uid) do nothing;

  -- Sudah pernah diproses? kembalikan saldo terakhir tanpa mengubah apa pun.
  select * into prev from public.wave_tx where tx_id = p_tx;
  if found then
    select * into w from public.wallets where uid = p_uid;
    return query select w.coins, true;
    return;
  end if;

  if p_kind = 1 then
    update public.wallets set coins = coins - amt, updated_at = now()
      where uid = p_uid and coins >= amt
      returning * into w;
    if w.uid is null then raise exception 'SALDO_KURANG'; end if;
  else
    update public.wallets set coins = coins + amt, updated_at = now()
      where uid = p_uid
      returning * into w;
  end if;

  insert into public.wave_tx (tx_id, uid, kind, amount, game_name, balance)
  values (p_tx, p_uid, p_kind, amt, p_game, w.coins)
  on conflict (tx_id) do nothing;

  insert into public.money_tx (uid, kind, item_id, amount)
  values (p_uid, case when p_kind = 1 then 'game_bet' else 'game_win' end,
          coalesce(p_game, ''), case when p_kind = 1 then -amt else amt end);

  return query select w.coins, false;
end
$$;
