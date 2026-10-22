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
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Agora RTC (voice only). Satu channel = satu room (channel = roomId). Token diminta dari Cloudflare Worker. */
object AgoraVoice {
    private const val TAG = "AgoraVoice"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: RtcEngine? = null

    @Volatile private var inChannel = false

    @Volatile private var roomId: String? = null

    @Volatile private var wantSpeaking = false

    @Volatile private var muteRemote = false
    private var joinJob: Job? = null

    private val handler = object : IRtcEngineEventHandler() {
        override fun onError(err: Int) {
            Log.e(TAG, "onError $err")
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            val r = roomId ?: return
            scope.launch { fetchToken(r)?.let { engine?.renewToken(it) } }
        }
    }

    private fun ensure(ctx: Context): RtcEngine? {
        engine?.let { return it }
        if (BuildConfig.AGORA_APP_ID.isBlank()) {
            Log.e(TAG, "AGORA_APP_ID kosong")
            return null
        }
        return try {
            val cfg = RtcEngineConfig().apply {
                mContext = ctx.applicationContext
                mAppId = BuildConfig.AGORA_APP_ID
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                mEventHandler = handler
            }
            RtcEngine.create(cfg).also {
                it.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT, Constants.AUDIO_SCENARIO_GAME_STREAMING)
                it.enableAudio()
                engine = it
            }
        } catch (e: Exception) {
            Log.e(TAG, "init gagal", e)
            null
        }
    }

    private suspend fun fetchToken(channel: String): String? {
        val url = BuildConfig.AGORA_TOKEN_URL
        if (url.isBlank()) {
            Log.e(TAG, "AGORA_TOKEN_URL kosong")
            return null
        }
        return try {
            val idToken = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token ?: return null
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $idToken")
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(JSONObject().put("channel", channel).toString().toByteArray()) }
            if (c.responseCode != 200) {
                Log.e(TAG, "token gagal ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()}")
                return null
            }
            JSONObject(c.inputStream.bufferedReader().readText()).getString("token")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "token error", e)
            null
        }
    }

    /** Masuk channel sebagai audience (hanya mendengar). */
    fun join(ctx: Context, id: String) {
        val app = ctx.applicationContext
        joinJob?.cancel()
        roomId = id
        joinJob = scope.launch {
            val e = ensure(app) ?: return@launch
            if (inChannel) {
                e.leaveChannel()
                inChannel = false
            }
            val token = fetchToken(id) ?: return@launch
            if (roomId != id) return@launch
            val opt = ChannelMediaOptions().apply {
                clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                autoSubscribeAudio = true
                publishMicrophoneTrack = false
            }
            val r = e.joinChannel(token, id, 0, opt)
            if (r != 0) {
                Log.e(TAG, "joinChannel gagal: $r")
                return@launch
            }
            inChannel = true
            e.muteAllRemoteAudioStreams(muteRemote)
            applyRole()
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
        val r = e.startAudioMixing(path, false, false, if (loop) -1 else 1)
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
        AgoraVoice.join(ctx, roomId)
        val a = RoomRepo.observeMembers(roomId) { members = it }
        val b = RoomRepo.observeSeats(roomId) { seats ->
            mySeat = seats.values.firstOrNull { it.uid == myUid }
        }
        onDispose {
            a.remove()
            b.remove()
            AgoraVoice.leave()
        }
    }

    val onSeat = mySeat != null && myUid in members
    LaunchedEffect(onSeat) { if (onSeat && !granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(onSeat, mySeat?.muted, granted) {
        AgoraVoice.setSpeaking(onSeat && granted && mySeat?.muted == false)
    }
}
