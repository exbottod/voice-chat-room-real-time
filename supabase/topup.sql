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
