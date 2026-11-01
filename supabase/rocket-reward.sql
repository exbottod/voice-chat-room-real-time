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
