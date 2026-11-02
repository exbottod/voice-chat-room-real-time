-- BAGIAN 2: tukar berlian aman diulang (tanpa mengubah tabel money_tx,
-- supaya tidak menunggu kunci tabel besar).
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
  if p_req is not null then
    insert into public.exchange_req (req_id, uid) values (p_req, p_uid)
      on conflict (req_id) do nothing;
    if not found then
      -- Permintaan yang sama sudah pernah diproses: jangan potong lagi.
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

revoke all on function public.w_exchange(text, bigint, text) from public, anon, authenticated;
grant execute on function public.w_exchange(text, bigint, text) to service_role;
