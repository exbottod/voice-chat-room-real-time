package com.example.googlelogin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Agora RTC (voice only). Satu channel = satu room (channel = roomId). Token diminta dari Cloudflare Worker. */
object AgoraVoice {
    private const val TAG = "AgoraVoice"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: RtcEngine? = null
    private var appIdInUse = ""

    @Volatile private var inChannel = false

    @Volatile private var joined = false

    @Volatile private var roomId: String? = null

    @Volatile private var wantSpeaking = false

    @Volatile private var muteRemote = false

    /** Sekali coba ulang saat token ditolak Agora (error 109/110). */
    @Volatile private var retriedToken = false

    private var appCtx: Context? = null
    private var joinJob: Job? = null

    /**
     * Beberapa operator seluler memblokir port media Agora (UDP 1089-1091), akibatnya
     * joinChannel balas 0 tetapi channel tidak pernah tersambung: layar menampilkan
     * "Suara: menyambung ulang…" terus. Cloud proxy memaksa lewat TLS 443.
     */
    @Volatile private var pakaiProxy = true
    @Volatile private var sudahCobaTanpaProxy = false

    /**
     * Penolakan token (alasan 6-10) bukan alasan menyerah: server token pertama
     * bisa memakai sertifikat yang berbeda dengan project Agora. Kita naikkan
     * eskalasi: coba server token lain, lalu terakhir masuk tanpa token (sah
     * kalau project tidak memakai App Certificate).
     */
    @Volatile private var sumberIdx = 0
    @Volatile private var eskalasi = 0
    @Volatile private var tanpaToken = false
    @Volatile private var sumberTerakhir = ""

    /** Ringkasan asal token, ikut ditampilkan kalau suara gagal supaya penyebabnya jelas. */
    @Volatile private var infoToken = ""

    /** Status terakhir untuk diagnosa (ditampilkan sebagai toast bila gagal). */
    // Dibaca layar room supaya kalau suara gagal, alasannya kelihatan langsung.
    var lastStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    @Volatile var onProblem: ((String) -> Unit)? = null
    private fun problem(msg: String) {
        lastStatus = msg
        Log.e(TAG, msg)
        try { onProblem?.invoke(msg) } catch (e: Exception) { }
    }

    private val handler = object : IRtcEngineEventHandler() {
        override fun onError(err: Int) {
            // 109/110 = token Agora ditolak. Ambil token baru lalu masuk ulang sekali,
            // supaya suara tidak mati sampai aplikasi ditutup.
            if ((err == 109 || err == 110) && !retriedToken) {
                retriedToken = true
                val c = appCtx
                val r = roomId
                problem("Suara: token diperbarui, menyambung ulang…")
                if (r != null) tokenCache.remove(r)
                if (c != null && r != null) {
                    scope.launch {
                        kotlinx.coroutines.delay(600)
                        join(c, r)
                    }
                }
                return
            }
            problem("Suara error $err")
        }

        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            joined = true
            retriedToken = false
            eskalasi = 0
            lastStatus = "OK joined $channel"
            Log.i(TAG, lastStatus)
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            // 3 = CONNECTED. Sebagian HP jarang memanggil onJoinChannelSuccess,
            // akibatnya aplikasi terus menyambung ulang padahal suara sudah jalan.
            Log.i(TAG, "koneksi state=$state alasan=$reason")
            if (state == 3) {
                joined = true
                retriedToken = false
                eskalasi = 0
                lastStatus = "OK tersambung"
            } else if (state == 5 && !joined) {
                lastStatus = "Suara: gagal tersambung ($reason)"
                // 6..10 = app id/channel/token ditolak server. Coba jalur lain.
                if (reason in 6..10 && eskalasi >= 2) {
                    // Semua jalur sudah dicoba: penyebabnya di console Agora.
                    lastStatus = "Suara mati: token ditolak Agora ($infoToken)"
                }
                if (reason in 6..10 && eskalasi < 2) {
                    eskalasi++
                    val c = appCtx
                    val r = roomId
                    if (eskalasi == 1) {
                        // Server token ini ditolak: pindah ke server berikutnya.
                        val daftar = sumberToken()
                        sumberIdx = (sumberIdx + 1) % daftar.size
                        sumberTerakhir = daftar[sumberIdx]
                        tokenCache.clear()
                        lastStatus = "Suara: token ditolak, coba server lain…"
                    } else {
                        // Semua token ditolak: masuk tanpa token (proyek tanpa sertifikat).
                        tanpaToken = true
                        tokenCache.clear()
                        lastStatus = "Suara: token ditolak, coba tanpa token…"
                    }
                    if (c != null && r != null) {
                        scope.launch {
                            kotlinx.coroutines.delay(700)
                            join(c, r)
                        }
                    }
                }
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            val r = roomId ?: return
            scope.launch { fetchToken(r)?.let { engine?.renewToken(it.token) } }
        }
    }

    private fun ensure(ctx: Context, appId: String): RtcEngine? {
        val use = appId.ifBlank { BuildConfig.AGORA_APP_ID }
        engine?.let {
            if (appIdInUse == use) return it
            try { it.leaveChannel() } catch (e: Exception) { }
            try { RtcEngine.destroy() } catch (e: Exception) { }
            engine = null
            inChannel = false
        }
        if (use.isBlank()) {
            problem("Suara mati: AGORA_APP_ID kosong di build")
            return null
        }
        return try {
            val cfg = RtcEngineConfig().apply {
                mContext = ctx.applicationContext
                mAppId = use
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                mEventHandler = handler
            }
            RtcEngine.create(cfg).also {
                it.setAudioProfile(Constants.AUDIO_PROFILE_SPEECH_STANDARD, Constants.AUDIO_SCENARIO_CHATROOM)
                it.enableAudio()
                // Suara room harus keluar dari speaker, bukan earpiece.
                try { it.setDefaultAudioRoutetoSpeakerphone(true) } catch (e: Exception) { }
                try { it.setEnableSpeakerphone(true) } catch (e: Exception) { }
                // Wajib untuk HP yang jaringannya memblokir UDP Agora.
                // Nama konstanta proxy berbeda antar versi SDK, jadi dipanggil
                // lewat refleksi supaya build tidak gagal saat SDK diperbarui.
                try {
                    val m = it.javaClass.methods.firstOrNull {
                        it.name == "setCloudProxy" && it.parameterTypes.size == 1
                    }
                    if (m != null) {
                        val tipe = m.parameterTypes[0]
                        val nilai = if (tipe.isEnum) {
                            tipe.enumConstants?.firstOrNull { e ->
                                (e as Enum<*>).name.contains("AUTO")
                            }
                        } else {
                            // Angka: 3 = auto proxy pada SDK versi lama.
                            3
                        }
                        if (nilai != null) m.invoke(it, nilai)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "cloud proxy gagal: ${e.message}")
                }
                engine = it
                appIdInUse = use
            }
        } catch (e: Exception) {
            Log.e(TAG, "init gagal", e)
            problem("Suara mati: SDK gagal dimuat (" + (e.message ?: "tak diketahui") + ")")
            null
        }
    }

    private data class Tok(val token: String, val appId: String, val menit: Long = 0)

    /** Token terakhir per channel, dipakai ulang kalau worker lambat (berlaku 24 jam). */
    private val tokenCache = HashMap<String, Pair<Long, Tok>>()

    /** Server token yang tersedia, urut dari yang paling utama. */
    private fun sumberToken(): List<String> {
        // Token suara hanya dari VPS (sertifikat Agora sudah dipasang di sana).
        // Server Base44 dan worker Cloudflare lama tidak dipakai lagi.
        val kandidat = arrayListOf(
            "https://43-156-99-4.sslip.io/agora",
            // Cadangan: layanan token di VPS baru menghasilkan token yang benar
            // setelah skrip pembaruannya dijalankan ulang oleh owner.
            "https://superagent-6187eafe.base44.app/functions/agoraToken2",
        )
        // Worker Cloudflare lama sudah kena batas kuota (429) dan hanya
        // memperlambat: jangan pernah dipakai lagi sebagai sumber token.
        BuildConfig.AGORA_TOKEN_URL
            .takeIf { it.isNotBlank() && !it.contains("workers.dev") }
            ?.let { kandidat.add(it) }
        return kandidat
    }

    private suspend fun fetchToken(channel: String): Tok? {
        if (tanpaToken) return null
        val kandidat = sumberToken().let { l ->
            val mulai = sumberIdx.coerceIn(0, l.size - 1)
            l.drop(mulai) + l.take(mulai)
        }
        sumberTerakhir = kandidat.firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        tokenCache[channel]?.let { (at, tok) ->
            if (now - at < 6L * 3600_000L) return tok
        }
        var lastErr = ""
        for (url in kandidat) {
            // Dua percobaan per server: jaringan seluler sering putus-nyambung.
            for (attempt in 1..2) {
                try {
                    val idToken = FirebaseAuth.getInstance().currentUser
                        ?.getIdToken(attempt > 1)?.await()?.token
                    if (idToken == null) { problem("Suara mati: belum login"); return null }
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $idToken")
                        setRequestProperty("Content-Type", "application/json")
                    }
                    c.outputStream.use { it.write(JSONObject().put("channel", channel).toString().toByteArray()) }
                    if (c.responseCode != 200) {
                        val body = c.errorStream?.bufferedReader()?.readText().orEmpty()
                        lastErr = "token gagal ${c.responseCode} $body"
                        // 4xx (login/secret salah) tidak akan sembuh dengan mengulang.
                        if (c.responseCode in 400..499 && c.responseCode != 408 && c.responseCode != 429) break
                    } else {
                        val o = JSONObject(c.inputStream.bufferedReader().readText())
                        val tok = Tok(
                            o.getString("token"),
                            o.optString("appId", ""),
                            (o.optLong("expiresIn", o.optLong("ttl", 0)) / 60).coerceAtLeast(0),
                        )
                        infoToken = "sumber=${URL(url).host} appId=…${tok.appId.takeLast(6)} sisa=${tok.menit}m"
                        tokenCache[channel] = System.currentTimeMillis() to tok
                        return tok
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = "token error ${e.message}"
                }
                kotlinx.coroutines.delay(700L * attempt)
            }
        }
        problem("Suara mati: $lastErr".take(140))
        return null
    }

    /** Satu join pada satu waktu; join baru menunggu join lama selesai. */
    private val joinLock = kotlinx.coroutines.sync.Mutex()

    /** Masuk channel sebagai audience (hanya mendengar). */
    fun join(ctx: Context, id: String) {
        val app = ctx.applicationContext
        appCtx = app
        joinJob?.cancel()
        roomId = id
        joinJob = scope.launch {
            joinLock.withLock {
                if (roomId != id) return@withLock
                // Coba minta token; kalau worker gagal, tetap coba masuk tanpa token
                // (berhasil bila project Agora tidak memakai sertifikat).
                val t = fetchToken(id)
                if (t == null && !tanpaToken) {
                    // Jangan masuk dengan token kosong tanpa alasan: itu yang
                    // memunculkan "gagal tersambung (10)" tanpa penjelasan.
                    problem("Suara mati: server token tidak bisa dihubungi (cek VPS)")
                    return@withLock
                }
                if (tanpaToken) problem("Suara: masuk tanpa token (proyek tanpa sertifikat)")
                if (roomId != id) return@withLock
                val useAppId = t?.appId?.takeIf { it.isNotBlank() } ?: BuildConfig.AGORA_APP_ID
                val e = ensure(app, useAppId) ?: return@withLock

                // Selalu keluar dari sesi lama dulu dan beri waktu selesai.
                // leaveChannel berjalan asinkron; kalau langsung join, Agora menolak
                // dengan error -17 (ERR_JOIN_CHANNEL_REJECTED).
                try { e.leaveChannel() } catch (ex: Exception) { }
                inChannel = false
                joined = false
                kotlinx.coroutines.delay(500)

                val opt = ChannelMediaOptions().apply {
                    clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
                    channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                    autoSubscribeAudio = true
                    publishMicrophoneTrack = false
                }
                var r = e.joinChannel(t?.token ?: "", id, 0, opt)
                // -17: sesi lama belum benar-benar tertutup. Tutup lalu coba lagi.
                var coba = 0
                while ((r == -17 || r == -5) && coba < 4 && roomId == id) {
                    coba++
                    try { e.leaveChannel() } catch (ex: Exception) { }
                    kotlinx.coroutines.delay(700L * coba)
                    r = e.joinChannel(t?.token ?: "", id, 0, opt)
                }
                if (r != 0) {
                    problem("Suara mati: joinChannel gagal $r")
                    return@withLock
                }
                inChannel = true
                e.muteAllRemoteAudioStreams(muteRemote)
                try { e.setEnableSpeakerphone(true) } catch (ex: Exception) { }
                applyRole()
            }

            // Kalau 12 detik belum benar-benar masuk channel, coba sekali lagi.
            kotlinx.coroutines.delay(12000)
            if (roomId != id) return@launch
            if (!joined) {
                // Belum tersambung: coba lewat jalur lain (tanpa proxy) sekali,
                // karena sebagian jaringan justru menghalangi proxy tapi lancar UDP.
                if (pakaiProxy && !sudahCobaTanpaProxy) {
                    sudahCobaTanpaProxy = true
                    pakaiProxy = false
                    try { engine?.setCloudProxy(0) } catch (ex: Exception) { }
                    lastStatus = "Suara: menyambung ulang…"
                } else {
                    lastStatus = "Suara: menyambung ulang…"
                }
                Log.e(TAG, lastStatus + " proxy=$pakaiProxy")
                join(ctx, id)
            }
        }
    }

    private fun applyRole() {
        val e = engine ?: return
        if (!inChannel) return
        val on = wantSpeaking
        val opt = ChannelMediaOptions().apply {
            clientRoleType = if (on) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
            publishMicrophoneTrack = on
            autoSubscribeAudio = true
        }
        e.updateChannelMediaOptions(opt)
    }

    /** true = broadcaster + kirim mic; false = audience. Aman dipanggil sebelum join selesai. */
    fun setSpeaking(on: Boolean) {
        wantSpeaking = on
        applyRole()
    }

    fun muteRoom(mute: Boolean) {
        muteRemote = mute
        engine?.muteAllRemoteAudioStreams(mute)
    }

    fun leave() {
        joinJob?.cancel()
        // Setiap kali keluar room, mulai lagi dari token sungguhan. Tanpa ini,
        // status "tanpa token" dari room lama terbawa ke room berikutnya.
        tanpaToken = false
        eskalasi = 0
        sumberIdx = 0
        joined = false
        roomId = null
        wantSpeaking = false
        muteRemote = false
        engine?.leaveChannel()
        inChannel = false
    }

    // ---------- Musik room (audio mixing) ----------
    // replace=false: musik dicampur dengan suara mic DJ lalu dikirim ke channel,
    // sehingga terdengar oleh SEMUA pengguna di room (bukan hanya di perangkat DJ).

    /** Putar file musik. loop=true memutar berulang tanpa henti. */
    fun startMusic(path: String, loop: Boolean): Boolean {
        val e = engine ?: return false
        wantSpeaking = true // pastikan role broadcaster agar musik terkirim
        applyRole()
        val r = e.startAudioMixing(path, false, if (loop) -1 else 1)
        if (r != 0) Log.e(TAG, "startAudioMixing gagal $r")
        return r == 0
    }

    fun stopMusic() {
        engine?.stopAudioMixing()
    }

    fun pauseMusic() {
        engine?.pauseAudioMixing()
    }

    fun resumeMusic() {
        engine?.resumeAudioMixing()
    }

    fun seekMusic(ms: Long) {
        try {
            engine?.setAudioMixingPosition(ms.toInt())
        } catch (e: Exception) {
        }
    }

    /** Durasi lagu berjalan (ms), -1 kalau tidak ada. */
    fun musicDurationMs(): Int = try {
        engine?.getAudioMixingDuration() ?: -1
    } catch (e: Exception) {
        -1
    }

    /** Posisi lagu berjalan (ms). */
    fun musicPositionMs(): Int = try {
        engine?.getAudioMixingCurrentPosition() ?: 0
    } catch (e: Exception) {
        0
    }

    /** Volume musik 0..100 (terpisah dari volume mic). */
    fun setMusicVolume(v: Int) {
        try {
            engine?.adjustAudioMixingVolume(v.coerceIn(0, 100))
        } catch (e: Exception) {
        }
    }
}

/** Aktif selama user di room (termasuk saat diminimalkan). */
@Composable
fun AgoraRoomBridge(roomId: String, myUid: String) {
    val ctx = LocalContext.current
    var members by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mySeat by remember { mutableStateOf<Seat?>(null) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    DisposableEffect(roomId) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        AgoraVoice.onProblem = { m -> main.post { android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show() } }
        AgoraVoice.join(ctx, roomId)
        val a = RoomRepo.observeMembers(roomId) { members = it }
        val b = RoomRepo.observeSeats(roomId) { seats ->
            mySeat = seats.values.firstOrNull { it.uid == myUid }
        }
        onDispose {
            a.remove()
            b.remove()
            AgoraVoice.onProblem = null
            AgoraVoice.leave()
        }
    }

    // Mic ditentukan oleh KURSI saja. Sebelumnya juga butuh myUid di members, padahal
    // user dengan "Invisible in rooms" (SVIP7) / join gagal tidak masuk members,
    // sehingga suaranya tidak pernah terkirim.
    val onSeat = mySeat != null
    LaunchedEffect(onSeat) { if (onSeat && !granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(onSeat, mySeat?.muted, granted) {
        AgoraVoice.setSpeaking(onSeat && granted && mySeat?.muted == false)
    }
}
