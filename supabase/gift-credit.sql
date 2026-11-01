-- Tambah diamond + EXP ke dompet PENERIMA gift.
-- Jalankan di Supabase > SQL Editor (sekali saja). Kalau belum dijalankan,
-- edge function wallet-api tetap jalan memakai cara cadangan.
create or replace function public.w_gift_credit(p_to text, p_amount bigint)
returns public.wallets language plpgsql security definer set search_path = public as $$
declare w public.wallets;
begin
  if coalesce(p_amount,0) <= 0 then raise exception 'Jumlah tidak valid'; end if;
  select * into w from public.wallets where uid = p_to for update;
  if not found then
    insert into public.wallets (uid, diamonds, exp) values (p_to, p_amount, p_amount)
      returning * into w;
    return w;
  end if;
  update public.wallets set
    diamonds = diamonds + p_amount,
    exp      = exp + p_amount,
    updated_at = now()
  where uid = p_to returning * into w;
  return w;
end
$$;

revoke all on function public.w_gift_credit(text,bigint) from public, anon, authenticated;
grant execute on function public.w_gift_credit(text,bigint) to service_role;
