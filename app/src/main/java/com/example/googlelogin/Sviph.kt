package com.example.googlelogin

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Daftar privilege SVIP. Membeli SVIP n otomatis membuka semua baris minVip <= n. */
data class SvipPriv(
    val key: String,
    val title: String,
    val desc: String,
    val minVip: Int,
    val arrow: Boolean = false,
)

val SVIP_PRIVS = listOf(
    SvipPriv("muteRingtone", "Mute ringtone", "Activate this privilege to mute the ringtone for incoming messages.", 1),
    SvipPriv("hideGiftBanner", "Hide top gift banners", "When you enable this option, the gift banner at the top of the screen will be hidden.", 1),
    SvipPriv("personalAds", "Personal ADs", "Edit an AD for the user who open your inbox.", 1, arrow = true),
    SvipPriv("avoidDisturbing", "Avoid disturbing", "Once you activate this privilege, only people you follow can send you messages.", 2),
    SvipPriv("cantFollow", "Can't be followed", "When enabled, other users cannot follow you.", 3),
    SvipPriv("antiGoAfter", "Anti Go After", "When enabled, other users cannot invite you to a mic seat.", 3),
    SvipPriv("antiKick", "Anti Kick", "SVIP 5-9 privileges: you cannot be kicked from a room.", 5),
    SvipPriv("hideVisits", "Hide visit records", "Hide your visit history when viewing others' profiles.", 5),
    SvipPriv("hideOnline", "Hide Online Status", "Hide your online status to appear offline to others.", 5),
    SvipPriv("hideProfile", "Hide my profile", "Your profile appears mysterious with shining name and hidden stats.", 6),
    SvipPriv("hideRanking", "Hide ranking records", "Others will see a mystery man on the lists, no one knows your ranking.", 6),
    SvipPriv("invisibleRoom", "Invisible in rooms", "You remain invisible in a room until you take a mic seat.", 7),
)

/** Daftar key svip yang aktif dari string publik "a,b,c". */
fun svipKeys(csv: String?): Set<String> =
    (csv ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** Apakah user publik ini mengaktifkan privilege svip tertentu. */
fun PublicUser.svipOn(key: String): Boolean = key in svipKeys(svip)

/**
 * Cache SVIP lokal di perangkat (SharedPreferences).
 * Panel SVIP tetap hidup walau kuota database habis / offline;
 * perubahan disinkronkan otomatis begitu koneksi/kuota kembali.
 */
object SvipLocal {
    private fun sp() = FirebaseApp.getInstance().applicationContext
        .getSharedPreferences("svip_local", Context.MODE_PRIVATE)

    fun keys(uid: String): Set<String> = sp().getStringSet("k_$uid", emptySet()) ?: emptySet()
    fun on(uid: String, key: String): Boolean = key in keys(uid)
    fun csv(uid: String): String = keys(uid).joinToString(",")

    fun save(uid: String, key: String, isOn: Boolean) {
        val cur = keys(uid).toMutableSet()
        if (isOn) cur.add(key) else cur.remove(key)
        sp().edit().putStringSet("k_$uid", cur).putBoolean("dirty_$uid", true).apply()
    }

    fun saveAll(uid: String, csv: String) {
        val set = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        sp().edit().putStringSet("k_$uid", set).apply()
    }

    fun dirty(uid: String): Boolean = sp().getBoolean("dirty_$uid", false)
    fun clearDirty(uid: String) { sp().edit().putBoolean("dirty_$uid", false).apply() }

    fun ad(uid: String): String = sp().getString("ad_$uid", "") ?: ""
    fun saveAd(uid: String, ad: String) { sp().edit().putString("ad_$uid", ad).putBoolean("dirty_$uid", true).apply() }

    fun vip(uid: String): Pair<Int, Long> {
        val p = sp()
        val v = p.getInt("vip_$uid", -1)
        return if (v >= 0) v to p.getLong("vipUntil_$uid", 0L) else 0 to 0L
    }
    fun saveVip(uid: String, vip: Int, until: Long) {
        sp().edit().putInt("vip_$uid", vip).putLong("vipUntil_$uid", until).apply()
    }
}


/** Level SVIP dari snapshot RTDB pub/{uid}: svipLv/svipUntil (baru), fallback vip/vipUntil (lama). */
private fun svipLevelOf(s: com.google.firebase.database.DataSnapshot): Pair<Int, Long> {
    val sv = (s.child("svipLv").value as? Long)?.toInt() ?: 0
    val su = s.child("svipUntil").value as? Long ?: 0L
    if (sv > 0) return sv to su
    val v = (s.child("vip").value as? Long)?.toInt() ?: 0
    val u = s.child("vipUntil").value as? Long ?: 0L
    return v to u
}

object SvipRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db().collection("wallets").document(uid)

    /**
     * Tulis status SVIP ke profil publik RTDB (pub/{uid}). Kuota RTDB terpisah dari
     * Firestore, jadi walau kuota Firestore habis status tetap tersimpan publik dan
     * bisa dibaca orang lain lewat jalur cadangan RTDB.
     */
    private suspend fun rtdbPub(uid: String) {
        try {
            // Server cadangan RTDB: simpan toggle + iklan + level SVIP supaya
            // panel SVIP tetap hidup walau kuota Firestore habis.
            val data = hashMapOf<String, Any?>("svip" to SvipLocal.csv(uid), "ad" to SvipLocal.ad(uid))
            val lv = SvipLocal.vip(uid)
            if (lv.first > 0) {
                // BELI SVIP: level tersimpan di Realtime Database pub/{uid}/svipLv.
                data["svipLv"] = lv.first.toLong()
                data["svipUntil"] = lv.second
            }
            Rtdb.ref("pub/$uid").updateChildren(data).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
    }

    /**
     * Simpan toggle privilege. Selalu ditulis ke cache lokal dulu (berhasil walau
     * kuota database habis / offline), lalu dicoba kirim ke server. Kalau server
     * gagal, nilai lokal tetap dipakai dan otomatis disinkronkan lain waktu.
     */
    suspend fun set(uid: String, key: String, on: Boolean): String? {
        SvipLocal.save(uid, key, on)
        // RTDB LANGSUNG & TANPA MENUNGGU: nilai svip ditulis ke Realtime Database
        // pub/{uid}/svip. RTDB menerapkan nilai ke cache lokal seketika dan
        // menyinkronkan ke server di latar belakang, jadi panel tidak delay.
        try {
            // Tunggu tulisan sampai RTDB; kalau gagal (rules/network) dirty tetap
            // menyala sehingga perubahan otomatis didorong lagi di resync().
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                Rtdb.ref("pub/$uid/svip").setValue(SvipLocal.csv(uid)).await()
            }
            if (!SvipLocal.dirty(uid)) { /* sukses: dirty sudah diclear di bawah */ }
            SvipLocal.clearDirty(uid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // nilai lokal tetap dipakai; resync() akan mencoba lagi
        }
        return null
    }

    /** Sinkronkan ulang perubahan lokal yang belum masuk server (dipanggil saat app dibuka). */
    suspend fun resync(uid: String) {
        if (!SvipLocal.dirty(uid)) return
        // RTDB LANGSUNG: perubahan lokal didorong ke Realtime Database pub/{uid}.
        rtdbPub(uid)
        SvipLocal.clearDirty(uid)
    }

    /** Apakah user INI (uid) mengaktifkan privilege dan SVIP-nya masih aktif.
     *  Kalau database tidak bisa dibaca (kuota habis/offline), pakai cache lokal. */
    suspend fun myOn(uid: String, key: String, minVip: Int): Boolean {
        // Ada perubahan lokal yang belum tersinkron ke RTDB: nilai lokal MENANG,
        // jangan timpa dengan data lama dari server (fix toggle on/off tidak berubah).
        if (SvipLocal.dirty(uid)) return fallbackOn(uid, key, minVip)
        // RTDB LANGSUNG: baca dari Realtime Database pub/{uid} (kuota terpisah);
        // kalau RTDB tidak terjangkau, pakai cache lokal.
        try {
            val s = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("pub/$uid").get().await() }
            if (s != null && s.exists()) {
                val (v, until) = svipLevelOf(s)
                SvipLocal.saveVip(uid, v, until)
                if (s.hasChild("svip")) SvipLocal.saveAll(uid, s.child("svip").value as? String ?: "")
                if (s.hasChild("ad")) SvipLocal.saveAd(uid, s.child("ad").value as? String ?: "")
                return vipActive(v, until) && v >= minVip && SvipLocal.on(uid, key)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        return fallbackOn(uid, key, minVip)
    }

    /** DATABASE CADANGAN: baca status SVIP dari RTDB pub/{uid} (kuota terpisah
     *  dari Firestore), lalu dari KV wallet, terakhir cache lokal. */
    private suspend fun fallbackOn(uid: String, key: String, minVip: Int): Boolean {
        val st = fallbackState(uid)
        return vipActive(st.first, st.second) && st.first >= minVip &&
            (SvipLocal.on(uid, key))
    }

    /** Baca (vip, vipUntil) lewat jalur cadangan: RTDB pub dulu, lalu KV backup. */
    suspend fun fallbackState(uid: String): Pair<Int, Long> {
        try {
            val s = kotlinx.coroutines.withTimeoutOrNull(3000L) { Rtdb.ref("pub/$uid").get().await() }
            if (s != null && s.exists()) {
                val (v, until) = svipLevelOf(s)
                if (v > 0) {
                    SvipLocal.saveVip(uid, v, until)
                    return v to until
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // RTDB gagal / belum ada: coba database cadangan KV (wallet backup).
        try {
            val bk = BackupRepo.get()
            if (bk != null && bk.ver > 0 && bk.vip > 0) {
                SvipLocal.saveVip(uid, bk.vip, bk.vipUntil)
                return bk.vip to bk.vipUntil
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        return SvipLocal.vip(uid)
    }


    /** Segarkan cache lokal SVIP (toggle/iklan/level) dari RTDB pub/{uid};
     *  dipanggil sebelum sinkronisasi profil supaya nilai terbaru tidak tertimpa. */
    suspend fun refreshLocal(uid: String) {
        if (SvipLocal.dirty(uid)) return // ada perubahan lokal belum tersinkron: jangan ditimpa
        try {
            val s = kotlinx.coroutines.withTimeoutOrNull(2000L) { Rtdb.ref("pub/$uid").get().await() }
            if (s != null && s.exists()) {
                if (s.hasChild("svip")) SvipLocal.saveAll(uid, s.child("svip").value as? String ?: "")
                if (s.hasChild("ad")) SvipLocal.saveAd(uid, s.child("ad").value as? String ?: "")
                val (v, u) = svipLevelOf(s)
                if (v > 0) SvipLocal.saveVip(uid, v, u)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
    }

    /**
     * BELI SVIP n: koin dipotong lewat dompet Supabase (transaksi tercatat),
     * level + masa aktif SVIP disimpan di REALTIME DATABASE pub/{uid}/svipLv.
     * Membeli level yang sama memperpanjang 30 hari.
     */
    suspend fun buySvip(uid: String, tier: Int): String? {
        if (tier !in 1..9) return "Level SVIP tidak valid"
        val cur = SvipLocal.vip(uid)
        val curActive = vipActive(cur.first, cur.second)
        if (curActive && cur.first > tier) return "SVIP${cur.first} kamu masih aktif"
        val price = vipPrice(tier)
        // 1) Potong koin lewat dompet Supabase (juga mencatat transaksi).
        val item = StoreItem("svip$tier", "badge", "SVIP$tier", "\uD83D\uDC51", price, 30, Color(0xFFD4AF37), 0, null)
        val r = SupaRepo.buyItem(uid, item)
        r.wallet?.let { WalletRepo.emitBackup(uid, it) }
        if (r.error != null) return r.error
        // 2) Simpan level SVIP di Realtime Database.
        val now = System.currentTimeMillis()
        val until = (if (curActive && cur.first == tier) cur.second else now) + 30L * 86_400_000L
        SvipLocal.saveVip(uid, tier, until)
        try {
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                Rtdb.ref("pub/$uid").updateChildren(
                    hashMapOf<String, Any?>("svipLv" to tier.toLong(), "svipUntil" to until),
                ).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        return null
    }

    /** Simpan iklan pribadi (Personal ADs); lokal dulu, server best-effort. */
    suspend fun setAd(uid: String, ad: String): String? {
        SvipLocal.saveAd(uid, ad.trim().take(120))
        // RTDB LANGSUNG: iklan pribadi langsung ke Realtime Database pub/{uid}.
        rtdbPub(uid)
        SvipLocal.clearDirty(uid)
        return null
    }
}

/** Toggle pill emas ala SVIP. */
@Composable
fun ToggleGold(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val knob by animateDpAsState(if (on) 22.dp else 4.dp, label = "knob")
    Box(
        Modifier.width(46.dp).height(26.dp).clip(RoundedCornerShape(50))
            .background(if (on) Color(0xFFF5C542) else Color(0xFF444444))
            .clickable(enabled = enabled) { onChange(!on) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(start = knob).size(18.dp).clip(CircleShape).background(Color.White))
    }
}

/** Badge kecil "SVIP n" emas. */
@Composable
fun SvipBadge(n: Int) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFFFE082), Color(0xFFC89118))))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("👑 SVIP$n", color = Color(0xFF3B2800), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Panel SVIP Settings: semua privilege, terbuka berurutan sesuai level SVIP. */
@Composable
fun SvipSettingsScreen(uid: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    // Cache lokal: panel langsung tampil walau kuota database habis / offline.
    val locVip = remember(uid) { SvipLocal.vip(uid) }
    var vip by remember(uid) { mutableStateOf(locVip.first) }
    var vipUntil by remember(uid) { mutableStateOf(locVip.second) }
    var toggles by remember(uid) { mutableStateOf(SvipLocal.keys(uid).associateWith { true }) }
    var myAd by remember(uid) { mutableStateOf(SvipLocal.ad(uid)) }
    var loaded by remember(uid) { mutableStateOf(false) }
    var editAd by remember { mutableStateOf(false) }
    // BELI SVIP: level disimpan di Realtime Database (pub svipLv/svipUntil).
    var selSvip by remember { mutableStateOf(1) }
    var buyBusy by remember { mutableStateOf(false) }
    var wallet by remember(uid) { mutableStateOf(WalletCache.load(uid)) }

    DisposableEffect(uid) {
        val reg = WalletRepo.observe(uid) { wallet = it }
        onDispose { reg.remove() }
    }
    // RTDB REALTIME: panel mendengarkan pub/{uid} langsung, jadi level & toggle
    // selalu terbaru tanpa delay (data lokal RTDB tampil seketika, lalu server menyusul).
    DisposableEffect(uid) {
        val r = Rtdb.ref("pub/$uid")
        val l = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                if (!s.exists()) return
                val (v, until) = svipLevelOf(s)
                if (v > 0) { vip = v; vipUntil = until; SvipLocal.saveVip(uid, v, until) }
                if (s.hasChild("svip")) {
                    val csv = s.child("svip").value as? String ?: ""
                    SvipLocal.saveAll(uid, csv)
                    toggles = svipKeys(csv).associateWith { true }
                }
                if (s.hasChild("ad")) {
                    myAd = s.child("ad").value as? String ?: ""
                    SvipLocal.saveAd(uid, myAd)
                }
                loaded = true
            }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) { }
        }
        r.addValueEventListener(l)
        onDispose { r.removeEventListener(l) }
    }

    LaunchedEffect(uid) {
        var ok = false
        // RTDB LANGSUNG: level + toggle dibaca dari Realtime Database pub/{uid}
        // (kuota terpisah dari Firestore) supaya panel langsung tampil tanpa jeda.
        try {
            val s = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("pub/$uid").get().await() }
            if (s != null && s.exists()) {
                ok = true
                val (vv, uu) = svipLevelOf(s)
                vip = vv; vipUntil = uu
                if (s.hasChild("svip")) {
                    val csv = s.child("svip").value as? String ?: ""
                    SvipLocal.saveAll(uid, csv)
                    toggles = svipKeys(csv).associateWith { true }
                }
                if (s.hasChild("ad")) {
                    myAd = s.child("ad").value as? String ?: ""
                    SvipLocal.saveAd(uid, myAd)
                }
                SvipLocal.saveVip(uid, vip, vipUntil)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        if (!ok) {
            // RTDB tidak terjangkau: tampilkan cache lokal + jalur cadangan KV.
            val st = SvipRepo.fallbackState(uid)
            if (st.first > 0) { vip = st.first; vipUntil = st.second }
            SvipLocal.saveVip(uid, vip, vipUntil)
            toggles = SvipLocal.keys(uid).associateWith { true }
            myAd = SvipLocal.ad(uid)
        }
        loaded = true
    }

    val active = vipActive(vip, vipUntil)

    Box(Modifier.fillMaxSize().background(Color(0xFF1C1C1C))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp),
                )
                Text("SVIP Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).navigationBarsPadding(),
            ) {
                if (!loaded) {
                    Spacer(Modifier.height(40.dp))
                    Text("Memuat…", color = Color(0xFF9E9E9E), fontSize = 14.sp)
                }
                // ===== BELI / PERPANJANG SVIP =====
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF262626)).border(1.dp, Color(0xFFF5C542), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    Column {
                        Text("Beli SVIP", color = Color(0xFFF5C542), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Level tersimpan di Realtime Database. Aktif 30 hari; beli level yang sama = perpanjang. Semua privilege level itu ke bawah langsung terbuka.",
                            color = Color(0xFF9E9E9E), fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (n in 1..9) {
                                val on = n == selSvip
                                Box(
                                    Modifier.clip(RoundedCornerShape(10.dp))
                                        .background(if (on) Color(0xFFF5C542) else Color(0xFF3A3A3A))
                                        .clickable { selSvip = n }.padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        "SVIP$n", color = if (on) Color(0xFF3B2800) else Color.White,
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (wallet != null) {
                            Text("Saldo: ${fmtNum(wallet!!.coins)} koin", color = Color(0xFFE9D3B0), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            (if (active && vip == selSvip) "Perpanjang" else "Beli") + " SVIP$selSvip · " + fmtNum(vipPrice(selSvip)) + " koin",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (buyBusy) {
                            Text("Memproses…", color = Color(0xFFF5C542), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text(
                                "Beli sekarang", color = Color(0xFF3B2800), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFF5C542))
                                    .clickable {
                                        buyBusy = true
                                        AppScope.scope.launch {
                                            val err = SvipRepo.buySvip(uid, selSvip)
                                            buyBusy = false
                                            if (err == null) {
                                                Toast.makeText(
                                                    context,
                                                    "SVIP$selSvip aktif! Privilege SVIP$selSvip ke bawah terbuka.",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            } else {
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }.padding(horizontal = 22.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                SVIP_PRIVS.forEachIndexed { i, priv ->
                    val unlocked = active && vip >= priv.minVip
                    val on = toggles[priv.key] == true
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SvipBadge(priv.minVip)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(priv.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(priv.desc, color = Color(0xFF9E9E9E), fontSize = 13.sp)
                            }
                            if (priv.arrow) {
                                Text(
                                    "›", color = Color(0xFF9E9E9E), fontSize = 26.sp,
                                    modifier = Modifier.clickable(enabled = unlocked) { editAd = true }
                                        .padding(horizontal = 10.dp),
                                )
                            } else {
                                ToggleGold(on = on, enabled = unlocked, onChange = { v ->
                                    toggles = toggles + (priv.key to v)
                                    AppScope.scope.launch {
                                        val err = SvipRepo.set(uid, priv.key, v)
                                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                })
                            }
                        }
                        if (i < SVIP_PRIVS.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (editAd) {
        var txt by remember { mutableStateOf(myAd) }
        AlertDialog(
            onDismissRequest = { editAd = false },
            title = { Text("Personal ADs") },
            text = {
                Column {
                    Text("Iklan ini tampil saat orang lain membuka kotak masuk kamu.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = txt,
                        onValueChange = { txt = it },
                        placeholder = { Text("Tulis iklanmu…") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editAd = false
                    myAd = txt
                    AppScope.scope.launch { SvipRepo.setAd(uid, txt) }
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { editAd = false }) { Text("Batal") } },
        )
    }
}

/** Foto profil misterius: foto hacker bertanda tanya, kilau putih berjalan, bingkai putih berkilau. */
@Composable
fun MysteryAvatar(size: Dp) {
    val t = rememberInfiniteTransition(label = "ma")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "mx")
    val glow by t.animateColor(Color(0xFF9E9E9E), Color(0xFFFFFFFF), infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "mg")
    Box(
        Modifier.size(size).clip(CircleShape)
            .border(2.dp, glow, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.mystery_avatar),
            contentDescription = "Misterius",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        // Kilau putih menyapu dari kiri ke kanan, berulang terus.
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    (x * 0.6f) to Color(0x66FFFFFF),
                    (x * 0.6f + 0.05f) to Color(0xCCFFFFFF),
                    (x * 0.6f + 0.1f) to Color(0x66FFFFFF),
                    (x * 0.6f + 0.15f) to Color.Transparent,
                    1f to Color.Transparent,
                )
            )
        )
    }
}

/** Nama "Mysterious" dengan kilau emas berjalan. */
@Composable
fun MysteryName(fontSize: TextUnit = 20.sp) {
    val c by rememberInfiniteTransition(label = "mn").animateColor(
        Color(0xFFB8860B), Color(0xFFFFF3C4),
        infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "mnc",
    )
    Text(
        "Mysterious",
        fontSize = fontSize,
        fontWeight = FontWeight.ExtraBold,
        color = c,
        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xFF000000), androidx.compose.ui.geometry.Offset(1f, 1f), 3f)),
    )
}

/** Riwayat pengunjung profil (fitur Hide visit records SVIP5). */
object VisitRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun logs(uid: String) = db().collection("visits").document(uid).collection("logs")

    /** Catat kunjungan visitor ke profil owner. */
    suspend fun record(ownerUid: String, visitorUid: String) {
        try {
            logs(ownerUid).document(visitorUid)
                .set(hashMapOf("uid" to visitorUid, "at" to System.currentTimeMillis())).await()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }

    /** Pengunjung terbaru profil (avatar + nama). */
    suspend fun recent(ownerUid: String, limit: Long = 12): List<PublicUser> = try {
        logs(ownerUid)
            .orderBy("at", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit).get().await().documents
            .mapNotNull { d -> d.getString("uid")?.let { UserDirectory.findByUid(it) } }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
}
