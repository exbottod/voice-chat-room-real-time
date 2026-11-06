package com.rezochatvoice.exceo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Suara room lewat LiveKit di VPS sendiri (tanpa Agora, tanpa kuota menit).
 * Nama objek dipertahankan (AgoraVoice) supaya layar yang memakainya tidak berubah.
 * Satu room = satu room LiveKit (nama = roomId). Token dari VPS, diverifikasi dengan login Firebase.
 */
object AgoraVoice {
    private const val TAG = "LiveKitVoice"
    private const val TOKEN_URL = "https://43-156-99-4.sslip.io/lktoken"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var room: Room? = null
    private var appCtx: Context? = null
    private var joinJob: Job? = null
    private var eventJob: Job? = null
    private val joinLock = Mutex()

    @Volatile private var roomId: String? = null
    @Volatile private var joined = false
    @Volatile private var wantSpeaking = false
    @Volatile private var muteRemote = false
    @Volatile private var sengajaKeluar = false
    @Volatile private var percobaan = 0

    var lastStatus by androidx.compose.runtime.mutableStateOf("")

    /**
     * Tingkat suara tiap peserta (uid -> 0..1), dipakai untuk gelombang suara
     * di belakang kursi mic supaya terlihat siapa yang sedang bicara.
     */
    var levels by androidx.compose.runtime.mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    private var levelJob: Job? = null

    private fun mulaiPantauLevel() {
        levelJob?.cancel()
        levelJob = mainScope.launch {
            while (joined) {
                val r = room
                if (r == null) { delay(300); continue }
                val m = HashMap<String, Float>()
                try {
                    m[r.localParticipant.identity.value] = r.localParticipant.audioLevel
                    for ((_, p) in r.remoteParticipants) {
                        m[p.identity.value] = p.audioLevel
                    }
                } catch (e: Exception) { }
                levels = m
                delay(130)
            }
            levels = emptyMap()
        }
    }

    private fun hentikanPantauLevel() {
        levelJob?.cancel(); levelJob = null
        levels = emptyMap()
    }
        private set
    @Volatile var onProblem: ((String) -> Unit)? = null

    // ===== Volume & rute suara =====
    // Sebelumnya app tidak pernah memberi tahu Android stream mana yang dipakai,
    // jadi tombol volume malah mengatur "Nada dering" dan suara room tetap pelan.
    // Sekarang selama di room, tombol volume mengatur volume panggilan (suara room).
    private var audioMgr: AudioManager? = null
    private var prevVolumeStream: Int? = null
    private var prevSpeaker: Boolean? = null
    private var prevMode: Int? = null
    @Volatile private var routed = false

    private fun findActivity(c: Context?): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) {
            if (x is Activity) return x
            x = x.baseContext
        }
        return x as? Activity
    }

    /** Arahkan tombol volume ke suara room dan pastikan keluar dari speaker. */
    fun tuneAudioRoute(ctx: Context) {
        try {
            val mgr = (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) ?: return
            if (!routed) {
                audioMgr = mgr
                prevMode = try { mgr.mode } catch (e: Exception) { null }
                prevSpeaker = try { mgr.isSpeakerphoneOn } catch (e: Exception) { null }
                findActivity(ctx)?.let { act ->
                    prevVolumeStream = act.volumeControlStream
                    act.volumeControlStream = AudioManager.STREAM_VOICE_CALL
                }
                val max = try { mgr.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) } catch (e: Exception) { 0 }
                val cur = try { mgr.getStreamVolume(AudioManager.STREAM_VOICE_CALL) } catch (e: Exception) { 0 }
                // Suara room tidak boleh mulai dari keadaan nyaris bisu.
                if (max > 0 && cur < (max * 0.7f).toInt()) {
                    try { mgr.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * 0.8f).toInt(), 0) } catch (e: Exception) { }
                }
                routed = true
            }
            try { mgr.mode = AudioManager.MODE_IN_COMMUNICATION } catch (e: Exception) { }
            try { mgr.isSpeakerphoneOn = true } catch (e: Exception) { }
        } catch (e: Exception) {
            Log.e(TAG, "tuneAudioRoute: ${e.message}")
        }
    }

    fun restoreAudioRoute() {
        if (!routed) return
        routed = false
        try {
            val mgr = audioMgr
            if (mgr != null) {
                prevSpeaker?.let { try { mgr.isSpeakerphoneOn = it } catch (e: Exception) { } }
                prevMode?.let { m -> try { mgr.mode = m } catch (e: Exception) { } }
            }
            appCtx?.let { c -> findActivity(c)?.let { act -> prevVolumeStream?.let { act.volumeControlStream = it } } }
        } catch (e: Exception) {
            Log.e(TAG, "restoreAudioRoute: ${e.message}")
        } finally {
            prevVolumeStream = null
            prevSpeaker = null
            prevMode = null
        }
    }

    private fun problem(msg: String) {
        lastStatus = msg
        Log.e(TAG, msg)
        try { onProblem?.invoke(msg) } catch (e: Exception) { }
    }

    private data class Tok(val token: String, val url: String)

    private suspend fun fetchToken(channel: String): Tok? {
        var lastErr = ""
        for (attempt in 1..3) {
            try {
                val idToken = FirebaseAuth.getInstance().currentUser
                    ?.getIdToken(attempt > 1)?.await()?.token
                if (idToken == null) { problem("Suara mati: belum login"); return null }
                val c = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $idToken")
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(JSONObject().put("channel", channel).toString().toByteArray()) }
                if (c.responseCode == 200) {
                    val o = JSONObject(c.inputStream.bufferedReader().readText())
                    return Tok(o.getString("token"), o.getString("url"))
                }
                val body = c.errorStream?.bufferedReader()?.readText().orEmpty()
                lastErr = "token gagal ${c.responseCode} $body"
                if (c.responseCode in 400..499 && c.responseCode != 408 && c.responseCode != 429) break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = "token error ${e.message}"
            }
            delay(700L * attempt)
        }
        problem("Suara mati: $lastErr".take(140))
        return null
    }

    /** Masuk room (awalnya hanya mendengar; mic dibuka lewat setSpeaking). */
    fun join(ctx: Context, id: String) {
        sengajaKeluar = false
        val app = ctx.applicationContext
        appCtx = app
        joinJob?.cancel()
        roomId = id
        joinJob = scope.launch {
            joinLock.withLock {
                if (roomId != id) return@withLock
                val t = fetchToken(id) ?: return@withLock
                if (roomId != id) return@withLock
                try {
                    closeRoom()
                    lastStatus = "Suara: menyambung…"
                    val r = LiveKit.create(app)
                    room = r
                    eventJob = mainScope.launch {
                        r.events.events.collect { ev ->
                            when (ev) {
                                is RoomEvent.Connected -> {
                                    joined = true
                                    percobaan = 0
                                    lastStatus = "OK tersambung"
                                    mulaiPantauLevel()
                                    appCtx?.let { c -> mainScope.launch { tuneAudioRoute(c) } }
                                }
                                is RoomEvent.Reconnecting -> { lastStatus = "Suara: menyambung ulang…" }
                                is RoomEvent.Reconnected -> {
                                    joined = true
                                    percobaan = 0
                                    lastStatus = "OK tersambung"
                                    mulaiPantauLevel()
                                    appCtx?.let { c -> mainScope.launch { tuneAudioRoute(c) } }
                                }
                                is RoomEvent.Disconnected -> {
                                    joined = false
                                    // Sambung ulang otomatis: dulu app langsung menyerah dan
                                    // menampilkan "Suara terputus" saat room ramai / jaringan goyang.
                                    if (!sengajaKeluar && roomId == id && percobaan < 6) {
                                        percobaan += 1
                                        lastStatus = "Suara: menyambung ulang (${percobaan}/6)…"
                                        mainScope.launch {
                                            delay(1200L * percobaan)
                                            val c = appCtx
                                            if (!sengajaKeluar && roomId == id && c != null) join(c, id)
                                        }
                                    } else if (!sengajaKeluar) {
                                        problem("Suara terputus: ${ev.error?.message ?: "jaringan tidak stabil"}")
                                    }
                                }
                                is RoomEvent.FailedToConnect -> problem("Suara gagal tersambung: ${ev.error.message}".take(140))
                                is RoomEvent.TrackSubscribed -> applyMute()
                                else -> {}
                            }
                        }
                    }
                    r.connect(t.url, t.token)
                    joined = true
                    try { tuneAudioRoute(app) } catch (e: Exception) { }
                    lastStatus = "OK tersambung"
                    applyMute()
                    applyRole()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    joined = false
                    problem("Suara gagal tersambung: ${e.message}".take(140))
                }
            }
        }
    }

    private suspend fun closeRoom() {
        eventJob?.cancel(); eventJob = null
        hentikanPantauLevel()
        val r = room ?: return
        room = null
        try { r.disconnect() } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
    }

    private fun applyMute() {
        val r = room ?: return
        try {
            for ((_, p) in r.remoteParticipants) {
                for (pub in p.audioTrackPublications) {
                    (pub.second as? RemoteAudioTrack)?.setVolume(if (muteRemote) 0.0 else 1.0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyMute: ${e.message}")
        }
    }

    private fun applyRole() {
        val r = room ?: return
        val on = wantSpeaking
        mainScope.launch {
            try { r.localParticipant.setMicrophoneEnabled(on) } catch (e: Exception) {
                Log.e(TAG, "mic: ${e.message}")
                if (on) problem("Mic gagal: ${e.message}".take(100))
            }
        }
    }

    /** true = mic menyala (di kursi); false = hanya mendengar. Aman dipanggil sebelum join selesai. */
    fun setSpeaking(on: Boolean) {
        wantSpeaking = on
        applyRole()
    }

    fun muteRoom(mute: Boolean) {
        muteRemote = mute
        applyMute()
    }

    fun leave() {
        sengajaKeluar = true
        percobaan = 0
        try { restoreAudioRoute() } catch (e: Exception) { }
        joinJob?.cancel()
        joined = false
        roomId = null
        wantSpeaking = false
        muteRemote = false
        stopMusic()
        scope.launch { joinLock.withLock { closeRoom() } }
    }

    // ---------- Musik room ----------
    // Musik diputar lokal di perangkat DJ (MediaPlayer). Suara speaker ikut
    // tertangkap mic saat DJ di kursi dan mic menyala.
    private var player: MediaPlayer? = null
    private var musicVol = 100

    fun startMusic(path: String, loop: Boolean): Boolean {
        return try {
            stopMusic()
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.isLooping = loop
            mp.setVolume(musicVol / 100f, musicVol / 100f)
            mp.prepare()
            mp.start()
            player = mp
            wantSpeaking = true
            applyRole()
            true
        } catch (e: Exception) {
            Log.e(TAG, "musik gagal: ${e.message}")
            false
        }
    }

    fun stopMusic() { try { player?.stop(); player?.release() } catch (e: Exception) { }; player = null }
    fun pauseMusic() { try { player?.pause() } catch (e: Exception) { } }
    fun resumeMusic() { try { player?.start() } catch (e: Exception) { } }
    fun seekMusic(ms: Long) { try { player?.seekTo(ms.toInt()) } catch (e: Exception) { } }
    fun musicDurationMs(): Int = try {
        player?.duration ?: RoomMusicShare.durasiMs()
    } catch (e: Exception) { -1 }
    fun musicPositionMs(): Int = try {
        player?.currentPosition ?: RoomMusicShare.posisiMs()
    } catch (e: Exception) { 0 }
    fun setMusicVolume(v: Int) {
        musicVol = v.coerceIn(0, 100)
        try { player?.setVolume(musicVol / 100f, musicVol / 100f) } catch (e: Exception) { }
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
