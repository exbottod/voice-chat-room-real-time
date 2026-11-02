-- Patch dompet: jalankan SATU BAGIAN sekali jalan. Kalau satu bagian error,
-- lanjutkan bagian berikutnya.

-- ====== BAGIAN 1: reset coin / diamond SEMUA user tanpa HTTP 546 ======
create or replace function public.w_admin_reset_all(p_field text)
returns integer language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  if p_field = 'coins' then
    update public.wallets set coins = 0, updated_at = now() where coins <> 0;
  elsif p_field = 'diamonds' then
    update public.wallets set diamonds = 0, updated_at = now() where diamonds <> 0;
  else
    raise exception 'Kolom tidak dikenal';
  end if;
  get diagnostics n = row_count;
  return n;
end
$$;

revoke all on function public.w_admin_reset_all(text) from public, anon, authenticated;
grant execute on function public.w_admin_reset_all(text) to service_role;
