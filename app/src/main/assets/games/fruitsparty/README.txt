FRUITS PARTY (dari fruitpartygame.zip)

Semua kode, komponen, aset, dan loading background diambil dari zip
yang dikirim owner. Sumbernya proyek React/Vite (src/components/FruitsParty.tsx,
src/lib/fruits.ts, src/styles.css). Halaman ini hasil build statis
(vite build --config vite.game.config.ts) supaya bisa dibuka WebView.

Perubahan hanya dua, di luar tampilan:
1. Saldo, taruhan, dan kemenangan memakai window.AndroidGame
   (balance / bet / win / report) jadi coin tercatat di Supabase.
2. Warna oklch diganti hex agar tetap tampil di WebView Android 8/9.

Jalan tanpa APK: buka index.html di browser, saldo uji coba 10 juta.
