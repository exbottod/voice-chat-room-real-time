-- ============================================================
-- BELI SVIP n: potong koin + SEMUA item SVIP 1..n masuk My Item
-- (frame svf_, efek masuk sve_, banner svbn_, bubble svb_,
--  lencana svbd_, medal svm_) sampai masa aktif SVIP n.
-- Jalankan di Supabase SQL Editor (aman dijalankan berulang).
-- ============================================================
create or replace function public.w_buy_svip(p_uid text, p_tier int)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; price bigint; until bigint; nowms bigint;
  inv jsonb; eq jsonb; cur text; i int; pre text;
begin
  if p_tier not between 1 and 9 then raise exception 'Level SVIP tidak valid'; end if;
  price := case p_tier
    when 1 then 500000 when 2 then 1500000 when 3 then 3000000 when 4 then 6000000
    when 5 then 12000000 when 6 then 25000000 when 7 then 50000000
    when 8 then 100000000 else 250000000 end;
  nowms := (extract(epoch from now())*1000)::bigint;
  select * into w from public.wallets where uid = p_uid for update;
  if not found then raise exception 'WALLET_NOT_FOUND'; end if;
  if w.coins < price then raise exception 'Coin tidak cukup. Tekan Topup'; end if;
  until := coalesce((w.inv ->> ('svm_' || p_tier))::bigint, 0);
  if until <= nowms then until := nowms; end if;
  until := until + 30 * 86400000;
  inv := w.inv;
  for i in 1..p_tier loop
    foreach pre in array array['svf_','sve_','svbn_','svb_','svbd_','svm_'] loop
      if coalesce((inv ->> (pre || i))::bigint, 0) < until then
        inv := inv || jsonb_build_object(pre || i, until);
      end if;
    end loop;
  end loop;
  eq := w.equip;
  cur := coalesce(eq ->> 'frame', '');
  if cur = '' or left(cur, 3) = 'svf' or left(cur, 5) = 'vipf_' then eq := jsonb_set(eq, '{frame}', to_jsonb('svf_' || p_tier)); end if;
  cur := coalesce(eq ->> 'entry', '');
  if cur = '' or left(cur, 4) = 'sve_' or left(cur, 5) = 'vipe_' then eq := jsonb_set(eq, '{entry}', to_jsonb('sve_' || p_tier)); end if;
  cur := coalesce(eq ->> 'banner', '');
  if cur = '' or left(cur, 5) = 'svbn_' or left(cur, 6) = 'vipbn_' then eq := jsonb_set(eq, '{banner}', to_jsonb('svbn_' || p_tier)); end if;
  cur := coalesce(eq ->> 'badge', '');
  if cur = '' or left(cur, 5) = 'svbd_' then eq := jsonb_set(eq, '{badge}', to_jsonb('svbd_' || p_tier)); end if;
  update public.wallets set
    coins = coins - price, inv = inv, equip = eq, updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, item_id, amount) values (p_uid, 'buy_svip', 'svip' || p_tier, price);
  return w;
end
$$;
revoke all on function public.w_buy_svip(text,int) from public, anon, authenticated;
grant execute on function public.w_buy_svip(text,int) to service_role;
