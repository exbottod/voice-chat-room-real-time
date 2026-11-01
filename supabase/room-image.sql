-- =====================================================================
--  Kirim gambar di chat room (Supabase Storage)
--  Jalankan di Supabase -> SQL Editor -> Run. Aman diulang.
--  Bucket publik "chatimg": gambar chat diunggah app langsung ke sini.
-- =====================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chatimg', 'chatimg', true, 2097152, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update
  set public = true,
      file_size_limit = 2097152,
      allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp'];

-- Semua orang boleh melihat gambar (bucket publik).
drop policy if exists chatimg_baca on storage.objects;
create policy chatimg_baca on storage.objects
  for select using (bucket_id = 'chatimg');

-- Aplikasi (anon key) boleh mengunggah; ukuran dan jenis dibatasi di bucket.
drop policy if exists chatimg_unggah on storage.objects;
create policy chatimg_unggah on storage.objects
  for insert to anon, authenticated with check (bucket_id = 'chatimg');
