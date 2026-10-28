-- FIX: "integer out of range" saat beli VIP/SVIP (30 hari dalam milidetik melebihi batas integer).
-- Jalankan di Supabase > SQL Editor > Run.

create or replace function public.w_buy_vip(p_uid text, p_tier int)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets; price bigint; until bigint; nowms bigint;
  active boolean; inv jsonb; eq jsonb; cur text; i int; pre text;
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
  -- Item tier yang dibeli: perpanjang 30 hari dari sisa item tier itu.
  until := coalesce((w.inv ->> ('vipt_' || p_tier))::bigint, 0);
  if until <= nowms then until := nowms; end if;
  until := until + 30::bigint * 86400000::bigint;
  inv := w.inv;
  -- BELI VIP N = SEMUA ITEM VIP 1..N OTOMATIS MASUK MY ITEM
  -- (gelar, frame, gelembung, efek masuk, banner) sampai masa aktif VIP N.
  for i in 1..p_tier loop
    foreach pre in array array['vipt_','vipf_','vipb_','vipe_','vipbn_'] loop
      inv := inv || jsonb_build_object(pre || i, until);
    end loop;
  end loop;
  -- Level dompet = level TERTINGGI; membeli level rendah tidak menurunkan level.
  if p_tier > w.vip or not active then
    w.vip := p_tier;
    w.vip_until := until;
  elsif p_tier = w.vip then
    w.vip_until := until;
  end if;
  -- Perlengkapan otomatis hanya dipasang kalau level naik / belum ada.
  eq := w.equip;
  if p_tier >= w.vip or not active then
    eq := jsonb_set(eq, '{frame}', to_jsonb('vipf_' || p_tier));
    cur := coalesce(eq ->> 'bubble', '');
    if cur = '' or left(cur, 5) = 'vipb_' then eq := jsonb_set(eq, '{bubble}', to_jsonb('vipb_' || p_tier)); end if;
    cur := coalesce(eq ->> 'entry', '');
    if cur = '' or left(cur, 5) = 'vipe_' then eq := jsonb_set(eq, '{entry}', to_jsonb('vipe_' || p_tier)); end if;
    cur := coalesce(eq ->> 'banner', '');
    if cur = '' or left(cur, 6) = 'vipbn_' then eq := jsonb_set(eq, '{banner}', to_jsonb('vipbn_' || p_tier)); end if;
  end if;
  update public.wallets set
    coins = coins - price, vip = w.vip, vip_until = w.vip_until,
    inv = inv, equip = eq, updated_at = now()
  where uid = p_uid returning * into w;
  insert into public.money_tx (uid, kind, item_id, amount) values (p_uid, 'buy_vip', 'vip' || p_tier, price);
  return w;
end
$$;

grant execute on function public.w_buy_vip(text,int) to service_role;
