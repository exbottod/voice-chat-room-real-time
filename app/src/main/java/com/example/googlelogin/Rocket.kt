package com.example.googlelogin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Fitur Rocket: level roket naik dari total koin yang dipakai mengirim gift.
 * Semua angka dan aset bisa diatur di Realtime Database node "rocketCfg" tanpa
 * perlu build ulang aplikasi:
 *
 *   rocketCfg/exp1 .. exp5   = syarat EXP tiap level (angka)
 *   rocketCfg/img1 .. img5   = URL / emoji roket tiap level
 *   rocketCfg/rw1 .. rw6     = hadiah grid 3x2 (URL gambar atau emoji)
 *
 * Progres pemain disimpan di rocket/{uid}/exp.
 */
/**
 * Saklar sementara: false = ikon roket dan animasi peluncuran di dalam room
 * tidak dipasang. Dipakai untuk menguji apakah keduanya penyebab room macet.
 */
const val ROCKET_ROOM_UI = true

object RocketCfg {
    // Syarat energi tiap roket (energi yang harus diisi sebelum roket itu meluncur).
    val defaultExp = listOf(
        100_000_000L,   // roket 1
        500_000_000L,   // roket 2
        1_000_000_000L, // roket 3
        1_500_000_000L, // roket 4
        2_000_000_000L, // roket 5
    )

    /** Lama roket meluncur (ms). Semua orang memakai jam yang sama, jadi
     *  yang masuk di tengah jalan tetap melihat roketnya masih meluncur. */
    var flyDurMs by mutableStateOf(15_000L)
    // Ikon roket kecil per level, sudah ikut di dalam APK.
    fun bundledIcon(lv: Int): String =
        "asset/rocket/votaky_rocket_small_level_" + lv.coerceIn(1, 5) + ".svga"

    // Animasi meluncur (mp4) bawaan per level, ikut di dalam APK.
    fun bundledFly(lv: Int): String {
        val v2 = "asset/rocket/rocket_v2_lv" + lv.coerceIn(1, 5) + "_launch.mp4"
        return if (assetAda(v2)) v2
        else "asset/rocket/votaky_rocket_large_level_" + lv.coerceIn(1, 5) + "_launch.mp4"
    }

    /** Apakah file aset ikut di dalam APK. */
    private fun assetAda(path: String): Boolean = try {
        val ctx = QuotaGuard.appContext ?: return false
        ctx.assets.open(path.removePrefix("asset/")).close()
        true
    } catch (e: Exception) {
        false
    }

    /** Panjang tiap video bawaan (ms), sesuai file aslinya. */
    fun videoMs(url: String): Long = try {
        val ctx = QuotaGuard.appContext ?: return 5_000L
        val afd = ctx.assets.openFd(url.removePrefix("asset/"))
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
            (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 5_000L).coerceIn(1_000L, 20_000L)
        } finally {
            mmr.release()
        }
    } catch (e: Exception) {
        5_000L
    }

    /** Animasi roket pecah di atas setelah meluncur. Null = level ini belum punya. */
    fun bundledBurst(lv: Int): String? {
        val v2 = "asset/rocket/rocket_v2_lv" + lv.coerceIn(1, 5) + "_burst.mp4"
        return if (assetAda(v2)) v2 else null
    }

    /** Animasi "giff" (roket sebagai hadiah) per level. */
    fun bundledGift(lv: Int): String =
        "asset/rocket/votaky_rocket_large_level_" + lv.coerceIn(1, 5) + "_giff.mp4"

    /** Animasi keberuntungan (lucky) per level. */
    fun bundledLuck(lv: Int): String =
        "asset/rocket/votaky_rocket_large_level_" + lv.coerceIn(1, 5) + "_luck.mp4"

    val defaultImg = List(5) { bundledIcon(it + 1) }
    val defaultRewards = listOf("\uD83C\uDFC5", "\uD83D\uDC51", "\uD83D\uDCA5", "\uD83D\uDE97", "\uD83D\uDEF8", "\uD83C\uDFAB")

    /** Animasi bawaan; dipakai kalau panel belum mengisi animasi per level. */
    var flyUrl by mutableStateOf(bundledFly(1))

    /** Animasi meluncur tiap roket (index 0..4 = Lv.1..Lv.5), MP4 atau SVGA. */
    var flyUrls by mutableStateOf<List<String>>(List(5) { bundledFly(it + 1) })

    /** Hadiah 6 kolom untuk tiap roket (index 0..4 = Lv.1..Lv.5), diisi dari panel admin. */
    var lvRewards by mutableStateOf<List<List<String>>>(List(5) { emptyList() })

    /** Keterangan tiap hadiah roket: "VIP9 · 10 jt" bila diisi admin. */
    var lvRewardInfo by mutableStateOf<List<List<String>>>(List(5) { emptyList() })

    /** Gambar hadiah peringkat: kunci "1".."4" = Top1..Top3 dan Top4 ke bawah. */
    var rankPrize by mutableStateOf<Map<String, String>>(emptyMap())

    /** Keterangan hadiah peringkat. */
    var rankPrizeInfo by mutableStateOf<Map<String, String>>(emptyMap())

    /** Saklar darurat dari panel: true = ikon dan animasi roket disembunyikan di room. */
    var uiOff by mutableStateOf(false)

    var exp by mutableStateOf(defaultExp)
    var img by mutableStateOf(defaultImg)
    var rewards by mutableStateOf(defaultRewards)

    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            Rtdb.listen(Rtdb.ref("rocketCfg")) { s ->
                val e = defaultExp.toMutableList()
                for (i in 1..5) {
                    // Nilai 0 dianggap belum diatur, jadi memakai angka bawaan.
                    val v = s.child("exp$i").getValue(Long::class.java)
                    if (v != null && v > 0L) e[i - 1] = v
                }
                exp = e.toList()
                val im = defaultImg.toMutableList()
                for (i in 1..5) {
                    s.child("img$i").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { im[i - 1] = it }
                }
                img = im.toList()
                val rw = defaultRewards.toMutableList()
                for (i in 1..6) {
                    s.child("rw$i").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { rw[i - 1] = it }
                }
                rewards = rw.toList()
                s.child("flyUrl").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { flyUrl = it }
                uiOff = s.child("uiOff").getValue(Boolean::class.java) ?: false
                s.child("flyDur").getValue(Long::class.java)?.let { v ->
                    if (v in 5L..120L) flyDurMs = v * 1000L
                }
                // Animasi roket meluncur per level: rocketCfg/fly1..fly5.
                // Kalau panel belum mengisi, pakai mp4 bawaan di dalam APK.
                val globalFly = s.child("flyUrl").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                // Animasi meluncur selalu memakai video yang tertanam di APK.
                // URL sisa panel lama (flyN/flyUrl) diabaikan supaya roket tidak
                // gagal tampil gara-gara tautan yang sudah mati.
                flyUrls = (1..5).map { n -> bundledFly(n) }
                // Hadiah per roket: rocketCfg/lv{N}/r{1..6} (gambar + VIP + coin).
                // Data lama yang hanya berisi gambar di p{K} tetap dibaca.
                fun prizeAt(node: com.google.firebase.database.DataSnapshot): Triple<String, String, String> {
                    val img = node.child("img").getValue(String::class.java).orEmpty()
                    val vip = node.child("vip").getValue(Int::class.java) ?: 0
                    val coins = node.child("coins").getValue(Long::class.java) ?: 0L
                    val label = buildList {
                        if (vip > 0) add("VIP$vip")
                        if (coins > 0L) add(ringkas(coins))
                    }.joinToString(" · ")
                    return Triple(img, label, node.child("eff").getValue(String::class.java).orEmpty())
                }
                lvRewards = (1..5).map { n ->
                    (1..6).map { k ->
                        val o = prizeAt(s.child("lv$n").child("r$k"))
                        o.first.ifBlank {
                            s.child("lv$n").child("p$k").getValue(String::class.java).orEmpty()
                        }
                    }
                }
                lvRewardInfo = (1..5).map { n ->
                    (1..6).map { k -> prizeAt(s.child("lv$n").child("r$k")).second }
                }
                // Hadiah peringkat: rocketCfg/rank/t{1..4}
                rankPrize = (1..4).associate { k ->
                    val o = prizeAt(s.child("rank").child("t$k"))
                    "$k" to o.first.ifBlank { s.child("rank").child("t$k").getValue(String::class.java).orEmpty() }
                }
                rankPrizeInfo = (1..4).associate { k -> "$k" to prizeAt(s.child("rank").child("t$k")).second }
                // Animasi roket meluncur boleh diisi lewat panel.
                s.child("flyUrl").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { flyUrl = it }
            }
        } catch (e: Exception) {
            // pakai nilai bawaan
        }
    }

    /** Animasi meluncur untuk roket level tertentu. */
    fun flyFor(lv: Int): String = flyUrls.getOrNull((lv - 1).coerceIn(0, 4)) ?: flyUrl

    /** Level (1..5) menurut total EXP. */
    fun levelOf(total: Long): Int {
        var lv = 1
        for (i in exp.indices) if (total >= exp[i]) lv = i + 1
        return lv.coerceIn(1, exp.size)
    }
}

object RocketRepo {
    /** Energi roket pada tahap yang sedang diisi (bukan total). */
    var exp by mutableStateOf(0L)
        private set

    /** Roket yang sedang diisi energinya (1..5). Naik setelah roket meluncur. */
    var stage by mutableStateOf(1)
        private set

    /** Pesan terakhir bila energi roket gagal disimpan ke database. */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Catatan percobaan energi terakhir: sumber, koin masuk, hasilnya. */
    var lastAdd by mutableStateOf<String?>(null)

    fun noteAddFail(msg: String) { lastAdd = "gift: addExp error (${msg.take(60)})" }

    /** Peluncuran cadangan di perangkat sendiri kalau database menolak tulisan. */
    var localFx by mutableStateOf<Triple<Int, Long, Long>?>(null)
        private set

    /** Peringkat roket room ini pada musim berjalan. */
    var rankRows by mutableStateOf<List<RocketRankRow>>(emptyList())
        private set

    fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    /** Tanggal menurut GMT+3 (Arab Saudi): dasar reset energi roket tiap 00.00. */
    fun dayKey(): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+3"))
        return String.format(
            "%04d-%02d-%02d",
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    /** Kunci musim: tahun + nomor pekan, supaya peringkat bisa direset mingguan. */
    fun season(): String {
        val c = java.util.Calendar.getInstance()
        val wk = c.get(java.util.Calendar.WEEK_OF_YEAR)
        return "${c.get(java.util.Calendar.YEAR)}-W" + (if (wk < 10) "0$wk" else "$wk")
    }

    /** Peringkat saya di room ini (1 = teratas). */
    fun myRank(): Int {
        val u = uid()
        val i = rankRows.indexOfFirst { it.uid == u }
        return if (i < 0) rankRows.size + 1 else i + 1
    }

    /**
     * Penonton boleh klaim hadiah keberuntungan sekali per peluncuran.
     * Return true kalau belum pernah klaim (dan langsung ditandai).
     */
    suspend fun canLucky(uid: String, launchId: String): Boolean {
        if (uid.isBlank() || launchId.isBlank()) return false
        val mark = Rtdb.ref("rocketLucky/$launchId/$uid")
        val done = try {
            mark.get().await().exists()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (done) return false
        try {
            mark.setValue(true).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        return true
    }

    private var launching = false

    /**
     * Roket level 5 pecah lalu meluncur: tulis kejadian ke room supaya semua
     * orang melihat animasi yang sama. Dibatasi sekali per 6 jam per room.
     */
    suspend fun maybeLaunch(roomId: String, name: String) {
        val u = uid()
        if (u.isBlank() || roomId.isBlank() || launching) return
        launching = true
        try {
            val mark = try {
                Rtdb.ref("rocketLaunchMark/$roomId/$u").get().await().getValue(Long::class.java) ?: 0L
            } catch (e: Exception) {
                0L
            }
            val now = System.currentTimeMillis()
            // Satu peluncuran per siklus room; penjaga ini hanya mencegah dobel.
            if (now - mark < 30_000L) return
            Rtdb.ref("rocketFx/$roomId").push().setValue(
                hashMapOf<String, Any?>(
                    "uid" to u,
                    "name" to name,
                    "url" to RocketCfg.flyUrl,
                    "ts" to now,
                ),
            ).await()
            Rtdb.ref("rocketLaunchMark/$roomId/$u").setValue(now).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        } finally {
            launching = false
        }
    }

    /** Room yang sedang dihitung roketnya (EXP disimpan terpisah per room). */
    private var liveRoom = ""

    private val regs = mutableListOf<Reg>()

    /** Pindah konteks ke room lain: EXP dan peringkat dimuat ulang untuk room itu. */
    fun room(roomId: String) {
        if (roomId.isBlank() || roomId == liveRoom) return
        liveRoom = roomId
        exp = 0L
        stage = 1
        rankRows = emptyList()
        for (r in regs) {
            try {
                r.remove()
            } catch (e: Exception) {
            }
        }
        regs.clear()
        val u = uid()
        if (u.isBlank()) return
        // EXP roket milik ROOM, bukan per pemain: siapa pun yang mengirim gift
        // di room ini menaikkan roket yang sama untuk semua orang di room.
        try {
            regs += Rtdb.listen(Rtdb.ref("rocket/$roomId")) { s ->
                val day = s.child("day").getValue(String::class.java) ?: ""
                val v = s.child("exp").getValue(Long::class.java) ?: 0L
                val st = s.child("stage").getValue(Int::class.java) ?: 1
                if (day == dayKey()) {
                    exp = v
                    stage = st.coerceIn(1, 5)
                } else {
                    exp = 0L
                    stage = 1
                }
            }
        } catch (e: Exception) {
        }
        try {
            regs += Rtdb.listen(Rtdb.ref("rocketRank/$roomId/${season()}")) { s ->
                rankRows = s.children.mapNotNull { c ->
                    val id = c.key ?: return@mapNotNull null
                    RocketRankRow(
                        uid = id,
                        name = c.child("name").getValue(String::class.java) ?: "Player",
                        coins = c.child("coins").getValue(Long::class.java) ?: 0L,
                        photo = c.child("photo").getValue(String::class.java).orEmpty(),
                    )
                }.sortedByDescending { it.coins }.take(20)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Tambah energi roket room dari gift yang baru dikirim. Energi diisi untuk
     * roket tahap sekarang; begitu penuh roket itu meluncur, lalu tahap
     * berikutnya mulai dari nol. Setelah roket 5 meluncur, tetap di roket 5.
     */
    suspend fun addExp(roomId: String, name: String, coins: Long) {
        val u = uid()
        if (u.isBlank() || coins <= 0L || roomId.isBlank()) return
        if (roomId != liveRoom) room(roomId)
        // Pasangan (level, nomor urut) yang benar-benar penuh pada percobaan
        // transaksi yang menang. Nomor urut membuat penulisan idempoten:
        // transaksi Firebase bisa diulang, jadi hasil ulangan tidak menambah
        // peluncuran baru.
        var launches = mutableListOf<Pair<Int, Long>>()
        var txErr: String? = null
        var hasilExp = -1L
        var hasilStage = 0
        try {
            txErr = Rtdb.tx(Rtdb.ref("rocket/$roomId")) { d ->
                // Setiap percobaan mulai dari daftar kosong supaya tidak dobel.
                launches = mutableListOf()
                val today = dayKey()
                val savedDay = d.child("day").getValue(String::class.java) ?: ""
                var cur = d.child("exp").getValue(Long::class.java) ?: 0L
                var st = d.child("stage").getValue(Int::class.java) ?: 1
                if (savedDay != today) {
                    cur = 0L
                    st = 1
                }
                st = st.coerceIn(1, 5)
                var seq = d.child("seq").getValue(Long::class.java) ?: 0L
                d.child("day").value = today
                var left = cur + coins
                var need = RocketCfg.exp.getOrElse(st - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
                var guard = 0
                // Maksimal 5 peluncuran per transaksi (satu putaran 1..5), sisanya
                // tetap tersimpan sebagai energi dan diluncurkan pada gift berikutnya.
                while (left >= need && guard < 5) {
                    left -= need
                    seq += 1
                    launches.add(st to seq)
                    // Setelah roket 5 meluncur, tetap di roket 5 (tidak kembali ke 1).
                    st = if (st >= 5) 5 else st + 1
                    need = RocketCfg.exp.getOrElse(st - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
                    guard++
                }
                // Kalau batas 5 peluncuran tercapai dalam satu transaksi, sisa energi
                // harus disimpan utuh (dulu dipotong modulo sehingga gift besar
                // seperti 400B hilang dan roket tidak pernah meluncur).
                val kenaBatas = guard >= 5 && left >= need
                // Roket 5 adalah roket terakhir. Setelah roket itu pecah, energi
                // kembali ke syaratnya (bukan menumpuk terus).
                val akhir = if (kenaBatas) left else if (st >= 5) left % need else left
                hasilExp = akhir
                hasilStage = st
                d.child("exp").value = akhir
                d.child("stage").value = st
                d.child("seq").value = seq
                d.child("lastBy").value = name
                d.child("lastUid").value = u
                d.child("ts").value = System.currentTimeMillis()
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            txErr = e.message ?: "gagal menyimpan energi"
            launches = mutableListOf()
        }
        // Energi gagal disimpan = roket tidak akan pernah meluncur.
        // Ditampilkan di panel Rocket supaya penyebabnya kelihatan.
        lastError = txErr?.let { "Energi roket ditolak database: ${it.take(90)}" }
        lastAdd = if (txErr != null) {
            "masuk ${ringkas(coins)}: DITOLAK (${txErr.take(60)})"
        } else {
            "masuk ${ringkas(coins)}: ${launches.size} luncur, stage $hasilStage, energi ${ringkas(hasilExp.coerceAtLeast(0L))}"
        }
        // Peringkat: siapa paling banyak kirim gift di room ini pekan ini.
        try {
            Rtdb.ref("rocketRank/$roomId/${season()}/$u").setValue(
                hashMapOf<String, Any?>(
                    "name" to name,
                    "photo" to (FirebaseAuth.getInstance().currentUser?.photoUrl?.toString().orEmpty()),
                    "coins" to ServerValue.increment(coins),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        // Kalau energi gagal disimpan, jangan umumkan peluncuran apa pun:
        // dulu inilah penyebab dialog "roket sudah meluncur" muncul berulang
        // setiap ketukan padahal energinya tidak pernah tersimpan.
        if (txErr == null) {
            // Tiap roket yang penuh dicatat berurutan supaya semua orang melihat
            // roket 1 meluncur dulu, lalu roket berikutnya.
            for ((lv, seq) in launches) publishLaunch(roomId, name, lv, seq)
        }
    }

    /**
     * Uji tampilan: mencatat satu peluncuran roket tanpa menunggu energi penuh.
     * Dipakai untuk memastikan video roket benar-benar bisa diputar di room.
     */
    suspend fun testLaunch(roomId: String, name: String, lv: Int = 1): String? {
        if (roomId.isBlank()) return "Room kosong"
        return try {
            val now = System.currentTimeMillis()
            val dur = RocketCfg.flyDurMs
            Rtdb.ref("rocketFx/$roomId").push().setValue(
                hashMapOf<String, Any?>(
                    "uid" to uid(),
                    "name" to name.ifBlank { "Tes" },
                    "lv" to lv.coerceIn(1, 5),
                    "url" to RocketCfg.flyFor(lv.coerceIn(1, 5)),
                    "ts" to now,
                    "dur" to dur,
                    "room" to roomId,
                    "test" to true,
                ),
            ).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Database menolak: tetap putar di perangkat ini supaya kelihatan.
            localFx = Triple(lv.coerceIn(1, 5), System.nanoTime(), System.currentTimeMillis())
            "Database menolak, diputar lokal: " + (e.message ?: "").take(70)
        }
    }

    /**
     * Catat satu peluncuran roket ke room. Semua orang membaca node ini lalu
     * memutar animasi roket sesuai levelnya, satu per satu.
     */
    private suspend fun publishLaunch(roomId: String, name: String, lv: Int, seq: Long) {
        val u = uid()
        if (u.isBlank()) return
        val now = System.currentTimeMillis()
        val dur = RocketCfg.flyDurMs
        val url = RocketCfg.flyFor(lv)
        var roomName = ""
        try {
            roomName = kotlinx.coroutines.withTimeoutOrNull(4000L) {
                Rtdb.ref("roomsPub/$roomId/name").get().await().getValue(String::class.java)
            } ?: ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        try {
            // Kunci unik tiap peluncuran. Dulu memakai "rk"+nomor urut, sehingga
            // setelah nomor urut mulai lagi dari 1 (room direset) tulisan menimpa
            // kejadian lama yang sudah pernah dilihat aplikasi, dan animasinya
            // tidak pernah diputar lagi.
            val id = Rtdb.ref("rocketFx/$roomId").push().key ?: ("rk" + seq)
            Rtdb.ref("rocketFx/$roomId/$id").setValue(
                hashMapOf<String, Any?>(
                    "uid" to u,
                    "name" to name,
                    "lv" to lv,
                    "url" to url,
                    "ts" to now,
                    "dur" to dur,
                    "room" to roomId,
                ),
            ).await()
            // Penanda untuk semua orang: banner "roket sedang meluncur" bisa diketuk
            // dan langsung membuka room yang sedang meluncurkan roket.
            // Opsional: kalau ditolak database, peluncuran tetap jalan.
            try { Rtdb.ref("rocketNow/$roomId").setValue(
                hashMapOf<String, Any?>(
                    "id" to id,
                    "room" to roomId,
                    "roomName" to roomName,
                    "name" to name,
                    "lv" to lv,
                    "url" to url,
                    "ts" to now,
                    "dur" to dur,
                ),
            ).await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Database menolak tulisan utama: roket tetap diputar di perangkat ini
            // supaya tidak terlihat seperti tidak jalan.
            lastError = "Peluncuran roket ditolak database: ${(e.message ?: "").take(90)}"
            localFx = Triple(lv, seq, now)
        }
    }

    fun clearLocalFx() { localFx = null }
}

/** Satu baris peringkat roket. */
data class RocketRankRow(val uid: String, val name: String, val coins: Long, val photo: String = "")

/** Angka ringkas gaya panel: 374.6K, 20.2M. */
/**
 * Energi yang ditampilkan. Di roket 5, energi di atas syarat 2B dihitung
 * sebagai putaran baru, jadi tidak pernah tampil 9476B / 2B.
 */
private fun energiTampil(total: Long, need: Long, lv: Int): Long =
    if (lv >= 5 && need > 0L) total % need else total

private fun ringkas(n: Long): String = when {
    n >= 1_000_000_000L -> String.format("%.1fB", n / 1_000_000_000.0)
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

/** Tampilan satu aset roket/hadiah: kalau URL dipakai gambar, kalau bukan dipakai teks. */
@Composable
private fun RocketAsset(value: String, size: androidx.compose.ui.unit.Dp, glow: Boolean) {
    if (value.startsWith("asset/") && value.endsWith(".svga")) {
        // Ikon roket dari aset APK: animasi kecil yang berputar terus.
        Box(contentAlignment = Alignment.Center) {
            if (glow) {
                Box(
                    Modifier.size(size * 1.15f).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0x6600AAFF), Color.Transparent))),
                )
            }
            key(value) {
                GiftSvga(value, "\uD83D\uDE80", Modifier.size(size), loops = -1)
            }
        }
    } else if (value.startsWith("http")) {
        Box(contentAlignment = Alignment.Center) {
            if (glow) {
                Box(
                    Modifier.size(size * 1.15f).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0x6600AAFF), Color.Transparent))),
                )
            }
            AsyncImage(
                model = value,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        Box(contentAlignment = Alignment.Center) {
            if (glow) {
                Box(
                    Modifier.size(size * 1.15f).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0x6600AAFF), Color.Transparent))),
                )
            }
            Text(value, fontSize = (size.value * 0.55f).sp)
        }
    }
}

/**
 * Panel melayang di sisi kanan room: ikon roket yang bisa ditekan untuk membuka
 * panel Rocket, dengan bar persen menuju level berikutnya di bawahnya.
 */
@Composable
fun RocketRail(roomId: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (RocketCfg.uiOff) return
    LaunchedEffect(Unit) { RocketCfg.start() }
    LaunchedEffect(roomId) { RocketRepo.room(roomId) }
    val total = RocketRepo.exp
    val exp = RocketCfg.exp
    // Bar di sisi room mengikuti energi roket yang sedang diisi.
    val lv = RocketRepo.stage.coerceIn(1, 5)
    val need = exp.getOrElse(lv - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
    val frac = (energiTampil(total, need, lv).toFloat() / need.toFloat()).coerceIn(0f, 1f)
    val pct = (frac * 100).toInt()

    Column(
        modifier = modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .border(1.5.dp, GoldLight.copy(alpha = 0.85f), CircleShape)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0x664FC3F7), Color.Transparent))),
            )
            RocketAsset(RocketCfg.img.getOrElse(lv - 1) { "🚀" }, 28.dp, false)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.width(46.dp).height(6.dp).clip(RoundedCornerShape(50))
                .background(Color(0x99000000))
                .border(0.5.dp, GoldLight.copy(alpha = 0.6f), RoundedCornerShape(50)),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(frac.coerceAtLeast(0.04f))
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF4FC3F7), Color(0xFF00AAFF)))),
            )
        }
        Text("$pct%", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Panel Rocket: judul, bar level, deretan 5 roket evolusi, dan grid hadiah 3x2.
 * Dibuka dari ikon roket di room.
 */
@Composable
fun RocketScreen(
    roomId: String,
    onDismiss: () -> Unit,
    fxAktif: Boolean = true,
    onNyalakanFx: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { RocketCfg.start() }
    LaunchedEffect(roomId) { RocketRepo.room(roomId) }
    val total = RocketRepo.exp
    // Tombol back menutup panel roket saja, tidak keluar dari room.
    BackHandler { onDismiss() }

    val expList = RocketCfg.exp
    // Roket yang sedang diisi energinya (1..5), bukan level menurut total EXP.
    val lv = RocketRepo.stage.coerceIn(1, 5)
    val myUid = remember { RocketRepo.uid() }
    var prizeRank by remember { mutableStateOf(-1) }
    var showRules by remember { mutableStateOf(false) }
    var showRank by remember { mutableStateOf(false) }
    var showPrizes by remember { mutableStateOf(false) }
    // Hadiah yang sedang dilihat: default roket level sekarang, bisa diketuk di deretan roket.
    var viewLv by remember { mutableStateOf(0) }
    val shownLv = if (viewLv in 1..5) viewLv else lv

    // Energi roket tahap ini dibandingkan dengan syarat levelnya.
    val need = expList.getOrElse(lv - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
    val frac = (energiTampil(total, need, lv).toFloat() / need.toFloat()).coerceIn(0f, 1f)

    val gold = Color(0xFFD4AF37)
    Box(Modifier.fillMaxSize()) {
        // Latar gelap di atas supaya kursi mic room tetap terlihat seperti di desain.
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.80f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0A2A66), Color(0xFF061B45), Color(0xFF04122E))))
                .border(1.5.dp, gold.copy(alpha = 0.8f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { },
                )
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))

            // Baris atas: tanda tanya di kiri, piala + kado di kanan.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RocketRoundBtn("?") { showRules = true }
                Spacer(Modifier.weight(1f))
                Text(
                    "Rocket",
                    color = Color(0xFFFFD86B),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.weight(1f))
                RocketRoundBtn("\uD83C\uDFC6") { showRank = true }
                Spacer(Modifier.width(8.dp))
                RocketRoundBtn("\uD83C\uDF81") { showPrizes = true }
            }
            // Tombol uji hanya untuk akun developer, tidak boleh dilihat pengguna.
            val devTes = try {
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email == "developerex100@gmail.com"
            } catch (e: Exception) { false }
            var tesPesan by remember { mutableStateOf<String?>(null) }
            val ctxTes = androidx.compose.ui.platform.LocalContext.current
            if (devTes) {
            // Uji cepat: putar video roket sekarang juga, tanpa menunggu energi.
            Text(
                "Tes luncur roket",
                color = Color(0xFFFFD86B), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x33FFC107))
                    .clickable {
                        kotlinx.coroutines.MainScope().launch {
                            val e = RocketRepo.testLaunch(roomId, "Tes")
                            tesPesan = e ?: "Terkirim, lihat layar room"
                            try {
                                android.widget.Toast.makeText(
                                    ctxTes, tesPesan ?: "", android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } catch (ex: Exception) { }
                            // Panel ditutup supaya animasi roket di room kelihatan.
                            // Kalau panel tetap terbuka, video berjalan di belakangnya.
                            if (e == null) {
                                kotlinx.coroutines.delay(400)
                                onDismiss()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            tesPesan?.let {
                Text(it, color = Color(0xFFFFE082), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            RocketRepo.lastAdd?.let {
                Text("Terakhir: $it", color = Color(0xFFB0BEC5), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            // Tambah energi langsung (khusus developer) untuk menguji ambang roket.
            Text(
                "Tambah 1 miliar energi",
                color = Color(0xFFA5D6A7), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x3338A169))
                    .clickable {
                        kotlinx.coroutines.MainScope().launch {
                            try {
                                RocketRepo.addExp(roomId, "Tes", 1_000_000_000L)
                                val snap = Rtdb.ref("rocket/$roomId").get().await()
                                val e = snap.child("exp").getValue(Long::class.java) ?: 0L
                                val st = snap.child("stage").getValue(Int::class.java) ?: 1
                                val pesan = "Setelah +1M: stage " + st + ", energi " + ringkas(e) +
                                    " | ambang " + RocketCfg.exp.joinToString("/") { ringkas(it) }
                                tesPesan = pesan
                                android.widget.Toast.makeText(ctxTes, pesan, android.widget.Toast.LENGTH_LONG).show()
                            } catch (ex: Exception) {
                                tesPesan = "Gagal: " + (ex.message ?: "").take(80)
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // Uji penulisan energi: memastikan database menerima, bukan hanya tampilan.
            Text(
                "Tes energi roket",
                color = Color(0xFF80D8FF), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x3300B0FF))
                    .clickable {
                        kotlinx.coroutines.MainScope().launch {
                            try {
                                RocketRepo.addExp(roomId, "Tes", 1_000_000L)
                                val snap = Rtdb.ref("rocket/$roomId").get().await()
                                val e = snap.child("exp").getValue(Long::class.java) ?: 0L
                                val st = snap.child("stage").getValue(Int::class.java) ?: 1
                                val err = RocketRepo.lastError
                                val pesan = if (err != null) {
                                    "DITOLAK: " + err
                                } else {
                                    "Tersimpan: stage $st, energi " + ringkas(e)
                                }
                                tesPesan = pesan
                                android.widget.Toast.makeText(ctxTes, pesan, android.widget.Toast.LENGTH_LONG).show()
                            } catch (ex: Exception) {
                                tesPesan = "Gagal: " + (ex.message ?: "").take(80)
                                android.widget.Toast.makeText(ctxTes, tesPesan ?: "", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            }
            // Alasan paling sering animasi tidak muncul padahal database lancar:
            // efek roket dimatikan di menu Blok Efek milik akun ini.
            if (!fxAktif) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x33FF5252))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Efek roket sedang DIMATIKAN di menu Blok Efek HP ini, jadi animasi tidak diputar.",
                        color = Color(0xFFFFCDD2), fontSize = 11.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Nyalakan sekarang",
                        color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(Color(0xFFFFD54F))
                            .clickable { onNyalakanFx?.invoke() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (RocketCfg.uiOff) {
                Text(
                    "Roket juga sedang dimatikan global dari panel dev.",
                    color = Color(0xFFFF8A80), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Deretan 5 roket evolusi, dipisah tanda ">".
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..5) {
                    if (i > 1) Text(">", color = Color(0xFFE8C489), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    val active = i == lv
                    val unlocked = i <= lv
                    Box(
                        Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { viewLv = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.alpha(if (unlocked) 1f else 0.6f)) {
                            RocketAsset(
                                RocketCfg.img.getOrElse(i - 1) { "\uD83D\uDE80" },
                                if (active) 54.dp else 42.dp,
                                active,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Garis kemajuan tipis dengan roket kecil di ujung kiri, lv. sekarang dan berikutnya di bawahnya.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDE80", fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(50))
                        .background(Color(0xFF102A5E)),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(frac.coerceAtLeast(0.03f))
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFFFFC107), Color(0xFFFFE082)))),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("lv.$lv", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // Angka energi supaya kelihatan gift benar-benar menambah energi.
                Text(
                    ringkas(energiTampil(total, need, lv)) + " / " + ringkas(need),
                    color = Color(0xFFFFE082), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (lv >= 5) "lv.1" else "lv.${lv + 1}",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            RocketRepo.lastError?.let { pesan ->
                Spacer(Modifier.height(6.dp))
                Text(
                    pesan,
                    color = Color(0xFFFF8A80), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Kotak hadiah: 6 kotak persegi bergaris emas tipis.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFFE8C489), RoundedCornerShape(6.dp))
                    .padding(8.dp),
            ) {
                Column {
                    val list = RocketCfg.lvRewards.getOrNull(shownLv - 1).orEmpty()
                    for (r in 0 until 2) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in 0 until 3) {
                                val idx = r * 3 + c
                                val v = list.getOrNull(idx).orEmpty()
                                    .ifBlank { RocketCfg.rewards.getOrElse(idx) { "\uD83C\uDF81" } }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0A2455))
                                        .border(1.dp, Color(0x99E8C489), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        RocketAsset(v, 58.dp, false)
                                        val info = RocketCfg.lvRewardInfo.getOrNull(shownLv - 1)?.getOrNull(idx).orEmpty()
                                        if (info.isNotBlank()) {
                                            Text(info, color = Color(0xFFFFE08A), fontSize = 9.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                        if (r == 0) Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (showRules) RocketRulesDialog { showRules = false }
        if (showRank) RocketRankDialog(myUid, onClaim = { prizeRank = it }) { showRank = false }
        if (showPrizes) RocketRankPrizeDialog { showPrizes = false }
        if (prizeRank >= 0) {
            RocketPrizeDialog(
                myUid = myUid,
                mode = "rank",
                rank = prizeRank,
                onClose = { prizeRank = -1 },
            )
        }
    }
}

/** Tombol bulat emas kecil (?, piala, kado). */
@Composable
private fun RocketRoundBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2B5FB8), Color(0xFF123470))))
            .border(1.5.dp, Color(0xFFD4AF37), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFFFFE08A), fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

/** Dialog dasar bergaya panel roket: judul, isi, tombol tutup. */
@Composable
private fun RocketSheet(title: String, onClose: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0A2A66), Color(0xFF04122E))))
                .border(1.5.dp, Color(0xFFD4AF37), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { },
                )
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { Text("\u2039", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = body)
        }
    }
}

/** Aturan roket (tombol ?). */
@Composable
fun RocketRulesDialog(onClose: () -> Unit) {
    val rules = listOf(
        "Memberikan hadiah koin akan menyimpan energi untuk roket. 1 koin = 1 energi.",
        "Ketika energi roket di dalam room sudah penuh, roket akan meluncur dan membagikan hadiah.",
        "Roket berisi sejumlah besar hadiah, termasuk namun tidak terbatas pada hadiah yang ditampilkan di halaman.",
        "Pengguna yang berkontribusi dalam peluncuran roket akan mendapatkan hadiah kontribusi, pengguna lainnya juga berkesempatan mendapatkan hadiah keberuntungan.",
        "Bila kemajuan roket akan direset setiap hari pada pukul 00.00 (GMT+3).",
        "Hak interpretasi akhir acara ini adalah milik APP.",
    )
    RocketSheet("Aturan", onClose) {
        rules.forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("${i + 1}", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                Text(t, color = Color.White, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

/** Peringkat roket room ini (tombol piala) + tombol klaim hadiah peringkat. */
@Composable
fun RocketRankDialog(myUid: String, onClaim: (Int) -> Unit, onClose: () -> Unit) {
    val rows = RocketRepo.rankRows
    val myRank = RocketRepo.myRank()
    RocketSheet("Peringkat roket", onClose) {
        Text("Pengirim gift terbanyak di room ini pekan ini", color = Color(0xFF9FB6D9), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) {
            Text("Belum ada yang mengirim gift.", color = Color(0xFF9FB6D9), fontSize = 13.sp)
        } else {
            rows.take(10).forEachIndexed { i, r0 ->
                val medal = when (i) { 0 -> "\uD83E\uDD47"; 1 -> "\uD83E\uDD48"; 2 -> "\uD83E\uDD49"; else -> "${i + 1}" }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(medal, fontSize = 16.sp, modifier = Modifier.width(30.dp))
                    Text(
                        r0.name,
                        color = if (r0.uid == myUid) Color(0xFFFFD54F) else Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text(ringkas(r0.coins), color = Color(0xFF7FD3FF), fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth().height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFB8860B))))
                .clickable { onClaim(myRank); onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text("Klaim hadiah peringkat (#$myRank)", color = Color(0xFF2A1B00), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Hadiah peringkat (tombol kado): Top1, Top2, Top3, Top4 ke bawah. */
@Composable
fun RocketRankPrizeDialog(onClose: () -> Unit) {
    val rows = listOf(
        "Top 1" to "VIP9 + 100 juta coin",
        "Top 2" to "VIP8 + 50 juta coin",
        "Top 3" to "VIP5 + 10 juta coin",
        "Top 4 ke bawah" to "10 juta coin",
    )
    RocketSheet("Hadiah peringkat", onClose) {
        rows.forEachIndexed { i, (t, d) ->
            val v = RocketCfg.rankPrize["${i + 1}"].orEmpty()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0E2A63))
                    .border(1.dp, Color(0xFF3F67B5), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    RocketAsset(v.ifBlank { when (i) { 0 -> "\uD83E\uDD47"; 1 -> "\uD83E\uDD48"; 2 -> "\uD83E\uDD49"; else -> "\uD83C\uDFC5" } }, 52.dp, false)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(t, color = Color(0xFFFFE08A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(d, color = Color.White, fontSize = 13.sp)
                    val info = RocketCfg.rankPrizeInfo["${i + 1}"].orEmpty()
                    if (info.isNotBlank()) Text(info, color = Color(0xFF7FD3FF), fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Hadiah diberikan saat roket meluncur. Pengguna lain juga berkesempatan mendapat hadiah keberuntungan.",
            color = Color(0xFF9FB6D9), fontSize = 11.sp,
        )
    }
}

// ==================== Peluncuran roket (level 1..5) ====================

/** Kejadian peluncuran yang diterima dari room. */
private data class LaunchEv(
    val id: String,
    val uid: String,
    val name: String,
    val lv: Int,
    val url: String,
    val end: Long,
)

/**
 * Peluncuran roket di room. Roket naik tinggi sampai ujung layar, lalu setelah
 * waktunya habis baru dialog hadiah muncul. Kalau ada orang lain yang masuk
 * room di tengah peluncuran, dia tetap melihat roketnya meluncur karena
 * waktunya mengikuti jam yang sama untuk semua orang.
 */
@Composable
fun RocketLaunchOverlay(roomId: String, myUid: String) {
    if (roomId.isBlank() || RocketCfg.uiOff) return
    LaunchedEffect(Unit) { RocketCfg.start() }

    val seen = remember(roomId) { mutableSetOf<String>() }
    var seenSeq by remember(roomId) { mutableStateOf<Long?>(null) }
    var seenStage by remember(roomId) { mutableStateOf(1) }
    var pending by remember(roomId) { mutableStateOf(listOf<LaunchEv>()) }
    var tertunda by remember(roomId) { mutableStateOf(listOf<Pair<LaunchEv, String>>()) }
    var ev by remember(roomId) { mutableStateOf<LaunchEv?>(null) }
    var step by remember(roomId) { mutableStateOf(0) }
    var dialogMode by remember(ev?.id) { mutableStateOf("") }
    var playCount by remember(ev?.id) { mutableStateOf(0) }
    var rise by remember(ev?.id) { mutableStateOf(0f) }

    DisposableEffect(roomId) {
        // Peluncuran sungguhan dipicu nomor urut di rocket/$roomId/seq. Dulu
        // animasi hanya diputar kalau node rocketFx berhasil ditulis, sehingga
        // gift yang mengisi energi sampai penuh tidak pernah menampilkan roket.
        val regEnergi = Rtdb.listen(Rtdb.ref("rocket/$roomId")) { s ->
            val seq = s.child("seq").getValue(Long::class.java) ?: 0L
            val stageNow = (s.child("stage").getValue(Int::class.java) ?: 1).coerceIn(1, 5)
            val by = s.child("lastBy").getValue(String::class.java) ?: ""
            val byUid = s.child("lastUid").getValue(String::class.java) ?: ""
            val prev = seenSeq
            seenSeq = seq
            if (prev == null || seq <= prev) {
                seenStage = stageNow
                return@listen
            }
            val jumlah = (seq - prev).toInt().coerceAtMost(5)
            val now = System.currentTimeMillis()
            val tambah = (1..jumlah).map { i ->
                // Tahap yang sedang diisi saat itu = roket yang baru penuh.
                val lv = ((((seenStage - 1) + (i - 1)) % 5) + 1).coerceIn(1, 5)
                LaunchEv(
                    id = "energi-$roomId-$prev-$i",
                    uid = byUid,
                    name = by,
                    lv = lv,
                    url = RocketCfg.flyFor(lv),
                    end = now + RocketCfg.flyDurMs,
                )
            }
            seenStage = stageNow
            pending = (pending + tambah).sortedBy { it.end }.take(5)
        }
        // Tombol "Tes luncur roket" tetap memakai node rocketFx.
        val regTes = Rtdb.listen(Rtdb.ref("rocketFx/$roomId").limitToLast(4)) { s ->
            val tambah = mutableListOf<LaunchEv>()
            val now = System.currentTimeMillis()
            for (c in s.children) {
                val id = c.key ?: continue
                if (seen.contains(id)) continue
                seen.add(id)
                // Peluncuran nyata sudah ditangani lewat nomor urut di atas.
                if (c.child("test").getValue(Boolean::class.java) != true) continue
                val ts = c.child("ts").getValue(Long::class.java) ?: continue
                val dur = c.child("dur").getValue(Long::class.java) ?: RocketCfg.flyDurMs
                // Kejadian yang sudah lewat tidak diputar lagi.
                if (now > ts + dur) continue
                tambah.add(
                    LaunchEv(
                        id = id,
                        uid = c.child("uid").getValue(String::class.java) ?: "",
                        name = c.child("name").getValue(String::class.java) ?: "",
                        lv = (c.child("lv").getValue(Int::class.java) ?: 1).coerceIn(1, 5),
                        url = c.child("url").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                            ?: RocketCfg.flyFor(1),
                        end = ts + dur,
                    ),
                )
            }
            // Antrean dibatasi supaya combo gift tidak menumpuk dialog.
            if (tambah.isNotEmpty()) pending = (pending + tambah).sortedBy { it.end }.take(5)
        }
        onDispose {
            regTes.remove()
            regEnergi.remove()
        }
    }

    // Peluncuran cadangan dari perangkat sendiri (database menolak tulisan).
    LaunchedEffect(RocketRepo.localFx) {
        val lf = RocketRepo.localFx ?: return@LaunchedEffect
        pending = (pending + LaunchEv(
            id = "local" + lf.second,
            uid = myUid,
            name = "",
            lv = lf.first.coerceIn(1, 5),
            url = RocketCfg.flyFor(lf.first),
            end = System.currentTimeMillis() + RocketCfg.flyDurMs,
        )).sortedBy { it.end }.take(5)
        RocketRepo.clearLocalFx()
    }

    LaunchedEffect(pending, ev) {
        if (ev == null && pending.isNotEmpty()) {
            ev = pending.first()
            pending = pending.drop(1)
            step = 0
        }
    }

    val e = ev
    val tinggiNaik = with(LocalDensity.current) { 420.dp.toPx() }
    // Roket yang punya video bawaan diputar satu kali saja (tanpa diulang),
    // lalu langsung ke tahap berikutnya. Roket tanpa video bawaan memakai
    // perilaku lama: diulang sampai waktu meluncur habis.
    val bawaan = e != null && e.url.startsWith("asset/")
    val burstUrl = e?.let { RocketCfg.bundledBurst(it.lv) }
    if (e != null && step == 0) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = if (bawaan) 0f else -rise },
            ) {
                key(playCount) {
                    var lewat by remember(playCount, e.id) { mutableStateOf(false) }
                    val selesai = {
                        if (bawaan) {
                            if (!lewat) { lewat = true; step = if (burstUrl != null) 1 else 2 }
                        } else if (System.currentTimeMillis() < e.end - 1200L) {
                            playCount++
                        } else {
                            step = 2
                        }
                    }
                    if (bawaan) {
                        // Video bawaan ditayangkan sampai habis. Kalau pemutar gagal
                        // (mis. codec di HP tertentu), roket tetap tampil sebagai gambar
                        // diam selama durasi video, baru dialog hadiah muncul.
                        val ms = RocketCfg.videoMs(e.url).coerceAtLeast(2_500L)
                        LaunchedEffect(playCount, e.id) {
                            kotlinx.coroutines.delay(ms)
                            selesai()
                        }
                    }
                    if (isVideoMedia(e.url)) {
                        if (bawaan) {
                            // Latar cadangan: ikon roket level ini, terlihat kalau video
                            // gagal diputar di HP tersebut.
                            androidx.compose.foundation.Image(
                                painter = coil.compose.rememberAsyncImagePainter(
                                    RocketCfg.img.getOrNull(e.lv - 1).orEmpty(),
                                ),
                                contentDescription = null,
                                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.72f),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            )
                        }
                        // Untuk video bawaan, lanjut ke tahap berikutnya hanya lewat
                        // timer durasi video, supaya dialog hadiah tidak muncul
                        // mendahului roket yang meluncur.
                        GiftVideo(e.url, Modifier.fillMaxSize(), sound = true) { if (!bawaan) selesai() }
                        if (bawaan) RocketTopPhoto(RocketRepo.rankRows.firstOrNull()?.photo.orEmpty())
                    } else {
                        GiftSvga(e.url, "\uD83D\uDE80", Modifier.fillMaxSize(), onEnd = { selesai() })
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC101010))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "\uD83D\uDE80 Roket Lv.${e.lv} ${e.name} meluncur!",
                    color = GoldLight, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        if (!bawaan) {
            // Naik tinggi, sekali per putaran animasi (hanya animasi dari panel).
            LaunchedEffect(playCount) {
                rise = 0f
                val start = System.currentTimeMillis()
                val lama = 2400L
                while (System.currentTimeMillis() - start < lama) {
                    delay(16)
                    rise = tinggiNaik * ((System.currentTimeMillis() - start) / lama.toFloat()).coerceIn(0f, 1f)
                }
            }
            // Dialog baru muncul setelah waktu meluncur benar-benar habis.
            LaunchedEffect(e.id) {
                val sisa = e.end - System.currentTimeMillis()
                if (sisa > 0L) delay(sisa)
                step = 2
            }
        } else {
            // Pengaman: hanya kalau video gagal diputar. Mengikuti panjang video
            // roketnya (bukan timer tetap) ditambah jeda aman.
            LaunchedEffect(e.id) {
                delay(RocketCfg.videoMs(e.url) + 2500L)
                if (step == 0) step = if (burstUrl != null) 1 else 2
            }
        }
    }

    // Tahap 1: roket pecah di atas (hanya untuk level yang punya videonya).
    if (e != null && step == 1 && burstUrl != null) {
        val bMs = RocketCfg.videoMs(burstUrl).coerceAtLeast(1_500L)
        Box(Modifier.fillMaxSize()) {
            GiftVideo(burstUrl, Modifier.fillMaxSize(), sound = true) { }
        }
        LaunchedEffect(e.id, "burst") {
            // Efek pecah ditayangkan penuh dulu, baru dialog hadiah muncul.
            delay(bMs + 400L)
            if (step == 1) step = 2
        }
    }

    // Tahap 2: video selesai. Kalau masih ada roket berikutnya yang sudah penuh
    // di antrean, langsung lanjut ke roket itu tanpa menunggu dialog. Hadiah
    // ditampilkan sekali setelah rangkaian peluncuran terakhir selesai.
    LaunchedEffect(e?.id, step) {
        val cur = e ?: return@LaunchedEffect
        if (step != 2 || dialogMode.isNotBlank()) return@LaunchedEffect
        val mode = if (cur.uid == myUid) {
            "launch"
        } else if (RocketRepo.canLucky(myUid, cur.id)) {
            "lucky"
        } else {
            ""
        }
        if (pending.isNotEmpty()) {
            // Roket berikutnya sudah penuh: langsung meluncur. Hadiah roket ini
            // disimpan dan ditampilkan di akhir rangkaian.
            if (mode.isNotBlank()) tertunda = tertunda + (cur to mode)
            ev = null
            step = 0
            playCount = 0
        } else if (mode.isBlank() && tertunda.isEmpty()) {
            ev = null
            step = 0
        } else {
            // Akhir rangkaian: tampilkan hadiah paling akhir (atau yang tertunda).
            val tampil = if (mode.isNotBlank()) mode else tertunda.last().second
            tertunda = emptyList()
            dialogMode = tampil
        }
    }

    if (e != null && dialogMode.isNotBlank()) {
        RocketPrizeDialog(
            myUid = myUid,
            mode = dialogMode,
            rank = 0,
            launchId = e.id,
            lv = e.lv,
            onClose = {
                dialogMode = ""
                ev = null
                step = 0
                playCount = 0
            },
        )
    }
}

/** Satu room yang sedang meluncurkan roket, untuk banner di luar room. */
private data class LiveLaunch(
    val room: String,
    val roomName: String,
    val by: String,
    val lv: Int,
    val end: Long,
)

/**
 * Banner "roket sedang meluncur" yang tampil di mana pun, termasuk di luar room.
 * Bisa diketuk untuk langsung masuk ke room yang sedang meluncurkan roket.
 */
@Composable
fun RocketLiveBanner(currentRoomId: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (RocketCfg.uiOff) return
    LaunchedEffect(Unit) { RocketCfg.start() }
    var list by remember { mutableStateOf(listOf<LiveLaunch>()) }
    var tick by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val reg = Rtdb.listen(Rtdb.ref("rocketNow")) { s ->
            val now = System.currentTimeMillis()
            list = s.children.mapNotNull { c ->
                val room = c.child("room").getValue(String::class.java) ?: c.key ?: return@mapNotNull null
                val ts = c.child("ts").getValue(Long::class.java) ?: return@mapNotNull null
                val dur = c.child("dur").getValue(Long::class.java) ?: RocketCfg.flyDurMs
                if (now > ts + dur) return@mapNotNull null
                LiveLaunch(
                    room = room,
                    roomName = c.child("roomName").getValue(String::class.java) ?: "",
                    by = c.child("name").getValue(String::class.java) ?: "",
                    lv = (c.child("lv").getValue(Int::class.java) ?: 1).coerceIn(1, 5),
                    end = ts + dur,
                )
            }
        }
        onDispose { reg.remove() }
    }

    // Jam kecil supaya banner hilang sendiri begitu roket selesai meluncur.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }

    val now = if (tick == 0L) System.currentTimeMillis() else tick
    val live = list.filter { it.end > now && it.room != currentRoomId }.maxByOrNull { it.end } ?: return
    val sisa = ((live.end - now) / 1000L).coerceAtLeast(0L)
    val judul = if (live.roomName.isNotBlank()) live.roomName else "room lain"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(Color(0xF01B1200), Color(0xF03A2A00))))
            .border(1.5.dp, GoldLight, RoundedCornerShape(50))
            .clickable { onOpen(live.room) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\uD83D\uDE80", fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Roket Lv.${live.lv} sedang meluncur",
                color = GoldLight, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                (if (live.by.isNotBlank()) "${live.by} · " else "") + judul + " · ${sisa}s",
                color = Color(0xFFE8D9A8), fontSize = 11.sp,
            )
        }
        Text("Masuk", color = Color(0xFF2A1B00), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(GoldLight)
                .padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Dialog hadiah roket. Hadiah diundi server, jadi tidak bisa dipilih pemain. */
@Composable
fun RocketPrizeDialog(
    myUid: String,
    mode: String,
    rank: Int,
    launchId: String = "",
    lv: Int = 5,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    var loading by remember { mutableStateOf(true) }
    var coins by remember { mutableStateOf(0L) }
    var vip by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf(0) }
    var kartu by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mode, rank, launchId, lv) {
        val r = SupaRepo.rocketClaimPrize(myUid, mode, rank, launchId, lv)
        coins = r.coins
        vip = r.vip
        days = r.days
        kartu = r.kartu
        err = r.error
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        // Animasi keberuntungan roket (mp4 ikut di dalam APK) di belakang kartu hadiah.
        if (mode == "lucky") {
            key(lv) {
                GiftVideo(RocketCfg.bundledLuck(lv), Modifier.fillMaxSize(), sound = true)
            }
        }
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0B2A5B), Color(0xFF06152E))))
                .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(22.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (mode) {
                    "rank" -> "Hadiah peringkat roket"
                    "lucky" -> "Hadiah keberuntungan 🍀"
                    else -> "Hadiah kontribusi roket 🚀"
                },
                color = GoldLight, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (mode) {
                    "rank" -> "Peringkat kamu #$rank pekan ini"
                    "lucky" -> "Roket Lv.$lv di room meluncur, kamu kebagian!"
                    else -> "Roket Lv.$lv sudah meluncur!"
                },
                color = Color(0xFF9FB6D9), fontSize = 12.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            when {
                loading -> Text("Mengambil hadiah...", color = Color.White, fontSize = 14.sp)
                err != null -> Text(err ?: "", color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center)
                else -> {
                    if (vip > 0) Text("👑 VIP$vip $days hari", color = Color(0xFFFFD54F), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    if (vip > 0 && kartu) Text(
                        "Kartu masuk ke Props. Buka Profil › Alat peraga untuk mengaktifkannya.",
                        color = Color(0xFF9FB6D9), fontSize = 11.sp, textAlign = TextAlign.Center,
                    )
                    if (coins > 0) Text("+${ringkas(coins)} coin", color = Color(0xFFFFD54F), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    Text("Hadiah sudah masuk ke dompetmu.", color = Color(0xFF9FB6D9), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFB8860B))))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Okey", color = Color(0xFF2A1B00), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Foto profil Top 1 EXP roket di jendela bulat badan roket. Posisi dilacak dari
 * video roket bawaan (294x642, disamakan untuk roket 1-5): roket naik dari bawah
 * ke atas pada 0,4 detik pertama, melayang, lalu kamera zoom dan jendela
 * membesar. Foto ikut naik, tetap di jendela saat melayang, lalu memudar.
 */
@Composable
private fun RocketTopPhoto(photo: String) {
    if (photo.isBlank()) return
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(
            4.7f,
            androidx.compose.animation.core.tween(4700, easing = androidx.compose.animation.core.LinearEasing),
        )
    }
    val sec = t.value
    // Titik jalur (detik, y relatif tinggi video) hasil pengukuran frame demi frame.
    val kunci = listOf(
        0.10f to 1.10f, 0.15f to 0.957f, 0.20f to 0.792f, 0.25f to 0.627f,
        0.35f to 0.462f, 0.40f to 0.297f, 1.30f to 0.297f, 1.40f to 0.427f,
        1.50f to 0.480f,
    )
    val y = when {
        sec <= kunci.first().first -> kunci.first().second
        sec >= kunci.last().first -> kunci.last().second
        else -> {
            var out = kunci.last().second
            for (i in 0 until kunci.size - 1) {
                val a = kunci[i]; val b = kunci[i + 1]
                if (sec in a.first..b.first) {
                    val f = (sec - a.first) / (b.first - a.first)
                    out = a.second + (b.second - a.second) * f
                    break
                }
            }
            out
        }
    }
    val alpha = when {
        sec < 0.15f -> 0f
        sec <= 1.45f -> 1f
        sec < 1.75f -> 1f - (sec - 1.45f) / 0.3f
        else -> 0f
    }
    if (alpha <= 0f) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Video 294x642 ditampilkan "fit": tinggi/lebar dihitung dari layar.
        val vw = 294f; val vh = 642f
        val scale = minOf(constraints.maxWidth / vw, constraints.maxHeight / vh)
        val w = vw * scale; val h = vh * scale
        val left = (constraints.maxWidth - w) / 2f
        val top = (constraints.maxHeight - h) / 2f
        val diameter = w * 0.115f
        AsyncImage(
            model = photo,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (left + w * 0.504f - diameter / 2f).toInt(),
                        (top + h * y - diameter / 2f).toInt(),
                    )
                }
                .size(with(LocalDensity.current) { diameter.toDp() })
                .graphicsLayer { this.alpha = alpha }
                .clip(androidx.compose.foundation.shape.CircleShape),
        )
    }
}
