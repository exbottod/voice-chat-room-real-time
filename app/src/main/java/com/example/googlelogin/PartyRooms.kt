package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect

/**
 * Daftar room disimpan di satu tempat yang hidup selama aplikasi berjalan.
 * Tujuannya: pindah tab (Moment -> Party, dsb) tidak memuat ulang daftar room,
 * jadi tidak ada kedipan dan tidak ada permintaan server berulang. Data baru
 * hanya diambil saat pengguna menarik sendiri (refreshKey naik).
 */
object RoomFeed {
    var rooms by mutableStateOf(emptyList<RoomInfo>())
        private set
    var online by mutableStateOf(emptyMap<String, Int>())
        private set
    private var regs: List<Reg> = emptyList()

    fun start() {
        if (regs.isNotEmpty()) return
        regs = listOf(
            RoomRepo.observeRoomsMerged { rooms = it.sortedByDescending { r -> r.pinnedAt } },
            RoomRepo.observeAllOnline { online = it },
        )
    }

    /** Tarik-ulang manual: lepas sambungan lama lalu sambung lagi (baca ulang dari server). */
    fun restart() {
        stop()
        start()
    }

    fun stop() {
        regs.forEach { it.remove() }
        regs = emptyList()
    }
}

@Composable
fun rememberRooms(refreshKey: Int = 0): List<RoomInfo> {
    LaunchedEffect(Unit) { RoomFeed.start() }
    LaunchedEffect(refreshKey) { if (refreshKey > 0) RoomFeed.restart() }
    val rooms = RoomFeed.rooms
    val online = RoomFeed.online
    return remember(rooms, online) { rooms.map { it.copy(online = online[it.id] ?: 0) } }
}

@Composable
fun goldFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Gold,
    focusedBorderColor = Gold,
    unfocusedBorderColor = Gold.copy(alpha = 0.6f),
    focusedPlaceholderColor = Color(0xFF8A8A8A),
    unfocusedPlaceholderColor = Color(0xFF8A8A8A),
)

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) GoldBrush else Brush.horizontalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF3A3A3A))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.Black else Color(0xFF8A8A8A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.fillMaxWidth().height(46.dp).clip(shape).border(1.dp, Gold, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun DialogCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFF111111)).border(1.dp, Gold.copy(alpha = 0.5f), shape).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Daftar room berlabel OFFICIAL, diatur dari panel dev lewat Realtime Database. */
object OfficialRooms {
    var ids by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
        private set
    private var mulai = false
    fun start() {
        if (mulai) return
        mulai = true
        try {
            Rtdb.listen(Rtdb.ref("officialRooms")) { s ->
                ids = s.children.mapNotNull { c -> c.key?.takeIf { c.value == true } }.toSet()
            }
        } catch (e: Exception) { }
    }
}

@Composable
fun RoomCardView(room: RoomInfo, rank: Int = 0, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val bmp = remember(room.photoB64) { ProfileRepo.decodeB64(room.photoB64) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1E1E1E), Color(0xFF0E0E0E))))
                .border(1.dp, Brush.linearGradient(listOf(GoldDark, GoldLight, GoldDark)), shape),
        ) {
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("🎙️", fontSize = 40.sp, modifier = Modifier.align(Alignment.Center))
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("● ${room.online}", color = Color(0xFF4CAF50), fontSize = 12.sp)
            }
            OfficialRooms.start()
            if (OfficialRooms.ids.contains(room.id)) {
                // Pita diagonal OFFICIAL di sudut kiri atas foto room.
                Box(
                    Modifier.align(Alignment.TopStart).padding(top = 6.dp)
                        .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFFFB300))))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("OFFICIAL", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
             if (rank in 1..3) {
                val rb: Brush = when (rank) {
                    1 -> GoldBrush
                    2 -> Brush.horizontalGradient(listOf(Color(0xFFE0E0E0), Color(0xFF9E9E9E)))
                    else -> Brush.horizontalGradient(listOf(Color(0xFFE8A064), Color(0xFFA65A2A)))
                }
                Text(
                    "TOP$rank", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(rb)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                }
        }
        Spacer(Modifier.height(6.dp))
        Text(room.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(room.ownerName, color = Color(0xFF9A9A9A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CreateRoomDialog(ownerUid: String, ownerName: String, onDismiss: () -> Unit, onCreated: (RoomInfo) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val bmp = remember(photo) { ProfileRepo.decodeB64(photo) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val b64 = ProfileRepo.encodeImage(context, uri, 320, square = true, quality = 85)
            busy = false
            if (b64 != null) photo = b64
            else Toast.makeText(context, "Gagal membaca gambar", Toast.LENGTH_SHORT).show()
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        DialogCard {
            Text(tr("Buat room"), color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E1E))
                    .border(2.dp, Gold, CircleShape)
                    .clickable(enabled = !busy) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("📷", fontSize = 30.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(tr("Foto room"), color = Color(0xFF9A9A9A), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 30) name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(tr("Nama room")) },
                shape = RoundedCornerShape(12.dp),
                colors = goldFieldColors(),
            )
            Spacer(Modifier.height(18.dp))
            GoldButton(tr("Buat"), enabled = name.isNotBlank() && !busy) {
                scope.launch {
                    busy = true
                    try {
                        onCreated(RoomRepo.createRoom(ownerUid, ownerName, name.trim(), photo))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        Toast.makeText(context, "Gagal membuat room: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            }
        }
    }
}

@Composable
fun PasswordDialog(room: RoomInfo, onDismiss: () -> Unit, onOk: () -> Unit) {
    val context = LocalContext.current
    var pw by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Text(tr("Room terkunci"), color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(room.name, color = Color(0xFFBDBDBD), fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
                colors = goldFieldColors(),
            )
            Spacer(Modifier.height(16.dp))
            GoldButton(tr("Masuk"), enabled = pw.isNotEmpty()) {
                if (sha256(pw) == room.lockHash) onOk()
                else Toast.makeText(context, "Password salah", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
