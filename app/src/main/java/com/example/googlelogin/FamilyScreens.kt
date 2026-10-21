package com.example.googlelogin

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val TealDark = Color(0xFF0B2E2B)
private val Teal = Color(0xFF14625A)
private val TealLight = Color(0xFF7FE3D6)
private val YellowBtn = Brush.verticalGradient(listOf(Color(0xFFFFE680), Color(0xFFFFC107), Color(0xFFE0A100)))

@Composable
private fun YellowButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) YellowBtn else Brush.verticalGradient(listOf(Color(0xFF777777), Color(0xFF555555))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 36.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color(0xFF4A2F00), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun FamilyLogo(b64: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val bmp = remember(b64) { ProfileRepo.decodeB64(b64) }
    val shape = RoundedCornerShape(size / 6)
    if (bmp != null) {
        Image(bmp, null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size).clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF1FA89A), Color(0xFF0E7C86)))), contentAlignment = Alignment.Center) {
            Text("🏰", fontSize = (size.value * 0.5f).sp)
        }
    }
}

/* ============================================================ */
/* Tab Family: belum punya -> buat / cari. Sudah punya -> halaman */
/* ============================================================ */

@Composable
fun FamilyTabScreen(myUid: String, myName: String, myAvatar: String?, myId: String) {
    var fam by remember { mutableStateOf<Family?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    DisposableEffect(myUid) {
        val r = if (myUid.isNotEmpty()) FamilyRepo.observeMy(myUid) { f, l -> fam = f; loaded = l } else null
        onDispose { r?.remove() }
    }

    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Memuat...", color = Color(0xFF8A8A8A)) }
        fam != null -> FamilyHomeScreen(fam!!, myUid, myName, myAvatar)
        else -> NoFamilyPage(onCreate = { showCreate = true }, onSearch = { showSearch = true })
    }

    if (showCreate) FamilySettingsScreen(
        existing = null, myUid = myUid, myName = myName, myAvatar = myAvatar,
        onBack = { showCreate = false }, onDone = { showCreate = false },
    )
    if (showSearch) FamilySearchScreen(myUid = myUid, myName = myName, myAvatar = myAvatar, onBack = { showSearch = false })
}

@Composable
private fun NoFamilyPage(onCreate: () -> Unit, onSearch: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Family", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp).align(Alignment.Start))
        Spacer(Modifier.height(40.dp))
        Text("🏰", fontSize = 72.sp)
        Spacer(Modifier.height(12.dp))
        Text("Kamu belum bergabung dengan family", color = Color.White, fontSize = 16.sp)
        Text("Buat family sendiri atau cari family untuk bergabung", color = Color(0xFF8A8A8A), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
        Spacer(Modifier.height(24.dp))
        YellowButton("Buat Family", onClick = onCreate)
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onSearch,
            border = BorderStroke(1.dp, Gold),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight),
        ) { Text("Cari / Gabung Family", modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
    }
}

/* ============================================================ */
/* Foto 1: Pengaturan family (buat baru / ubah)                  */
/* ============================================================ */

@Composable
fun FamilySettingsScreen(
    existing: Family?, myUid: String, myName: String, myAvatar: String?,
    onBack: () -> Unit, onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var logo by remember { mutableStateOf(existing?.logoB64) }
    var needApproval by remember { mutableStateOf(existing?.needApproval ?: false) }
    var busy by remember { mutableStateOf(false) }
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

  BackHandler(onBack = onBack)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val b64 = ProfileRepo.encodeImage(context, uri, 400, square = true, quality = 85)
            busy = false
            if (b64 != null) logo = b64 else toast("Gagal membaca gambar")
        }
    }

    Box(Modifier.fillMaxSize().blockTouches().background(Color(0xFF14100C))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                Text("‹", color = Color.White, fontSize = 36.sp, modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 16.dp))
                Text(if (existing == null) "Buat family" else "Pengaturan family", color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp))
                FamilyLogo(logo, 150.dp, Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                Spacer(Modifier.height(8.dp))
                Text("Unggah Logo family", color = Color.White, modifier = Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                Spacer(Modifier.height(16.dp))

                Column(Modifier.fillMaxWidth()) {
                    Text("Nama family", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    FamField(name, 20) { name = it }
                    Text("*Hanya dapat diubah 1 kali per bulan, harap dipikirkan baik-baik.", color = Color(0xFF9A9A9A), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Label family", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    FamField(label, 10) { label = it }
                    Text("*Hanya dapat diubah 1 kali per bulan, harap dipikirkan baik-baik.", color = Color(0xFF9A9A9A), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Pratinjau", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    FamilyPreviewCard(name.ifBlank { "Nama family" }, label.ifBlank { "Label" }, logo)
                    Spacer(Modifier.height(16.dp))
                    Text("Menerapkan untuk mengatur", color = Color.White, fontSize = 18.sp)
                    Row(Modifier.fillMaxWidth().clickable { needApproval = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(needApproval, { needApproval = true }, colors = RadioButtonDefaults.colors(selectedColor = TealLight, unselectedColor = Color.White))
                        Text("Persetujuan diperlukan untuk bergabung dengan family", color = Color.White, fontSize = 16.sp)
                    }
                    Row(Modifier.fillMaxWidth().clickable { needApproval = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!needApproval, { needApproval = false }, colors = RadioButtonDefaults.colors(selectedColor = TealLight, unselectedColor = Color.White))
                        Text("Tidak diperlukan persetujuan, bergabung dengan family secara langsung", color = Color.White, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                YellowButton(if (busy) "Menyimpan..." else "Konfirmasi", enabled = !busy) {
                    val n = name.trim()
                    val l = label.trim()
                    if (n.length < 2) return@YellowButton toast("Nama family minimal 2 karakter")
                    if (l.length < 2) return@YellowButton toast("Label family minimal 2 karakter")
                    busy = true
                    scope.launch {
                        val err = if (existing == null) FamilyRepo.create(myUid, myName, myAvatar, n, l, logo, needApproval)
                        else FamilyRepo.saveSettings(existing, n, l, logo, needApproval)
                        busy = false
                        if (err != null) toast(err) else {
                            toast(if (existing == null) "Family berhasil dibuat" else "Pengaturan disimpan")
                            onDone()
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun FamField(value: String, max: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= max) onChange(it) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        trailingIcon = { Text("${value.length}/$max", color = Color(0xFF9A9A9A), modifier = Modifier.padding(end = 12.dp)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = TealLight,
            focusedBorderColor = TealLight, unfocusedBorderColor = Color(0xFF2D2822),
            focusedContainerColor = Color(0xFF2A2520), unfocusedContainerColor = Color(0xFF2A2520),
        ),
    )
}

@Composable
private fun FamilyPreviewCard(name: String, label: String, logo: String?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF0B4D45), Color(0xFF0E2E2B))))
            .border(2.dp, Gold, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FamilyLogo(logo, 64.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("🛡 $label", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Teal).padding(horizontal = 10.dp, vertical = 2.dp))
        }
    }
}

/* ============================================================ */
/* Foto 2: Halaman family                                        */
/* ============================================================ */

@Composable
fun FamilyHomeScreen(f: Family, myUid: String, myName: String, myAvatar: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var members by remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    var requests by remember { mutableStateOf<List<FamilyRequest>>(emptyList()) }
    var events by remember { mutableStateOf<List<FamilyEvent>>(emptyList()) }
    var done by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showRank by remember { mutableStateOf(false) }
    var showInbox by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var editAnn by remember { mutableStateOf(false) }
    var editSlogan by remember { mutableStateOf(false) }
    var showPost by remember { mutableStateOf(false) }
    var showClass by remember { mutableStateOf(false) }
    var memberAction by remember { mutableStateOf<FamilyMember?>(null) }
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    val me = members.firstOrNull { it.uid == myUid }
    val isOwner = f.ownerUid == myUid
    val isAdmin = isOwner || me?.role == "admin"
    val level = familyLevel(f.exp)
    val nextNeed = familyLevelNeed(level + 1)
    val cls = familyClassOf(f.exp)

    DisposableEffect(f.id) {
        val a = FamilyRepo.observeMembers(f.id) { members = it }
        val b = FamilyRepo.observeEvents(f.id) { events = it }
        val c = FamilyRepo.observeDoneTasks(f.id, myUid) { done = it }
        val d = if (isAdmin) FamilyRepo.observeRequests(f.id) { requests = it } else null
        onDispose { a.remove(); b.remove(); c.remove(); d?.remove() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07201D))) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Box(Modifier.fillMaxWidth().height(330.dp).background(Brush.verticalGradient(listOf(Color(0xFF1B5E55), Color(0xFF0E3A35), Color(0xFF07201D))))) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text("✉️", fontSize = 26.sp, modifier = Modifier.clickable { showInbox = true }.padding(8.dp))
                    if (isAdmin && requests.isNotEmpty()) Text("${requests.size}", color = Color.White, fontSize = 11.sp, modifier = Modifier.clip(CircleShape).background(Color.Red).padding(horizontal = 5.dp))
                    Box {
                        Text("⋮", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { menu = true }.padding(horizontal = 12.dp, vertical = 4.dp))
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF1E1A16)) {
                            if (isAdmin) DropdownMenuItem(text = { Text("Atur family", color = Color.White) }, onClick = { menu = false; showSettings = true })
                            DropdownMenuItem(text = { Text("Daftar anggota", color = Color.White) }, onClick = { menu = false; showMembers = true })
                            DropdownMenuItem(text = { Text("Salin ID family", color = Color.White) }, onClick = {
                                menu = false
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("family", f.numId))
                                toast("ID family disalin")
                            })
                            if (isOwner) DropdownMenuItem(text = { Text("Hapus family", color = Color(0xFFFF6B6B)) }, onClick = { menu = false; showDelete = true })
                            else DropdownMenuItem(text = { Text("Keluar family", color = Color(0xFFFF6B6B)) }, onClick = { menu = false; showLeave = true })
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FamilyLogo(f.logoB64, 120.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(f.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    Text("ID:${f.numId}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).clickable(enabled = isAdmin) { editSlogan = true }) {
                        Text(f.slogan.ifBlank { "welcome to family ${f.name}" }, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isAdmin) Text(" ✎", color = Color.White, fontSize = 16.sp)
                    }
                }
            
            // Kartu EXP
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).offset(y = (-50).dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0E5149), Color(0xFF0B3430))))
                    .border(2.dp, Color(0xFF2FB9A8), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🛡️", fontSize = 54.sp, modifier = Modifier.clickable { showClass = true })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total nilai pengalaman family", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("Peringkat bulan ini ›", color = Color(0xFFB8D8D3), fontSize = 13.sp, modifier = Modifier.clickable { showRank = true })
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pct = (f.exp.toFloat() / nextNeed.toFloat()).coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF2B2B2B))) {
                            Box(Modifier.fillMaxWidth(pct.coerceAtLeast(0.02f)).height(18.dp).background(Brush.horizontalGradient(listOf(Color(0xFFE0A100), Color(0xFFFFD54F)))))
                            Text("${f.exp}/$nextNeed", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center), maxLines = 1)
                        }
                        Text(" Lv.$level", color = Color(0xFFB8D8D3), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Nilai kontribusi saya:${me?.contribution ?: 0}", color = Color.White, fontSize = 15.sp)
                }
            }

            // Tabs
            val tabs = listOf("BERANDA", "TUGAS", "MOMEN", "KEGIATAN FAM")
            Row(Modifier.fillMaxWidth().offset(y = (-36).dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, t ->
                    Text(
                        t,
                        color = if (tab == i) Color(0xFF3D2A00) else Color(0xFFB8D8D3),
                        fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (tab == i) Brush.verticalGradient(listOf(Color(0xFFFFE680), Color(0xFFFFC107))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                            .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            Box(Modifier.weight(1f).offset(y = (-30).dp)) {
                when (tab) {
                    0 -> HomeTab(f, cls, members, isAdmin, onEditAnn = { editAnn = true }, onRank = { showRank = true }, onMembers = { showMembers = true }, onClass = { showClass = true })
                    1 -> TasksTab(done) { t ->
                        scope.launch {
                            val err = FamilyRepo.completeTask(f.id, myUid, t)
                            toast(err ?: "+${t.reward} EXP family")
                        }
                    }
                    2 -> MomentsTab(events.filter { it.title == "momen" }, onPost = { showPost = true }) { id -> scope.launch { FamilyRepo.deleteEvent(f.id, id) } }
                    else -> ActivitiesTab(events.filter { it.title != "momen" }, isAdmin, onPost = { showPost = true }) { id -> scope.launch { FamilyRepo.deleteEvent(f.id, id) } }
                }
            }
        }

        // Tombol Undang Anggota
        YellowButton("Undang Anggota", Modifier.align(Alignment.BottomC... (truncated)  
        
