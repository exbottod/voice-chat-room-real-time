-- ================================================================
-- Skema dompet REZO untuk Turso (SQLite).
-- Struktur kolom sengaja sama dengan versi Postgres supaya aplikasi
-- tidak perlu diubah saat membaca saldo.
-- Jalankan sekali di database Turso lewat menu SQL / turso db shell.
-- ================================================================

-- Saldo utama tiap pengguna.
create table if not exists wallets (
  uid        text primary key,
  coins      integer not null default 0,
  diamonds   integer not null default 0,
  exp        integer not null default 0,
  vip        integer not null default 0,
  vip_until  integer not null default 0,
  lv_claimed integer not null default 0,
  inv        text    not null default '{}',
  equip      text    not null default '{}',
  updated_at integer not null default (unixepoch() * 1000)
);

-- Riwayat pergerakan uang untuk audit dan pemulihan.
create table if not exists money_tx (
  id      integer primary key autoincrement,
  uid     text not null,
  kind    text not null,
  item_id text,
  amount  integer not null default 0,
  room_id text,
  created_at integer not null default (unixepoch() * 1000)
);
create index if not exists money_tx_uid_idx on money_tx (uid, id desc);
create index if not exists money_tx_at_idx  on money_tx (created_at);

-- Penanda permintaan: satu nomor hanya diproses sekali.
-- Inilah yang mencegah coin terpotong dua kali saat jaringan putus
-- lalu aplikasi mengulang permintaan yang sama.
create table if not exists gift_req (
  req_id text primary key,
  uid    text not null,
  at     integer not null default (unixepoch() * 1000)
);

create table if not exists exchange_req (
  req_id text primary key,
  uid    text not null,
  at     integer not null default (unixepoch() * 1000)
);

create table if not exists topup_req (
  req_id text primary key,
  uid    text not null,
  at     integer not null default (unixepoch() * 1000)
);

-- Pesanan game Hive: dipakai agar bet dan win tidak dihitung dua kali.
create table if not exists hive_order (
  order_id   text primary key,
  uid        text not null,
  kind       integer not null,
  amount     integer not null,
  round_id   text,
  game       text,
  at         integer not null default (unixepoch() * 1000)
);
create index if not exists hive_order_round_idx on hive_order (round_id);

-- Katalog store dan gift (dulu di Supabase, sekarang ikut di sini
-- supaya worker bisa menghitung harga tanpa bertanya ke tempat lain).
create table if not exists store_item (
  id       text primary key,
  kind     text not null,          -- frame | medal | effect | gift | gift_cb
  name     text not null default '',
  price    integer not null default 0,
  currency text not null default 'coins',
  days     integer not null default 0,
  data     text not null default '{}',
  active   integer not null default 1
);
create index if not exists store_item_kind_idx on store_item (kind, active);

-- Antrean permintaan VIP, supaya pembelian yang terkirim dua kali
-- tidak memotong coin dua kali.
create table if not exists vip_req (
  req_id text primary key,
  uid    text not null,
  at     integer not null default (unixepoch() * 1000)
);

-- Profil pemain untuk WaveGames/Hive (dulu tabel wave_players di Supabase).
create table if not exists wave_players (
  uid        text primary key,
  name       text not null default 'Player',
  avatar     text not null default '',
  updated_at integer not null default (unixepoch() * 1000)
);
