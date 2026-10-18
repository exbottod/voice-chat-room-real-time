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
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig

/** Agora RTC (voice only). Satu channel = satu room (channel = roomId). */
object AgoraVoice {
    private const val TAG = "AgoraVoice"
    private var engine: RtcEngine? = null
    private var inChannel = false

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
                mEventHandler = object : IRtcEngineEventHandler() {
                    override fun onError(err: Int) { Log.e(TAG, "onError $err") }
                }
            }
            RtcEngine.create(cfg).also {
                it.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT, Constants.AUDIO_SCENARIO_GAME_STREAMING)
                it.enableAudio()
                engine = it
            }
        } catch (e: Exception) { Log.e(TAG, "init gagal", e); null }
    }

    /** Masuk channel sebagai audience (hanya mendengar). */
    fun join(ctx: Context, roomId: String) {
        val e = ensure(ctx) ?: return
        if (inChannel) e.leaveChannel()
        val opt = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            autoSubscribeAudio = true
            publishMicrophoneTrack = false
        }
        // Token null = hanya untuk testing (App Certificate harus nonaktif).
        val r = e.joinChannel(null, roomId, 0, opt)
        inChannel = r == 0
        if (r != 0) Log.e(TAG, "joinChannel gagal: $r")
    }

    /** true = broadcaster + kirim mic; false = audience. */
    fun setSpeaking(on: Boolean) {
        val e = engine ?: return
        if (!inChannel) return
        val opt = ChannelMediaOptions().apply {
            clientRoleType = if (on) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
            publishMicrophoneTrack = on
            autoSubscribeAudio = true
        }
        e.updateChannelMediaOptions(opt)
    }

    fun muteRoom(mute: Boolean) { engine?.muteAllRemoteAudioStreams(mute) }

    fun leave() {
        engine?.leaveChannel()
        inChannel = false
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
        onDispose { a.remove(); b.remove(); AgoraVoice.leave() }
    }

    val onSeat = mySeat != null && myUid in members
    LaunchedEffect(onSeat) { if (onSeat && !granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(onSeat, mySeat?.muted, granted) {
        AgoraVoice.setSpeaking(onSeat && granted && mySeat?.muted == false)
    }
}
