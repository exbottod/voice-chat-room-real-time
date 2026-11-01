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
create or replace function public.w_manual_list(p_status text default 'pending', p_limit int default 60)
returns table (id text, uid text, coins bigint, amount bigint, payer text,
               status text, created_at bigint, name text, method text)
language sql security definer set search_path = public as $$
  select m.id, m.uid, m.coins, m.amount, m.payer, m.status, m.created_at,
         coalesce(w.name, '') as name, coalesce(m.method, 'dana') as method
    from public.topup_manual m
    left join public.wallets w on w.uid = m.uid
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
