create or replace function public.w_wave_flow(
  p_uid text, p_amount bigint, p_kind int, p_tx text, p_game text default null
)
returns table (coins bigint, duplicated boolean)
language plpgsql security definer set search_path = public as $$
#variable_conflict use_column
declare w public.wallets;
      amt bigint := coalesce(p_amount, 0);
      prev public.wave_tx;
begin
  if amt < 0 then raise exception 'NOMINAL_TIDAK_VALID'; end if;
  if p_kind not in (1, 2) then raise exception 'TIPE_TIDAK_VALID'; end if;
  if coalesce(p_tx, '') = '' then raise exception 'TX_KOSONG'; end if;

  insert into public.wallets as wl (uid) values (p_uid) on conflict (uid) do nothing;

  -- Sudah pernah diproses? kembalikan saldo terakhir tanpa mengubah apa pun.
  select * into prev from public.wave_tx t where t.tx_id = p_tx;
  if found then
    select * into w from public.wallets x where x.uid = p_uid;
    return query select w.coins::bigint, true;
    return;
  end if;

  if p_kind = 1 then
    update public.wallets as wl
       set coins = wl.coins - amt, updated_at = now()
     where wl.uid = p_uid and wl.coins >= amt
    returning wl.* into w;
    if w.uid is null then raise exception 'SALDO_KURANG'; end if;
  else
    update public.wallets as wl
       set coins = wl.coins + amt, updated_at = now()
     where wl.uid = p_uid
    returning wl.* into w;
  end if;

  insert into public.wave_tx (tx_id, uid, kind, amount, game_name, balance)
  values (p_tx, p_uid, p_kind, amt, p_game, w.coins)
  on conflict (tx_id) do nothing;

  insert into public.money_tx (uid, kind, item_id, amount)
  values (p_uid, case when p_kind = 1 then 'game_bet' else 'game_win' end,
          coalesce(p_game, ''), case when p_kind = 1 then -amt else amt end);

  return query select w.coins::bigint, false;
end
$$;
