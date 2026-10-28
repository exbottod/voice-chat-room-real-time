-- FIX BELI VIP ("integer out of range" + column reference "inv" is ambiguous)
-- Supabase > SQL Editor > New query > tempel semua > Run.
-- Aman dijalankan berulang.

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

grant execute on function public.w_buy_vip(text,int) to service_role;
grant execute on function public.w_backfill_vip(text) to service_role;
