package com.example.googlelogin

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val MusicTeal = Color(0xFF1DE9B6)
private val MusicGold = Color(0xFFFFB300)

data class MusicTrack(val title: String, val path: String)

/**
 * Playlist & state pemutar musik room.
 * Musik diputar lewat Agora audio mixing dari perangkat DJ (yang naik mic),
 * dicampur dengan suara mic dan dikirim ke channel, jadi terdengar oleh semua pengguna di room.
 * Playlist disimpan lokal di perangkat DJ.
 */
object MusicEngine {
    var playlist by mutableStateOf(listOf<MusicTrack>())
    var current by mutableStateOf(-1)
    var playing by mutableStateOf(false)
    var looping by mutableStateOf(false)
    var volume by mutableStateOf(80)

    /** Salin lagu dari URI (file picker) ke cache agar bisa diputar Agora. Return jumlah sukses. */
    suspend fun importFromUris(context: android.content.Context, uris: List<android.net.Uri>): Int =
        withContext(Dispatchers.IO) {
            var ok = 0
            for (uri in uris) {
                try {
                    var name = "Musik"
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (i >= 0) c.getString(i)?.let { name = it }
                        }
                    }
                    val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val f = File(context.cacheDir, "music_${System.currentTimeMillis()}_$safe")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    } ?: continue
                    playlist = playlist + MusicTrack(name, f.absolutePath)
                    ok++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }
            ok
        }

    fun play(index: Int): Boolean {
        if (index !in playlist.indices) return false
        if (!AgoraVoice.startMusic(playlist[index].path, looping)) return false
        current = index
        playing = true
        AgoraVoice.setMusicVolume(volume)
        return true
    }

    fun resume() {
        AgoraVoice.resumeMusic()
        playing = true
    }

    fun pause() {
        AgoraVoice.pauseMusic()
        playing = false
    }

    /** false = pause, true = play/resume. Return status playing setelahnya. */
    fun toggle(): Boolean {
        if (playing) {
            pause()
            return false
        }
        if (current >= 0) {
            resume()
            return true
        }
        return play(0)
    }

    fun next() {
        if (playlist.isNotEmpty()) play((current + 1).mod(playlist.size))
    }

    fun prev() {
        if (playlist.isEmpty()) return
        if (AgoraVoice.musicPositionMs() > 3000) {
            AgoraVoice.seekMusic(0)
        } else {
            play(if (current <= 0) playlist.size - 1 else current - 1)
        }
    }

    fun stop() {
        AgoraVoice.stopMusic()
        playing = false
        current = -1
    }

    /** Dipanggil saat keluar room. Playlist tetap tersimpan untuk nanti. */
    fun reset() {
        stop()
        current = -1
    }

    fun seekFraction(f: Float) {
        val d = AgoraVoice.musicDurationMs()
        if (d > 0) AgoraVoice.seekMusic((f * d).toLong())
    }
}

/**
 * Panel pemutar musik room (bawah layar), tampilannya seperti desain:
 * judul lagu, slider progres + persen, dan baris kontrol loop/prev/play/next/playlist.
 * Memutar musik hanya boleh untuk user yang sedang naik mic (DJ), agar suara terkirim ke semua.
 */
@Composable
fun MusicSheet(
    roomId: String,
    myUid: String,
    myName: String,
    canPlay: Boolean,
    onUnmute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPlaylist by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var dur by remember { mutableStateOf(-1) }
    var busyImport by remember { mutableStateOf(false) }
    var seekDrag by remember { mutableStateOf<Float?>(null) }

    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busyImport = true
                val n = MusicEngine.importFromUris(context, uris)
                busyImport = false
                toast(if (n > 0) "$n musik masuk ke playlist" else "Gagal membaca file musik")
            }
        }
    }

    fun startPlay() {
        if (!canPlay) {
            toast("Naik mic dulu untuk memutar musik")
            return
        }
        onUnmute()
        if (MusicEngine.toggle() && MusicEngine.current >= 0) {
            val t = MusicEngine.playlist.getOrNull(MusicEngine.current)?.title ?: ""
            scope.launch { RoomExtras.sendChat(roomId, myUid, myName, "🎧 Memutar: $t") }
        }
    }

    // Update progres & lanjut otomatis ke lagu berikutnya
    LaunchedEffect(Unit) {
        while (true) {
            if (MusicEngine.playing) {
                pos = AgoraVoice.musicPositionMs()
                dur = AgoraVoice.musicDurationMs()
                if (dur > 0 && pos >= dur - 250 && !MusicEngine.looping) {
                    if (MusicEngine.current < MusicEngine.playlist.size - 1) MusicEngine.next()
                    else MusicEngine.stop()
                }
            }
            delay(400)
        }
    }

    Box(Modifier.fillMaxSize().blockTouches()) {
        // Area atas: tutup panel
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Color(0xFF000000))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Judul lagu
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (MusicEngine.current >= 0) MusicEngine.playlist.getOrNull(MusicEngine.current)?.title ?: ""
                    else "Belum ada musik diputar",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("✕", color = Color(0xFF9A9A9A), modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
            }
            Spacer(Modifier.height(10.dp))

            // Slider progres + persen
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("🔊", fontSize = 18.sp, modifier = Modifier.clickable { volumeOpen = !volumeOpen })
                Spacer(Modifier.width(8.dp))
                val f = seekDrag ?: (if (dur > 0) pos.toFloat() / dur else 0f)
                Slider(
                    value = f.coerceIn(0f, 1f),
                    onValueChange = { seekDrag = it },
                    onValueChangeFinished = {
                        seekDrag?.let { MusicEngine.seekFraction(it) }
                        seekDrag = null
                    },
                    colors = SliderDefaults.colors(
                        activeTrackColor = MusicGold,
                        inactiveTrackColor = Color(0xFF2B2B2B),
                        thumbColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text("${(f * 100).toInt().coerceIn(0, 100)}%", color = Color(0xFFBDBDBD), fontSize = 12.sp)
            }
            if (volumeOpen) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔈", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = MusicEngine.volume / 100f,
                        onValueChange = {
                            MusicEngine.volume = (it * 100).toInt()
                            AgoraVoice.setMusicVolume(MusicEngine.volume)
                        },
                        colors = SliderDefaults.colors(
                            activeTrackColor = MusicGold,
                            inactiveTrackColor = Color(0xFF2B2B2B),
                            thumbColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // Baris kontrol: loop, prev, play/pause, next, playlist
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "↻",
                    fontSize = 24.sp,
                    color = if (MusicEngine.looping) MusicGold else Color(0xFF9A9A9A),
                    modifier = Modifier.clickable {
                        MusicEngine.looping = !MusicEngine.looping
                        if (MusicEngine.playing && MusicEngine.current >= 0) MusicEngine.play(MusicEngine.current)
                    },
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    "⏮",
                    color = Color.White,
                    fontSize = 26.sp,
                    modifier = Modifier.clickable {
                        if (MusicEngine.playlist.isEmpty()) toast("Playlist masih kosong") else MusicEngine.prev()
                    },
                )
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier.size(62.dp).clip(CircleShape).background(MusicTeal).clickable { startPlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (MusicEngine.playing) "⏸" else "▶",
                        color = Color.White,
                        fontSize = 28.sp,
                        modifier = Modifier.offset(x = if (MusicEngine.playing) 0.dp else 2.dp),
                    )
                }
                Spacer(Modifier.width(24.dp))
                Text(
                    "⏭",
                    color = Color.White,
                    fontSize = 26.sp,
                    modifier = Modifier.clickable {
                        if (MusicEngine.playlist.isEmpty()) toast("Playlist masih kosong") else MusicEngine.next()
                    },
                )
                Spacer(Modifier.width(24.dp))
                Text("☰", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable { showPlaylist = !showPlaylist })
            }

            // Playlist + tombol tambah musik dari perangkat
            if (showPlaylist) {
                Spacer(Modifier.height(10.dp))
                Text("Playlist (${MusicEngine.playlist.size})", color = GoldLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(MusicEngine.playlist.size) { i ->
                        val t = MusicEngine.playlist[i]
                        val isCur = i == MusicEngine.current
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (!canPlay) {
                                    toast("Naik mic dulu untuk memutar musik")
                                } else {
                                    onUnmute()
                                    if (MusicEngine.play(i)) {
                                        scope.launch { RoomExtras.sendChat(roomId, myUid, myName, "🎧 Memutar: ${t.title}") }
                                    }
                                }
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (isCur) "▶" else "♪", color = if (isCur) MusicTeal else GoldLight, fontSize = 15.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                t.title,
                                color = if (isCur) MusicGold else Color.White,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                        .background(Brush.verticalGradient(listOf(Color(0xFFFFE680), Color(0xFFFFC107))))
                        .clickable { picker.launch(arrayOf("audio/*")) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busyImport) "Menyalin musik..." else "➕ Tambah musik dari perangkat",
                        color = Color(0xFF4A2F00),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
