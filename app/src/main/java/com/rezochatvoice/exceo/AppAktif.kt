package com.rezochatvoice.exceo

/**
 * Penanda apakah aplikasi sedang terbuka di layar. Dipakai untuk mengatur
 * seberapa sering data diambil dari server: cepat saat sedang dilihat,
 * lambat saat app ditinggal di latar belakang. Ini yang membuat saldo tetap
 * segar tanpa menghabiskan kuota Supabase.
 */
object AppAktif {
    @Volatile
    var ya: Boolean = true
}
