package com.example.googlelogin

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

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
fun FamilyLogo(b64: String?, size: Dp, modifier: Modifier = Modifier) {
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

/* Tab Family: belum punya -> buat / cari. Sudah punya -> halaman */
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
        Text("Buat family sendiri atau cari family untuk bergabung", color = Color(0xFF8A8A8A), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
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

/* Pengaturan family (buat baru / ubah) */
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

/* Halaman family */
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
                    Text("✉", fontSize = 26.sp, modifier = Modifier.clickable { showInbox = true }.padding(8.dp))
                    if (isAdmin && requests.isNotEmpty()) Text("${requests.size}", color = Color.White, fontSize = 11.sp, modifier = Modifier.clip(CircleShape).background(Color.Red).padding(horizontal = 6.dp, vertical = 2.dp))
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
            }
            // Kartu EXP
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).offset(y = (-50).dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0E5149), Color(0xFF0B3430))))
                    .border(2.dp, Color(0xFF2FB9A8), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🛡", fontSize = 54.sp, modifier = Modifier.clickable { showClass = true })
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
                            .background(
                                if (tab == i) Brush.verticalGradient(listOf(Color(0xFFFFE680), Color(0xFFFFC107)))
                                else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                            )
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
        YellowButton("Undang Anggota", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)) { showInvite = true }
    }

    // ---------- Dialog & overlay ----------
    if (showSettings) FamilySettingsScreen(existing = f, myUid = myUid, myName = myName, myAvatar = myAvatar, onBack = { showSettings = false }, onDone = { showSettings = false })

    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("Hapus family?") },
        text = { Text("Family \"${f.name}\" dan semua datanya akan dihapus permanen, seluruh anggota akan dikeluarkan. Tindakan ini tidak bisa dibatalkan.") },
        confirmButton = {
            TextButton(onClick = {
                showDelete = false
                scope.launch { val e = FamilyRepo.delete(f.id); toast(e ?: "Family dihapus") }
            }) { Text("Hapus", color = Color(0xFFD9534F)) }
        },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Batal") } },
    )

    if (showLeave) AlertDialog(
        onDismissRequest = { showLeave = false },
        title = { Text("Keluar dari family?") },
        text = { Text("Kontribusimu di family ini akan hilang.") },
        confirmButton = {
            TextButton(onClick = {
                showLeave = false
                scope.launch { val e = FamilyRepo.leave(f.id, myUid); toast(e ?: "Kamu keluar dari family") }
            }) { Text("Keluar", color = Color(0xFFD9534F)) }
        },
        dismissButton = { TextButton(onClick = { showLeave = false }) { Text("Batal") } },
    )

    if (editAnn) TextEditDialog("Pengumuman family", f.announcement, 200, onDismiss = { editAnn = false }) { t ->
        scope.launch { toast(FamilyRepo.setAnnouncement(f.id, t) ?: "Pengumuman diperbarui") }
    }
    if (editSlogan) TextEditDialog("Sapaan family", f.slogan.ifBlank { "welcome to family ${f.name}" }, 60, onDismiss = { editSlogan = false }) { t ->
        scope.launch { toast(FamilyRepo.setSlogan(f.id, t) ?: "Sapaan diperbarui") }
    }
    if (showPost) TextEditDialog(if (tab == 2) "Tulis momen" else "Buat kegiatan", "", 300, onDismiss = { showPost = false }) { t ->
        scope.launch { toast(FamilyRepo.postEvent(f.id, myName, if (tab == 2) "momen" else "kegiatan", t) ?: "Terkirim") }
    }

    if (showInvite) AlertDialog(
        onDismissRequest = { showInvite = false },
        title = { Text("Undang Anggota") },
        text = { Text("Bagikan ID family ${f.numId} (${f.name}). Teman bisa membuka tab Family lalu Cari / Gabung Family.") },
        confirmButton = {
            TextButton(onClick = {
                showInvite = false
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Gabung family \"${f.name}\" di aplikasi kami! Cari ID family: ${f.numId}")
                }
                context.startActivity(Intent.createChooser(i, "Undang lewat"))
            }) { Text("Bagikan") }
        },
        dismissButton = { TextButton(onClick = { showInvite = false }) { Text("Tutup") } },
    )

    if (showClass) AlertDialog(
        onDismissRequest = { showClass = false },
        title = { Text("Kelas Family") },
        text = {
            Column {
                FAMILY_CLASSES.forEach {
                    Text("${it.name} ${it.roman}: butuh ${fmtNum(it.need)} EXP${if (it == cls) " ◀ sekarang" else ""}", modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { showClass = false }) { Text("Tutup") } },
    )

    if (showRank) FamilyRankScreen(myFamilyId = f.id, onBack = { showRank = false })
    if (showMembers) MemberListScreen(members, f.ownerUid, myUid, isAdmin, isOwner, onBack = { showMembers = false }) { memberAction = it }
    if (showInbox) RequestsScreen(
        requests, isAdmin, onBack = { showInbox = false },
        onApprove = { r -> scope.launch { toast(FamilyRepo.approve(f.id, r) ?: "${r.name} diterima") } },
        onReject = { r -> scope.launch { FamilyRepo.reject(f.id, r.uid); toast("Permintaan ditolak") } },
    )

    memberAction?.let { m ->
        AlertDialog(
            onDismissRequest = { memberAction = null },
            title = { Text(m.name) },
            text = {
                Column {
                    if (isOwner && m.role != "admin") Text(
                        "Jadikan admin",
                        Modifier.fillMaxWidth().clickable {
                            memberAction = null
                            scope.launch { toast(FamilyRepo.setRole(f.id, m.uid, "admin") ?: "${m.name} jadi admin") }
                        }.padding(vertical = 10.dp),
                    )
                    if (isOwner && m.role == "admin") Text(
                        "Cabut admin",
                        Modifier.fillMaxWidth().clickable {
                            memberAction = null
                            scope.launch { toast(FamilyRepo.setRole(f.id, m.uid, "member") ?: "Admin ${m.name} dicabut") }
                        }.padding(vertical = 10.dp),
                    )
                    Text(
                        "Keluarkan dari family",
                        Modifier.fillMaxWidth().clickable {
                            memberAction = null
                            scope.launch { toast(FamilyRepo.kick(f.id, m.uid) ?: "${m.name} dikeluarkan") }
                        }.padding(vertical = 10.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { memberAction = null }) { Text("Tutup") } },
        )
    }
}

@Composable
private fun TextEditDialog(title: String, initial: String, max: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(t, { if (it.length <= max) t = it }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${t.length}/$max") }) },
        confirmButton = { TextButton(onClick = { if (t.isNotBlank()) { onSave(t.trim()); onDismiss() } }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun HomeTab(
    f: Family, cls: FamilyClass, members: List<FamilyMember>, isAdmin: Boolean,
    onEditAnn: () -> Unit, onRank: () -> Unit, onMembers: () -> Unit, onClass: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF8A4B0F), Color(0xFF2A1A0A), Color(0xFF123B5C)))).clickable(onClick = onRank),
            contentAlignment = Alignment.Center,
        ) { Text("Liga Peringkat Fam", color = Color(0xFFFFD54F), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }

        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pengumuman family", color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (isAdmin) Text("✎", color = Color(0xFFB8D8D3), fontSize = 22.sp, modifier = Modifier.clickable(onClick = onEditAnn).padding(4.dp))
        }
        Text(
            f.announcement.ifBlank { "welcome ${f.name}" },
            color = Color.White, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1D2B28)).padding(14.dp),
        )

        Text("Kelas Fam", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF5A2A1A), Color(0xFF2E1710))))
                .border(2.dp, Color(0xFF8A5A2E), RoundedCornerShape(10.dp)).clickable(onClick = onClass).padding(12.dp),
        ) {
            Text("Peringkat saat ini: ${cls.name} ${cls.roman}", color = Color(0xFFFFB74D), fontSize = 15.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("🛡", fontSize = 56.sp)
                PerkBox("💵", cls.perk1); PerkBox("🏅", cls.perk2); PerkBox("⭕", cls.perk3)
            }
            val need = cls.need / 1000
            Text("Diperlukan untuk naik ke peringkat berikutnya: ${f.exp}/$need", color = Color.White, fontSize = 13.sp)
        }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp).clickable(onClick = onMembers), verticalAlignment = Alignment.CenterVertically) {
            Text("Member TOP", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("›", color = Color(0xFFB8D8D3), fontSize = 28.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = listOf(Color(0xFF7B2FBE), Color(0xFFC62828), Color(0xFF1565C0))
            val top = members.take(3)
            for (i in 0 until 3) {
                val m = top.getOrNull(i)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(colors[i].copy(alpha = 0.85f)).padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bmp = remember(m?.avatarB64) { ProfileRepo.decodeB64(m?.avatarB64) }
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text((m?.name ?: "?").take(1).uppercase(), color = GoldLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(m?.name ?: "-", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
                    Text(fmtNum(m?.contribution ?: 0L), color = Color(0xFFFFE082), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun PerkBox(emoji: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 28.sp) }
        Text(label, color = Color(0xFFD7B9A5), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TasksTab(done: Set<String>, onClaim: (FamilyTask) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(FAMILY_TASKS) { t ->
            val isDone = t.id in done
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1D2B28)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontSize = 16.sp)
                    Text(t.desc, color = Color(0xFF9AB5B0), fontSize = 12.sp)
                    Text("+${t.reward} EXP", color = Color(0xFFFFD54F), fontSize = 13.sp)
                }
                Text(
                    if (isDone) "Selesai" else "Klaim",
                    color = if (isDone) Color(0xFF9A9A9A) else Color(0xFF3D2A00),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (isDone) Brush.verticalGradient(listOf(Color(0xFF444444), Color(0xFF444444))) else YellowBtn)
                        .clickable(enabled = !isDone) { onClaim(t) }.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun MomentsTab(list: List<FamilyEvent>, onPost: () -> Unit, onDelete: (String) -> Unit) {
    PostList(list, "Belum ada momen family", "Tulis momen", true, onPost, onDelete)
}

@Composable
private fun ActivitiesTab(list: List<FamilyEvent>, isAdmin: Boolean, onPost: () -> Unit, onDelete: (String) -> Unit) {
    PostList(list, "Belum ada kegiatan family", "Buat kegiatan", isAdmin, onPost, onDelete)
}

@Composable
private fun PostList(list: List<FamilyEvent>, empty: String, btn: String, canPost: Boolean, onPost: () -> Unit, onDelete: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canPost) item {
            OutlinedButton(onClick = onPost, border = BorderStroke(1.dp, Gold), colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight), modifier = Modifier.fillMaxWidth()) { Text(btn) }
        }
        if (list.isEmpty()) item { Text(empty, color = Color(0xFF8A8A8A), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) }
        items(list) { e ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1D2B28)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.byName, color = GoldLight, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(timeAgo(e.createdAt), color = Color(0xFF9AB5B0), fontSize = 12.sp)
                    if (canPost) Text(" 🗑 ", modifier = Modifier.clickable { onDelete(e.id) })
                }
                Text(e.text, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

/* Daftar anggota, permintaan, peringkat, cari */
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    OverlayBox {
        Column(Modifier.fillMaxSize()) {
            GoldTopBar(title, onBack)
            Box(Modifier.weight(1f).navigationBarsPadding()) { content() }
        }
    }
}

@Composable
private fun MemberAvatar(b64: String?, name: String, size: Dp) {
    val bmp = remember(b64) { ProfileRepo.decodeB64(b64) }
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = GoldLight, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MemberListScreen(
    members: List<FamilyMember>, ownerUid: String, myUid: String, isAdmin: Boolean, isOwner: Boolean,
    onBack: () -> Unit, onManage: (FamilyMember) -> Unit,
) {
    SubPage("Anggota (${members.size})", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(members) { m ->
                val canManage = m.uid != myUid && m.uid != ownerUid && (isOwner || (isAdmin && m.role == "member"))
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatar(m.avatarB64, m.name, 46.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Color.White, fontSize = 16.sp)
                        Text(
                            when { m.uid == ownerUid -> "Pemilik"; m.role == "admin" -> "Admin"; else -> "Anggota" } + " · Kontribusi ${fmtNum(m.contribution)}",
                            color = Color(0xFF9AB5B0), fontSize = 12.sp,
                        )
                    }
                    if (canManage) Text("Kelola", color = GoldLight, modifier = Modifier.clickable { onManage(m) }.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun RequestsScreen(list: List<FamilyRequest>, isAdmin: Boolean, onBack: () -> Unit, onApprove: (FamilyRequest) -> Unit, onReject: (FamilyRequest) -> Unit) {
    SubPage("Permintaan bergabung", onBack) {
        if (!isAdmin || list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (isAdmin) "Tidak ada permintaan" else "Hanya admin yang dapat melihat permintaan", color = Color(0xFF8A8A8A))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(list) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatar(r.avatarB64, r.name, 46.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = Color.White, fontSize = 16.sp)
                        Text(timeAgo(r.createdAt), color = Color(0xFF9AB5B0), fontSize = 12.sp)
                    }
                    Text("Tolak", color = Color(0xFFFF6B6B), modifier = Modifier.clickable { onReject(r) }.padding(8.dp))
                    Text("Terima", color = Color(0xFF3D2A00), modifier = Modifier.clip(RoundedCornerShape(50)).background(YellowBtn).clickable { onApprove(r) }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun FamilyRankScreen(myFamilyId: String, onBack: () -> Unit) {
    var list by remember { mutableStateOf<List<Family>?>(null) }
    LaunchedEffect(Unit) { list = try { FamilyRepo.topFamilies() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() } }
    SubPage("Liga Peringkat Fam", onBack) {
        val l = list
        if (l == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Memuat...", color = Color(0xFF8A8A8A)) }
        else if (l.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Belum ada data peringkat", color = Color(0xFF8A8A8A)) }
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(l.size) { i ->
                val f = l[i]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (f.id == myFamilyId) Color(0xFF2A3A20) else CardBlack).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}" }, color = Color.White, fontSize = 20.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                    FamilyLogo(f.logoB64, 46.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("ID:${f.numId} · ${f.memberCount} anggota", color = Color(0xFF9AB5B0), fontSize = 12.sp)
                    }
                    Text("${fmtNum(f.exp)} EXP", color = Color(0xFFFFD54F), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun FamilySearchScreen(myUid: String, myName: String, myAvatar: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Family>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    fun doSearch() {
        busy = true
        scope.launch {
            results = try { FamilyRepo.search(q) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            busy = false
        }
    }

    LaunchedEffect(Unit) { results = try { FamilyRepo.topFamilies() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() } }

    SubPage("Cari Family", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    q, { q = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Nama atau ID family") }, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Gold,
                        focusedBorderColor = Gold, unfocusedBorderColor = Color(0xFF2D2822),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                YellowButton(if (busy) "..." else "Cari", enabled = !busy) { doSearch() }
            }
            Spacer(Modifier.height(8.dp))
            val l = results
            if (l != null && l.isEmpty()) Text("Family tidak ditemukan", color = Color(0xFF8A8A8A), modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(l ?: emptyList()) { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(CardBlack).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FamilyLogo(f.logoB64, 50.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("ID:${f.numId} · ${f.memberCount} anggota · 🛡 ${f.label}", color = Color(0xFF9AB5B0), fontSize = 12.sp)
                        }
                        Text(
                            if (f.needApproval) "Ajukan" else "Gabung", color = Color(0xFF3D2A00),
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(YellowBtn).clickable {
                                scope.launch {
                                    val err = FamilyRepo.join(f.id, myUid, myName, myAvatar)
                                    when (err) {
                                        null -> { toast("Berhasil bergabung"); onBack() }
                                        "REQUEST_SENT" -> toast("Permintaan dikirim, tunggu persetujuan admin")
                                        else -> toast(err.orEmpty())
                                    }
                                }
                            }.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
