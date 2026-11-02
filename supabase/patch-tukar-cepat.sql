-- Patch: tukar berlian jadi coin lebih tahan jaringan lambat.
-- Jalankan sekali di Supabase > SQL Editor.

alter table public.money_tx add column if not exists req_id text;
create unique index if not exists money_tx_req_uniq on public.money_tx (req_id) where req_id is not null;

drop function if exists public.w_exchange(text, bigint);

create or replace function public.w_exchange(p_uid text, p_diamonds bigint, p_req text default null)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; gain bigint;
begin
  if coalesce(p_diamonds,0) <= 0 then raise exception 'Jumlah tidak valid'; end if;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  if p_req is not null and exists (select 1 from public.money_tx where req_id = p_req) then
    return w;
  end if;
  if p_diamonds > w.diamonds then raise exception 'Diamond tidak cukup'; end if;
  gain := p_diamonds / 2;
  if gain < 1 then raise exception 'Minimal 2 diamond'; end if;
  update public.wallets set
    coins = coins + gain,
    diamonds = diamonds - gain * 2,
    updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, amount, req_id) values (p_uid, 'exchange', p_diamonds, p_req);
  return w;
end
$$;

revoke all on function public.w_exchange(text, bigint, text) from public, anon, authenticated;
grant execute on function public.w_exchange(text, bigint, text) to service_role;
