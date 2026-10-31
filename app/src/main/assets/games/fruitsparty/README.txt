FRUITS PARTY - Jackpot Fruit Game
Sumber: proyek Lovable FruitsParty.tsx (dibaca ulang dan dirapikan).

Nilai bayaran buah: lemon 5x, raspberry 5x, kiwi 5x, plum 5x,
banana 10x, strawberry 10x, watermelon 20x, cherry 40x.

Bonus tiap ronde: 55% tanpa bonus, +1 buah 13%, +2 buah 9%,
+3 buah 5%, pengali x2 10%, pengali x3 6%, jackpot 2%.

Porsi jackpot dari total taruhan: 1.000-9.999 dapat 10%,
10.000-99.999 dapat 30%, 100.000-999.999 dapat 50%,
1.000.000 ke atas dapat 70%. Bagian pemenang = 5% dari pool x porsi.

Waktu: fase taruhan 20 detik, fase hasil 5 detik.
Chip taruhan: 50K, 500K, 1M, 5M.

Jembatan wallet (diisi di dalam APK):
window.RezoBridge.getBalance() -> saldo coin
window.RezoBridge.bet(amount)    -> true jika potong saldo berhasil
window.RezoBridge.win(amount)    -> tambah kemenangan
Tanpa bridge, game memakai saldo lokal 10.000.000 untuk uji coba.
