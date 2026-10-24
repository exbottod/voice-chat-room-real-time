package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateColor
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch

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

object SvipRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db().collection("wallets").document(uid)

    /** Simpan toggle privilege (wallet svip.<key>), lalu sinkronkan ke profil publik. */
    suspend fun set(uid: String, key: String, on: Boolean): String? = try {
        ref(uid).set(hashMapOf<String, Any?>("svip" to hashMapOf(key to on)), SetOptions.merge()).await()
        PublicSync.push(uid)
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Gagal"
    }

    /** Simpan iklan pribadi (Personal ADs). */
    suspend fun setAd(uid: String, ad: String): String? = try {
        ref(uid).set(hashMapOf<String, Any?>("svipAd" to ad.trim().take(120)), SetOptions.merge()).await()
        PublicSync.push(uid)
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Gagal"
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
    var vip by remember { mutableStateOf(0) }
    var vipUntil by remember { mutableStateOf(0L) }
    var toggles by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var myAd by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var editAd by remember { mutableStateOf(false) }

    LaunchedEffect(uid) {
        try {
            val doc = FirebaseFirestore.getInstance().collection("wallets").document(uid).get().await()
            vip = (doc.getLong("vip") ?: 0L).toInt()
            vipUntil = doc.getLong("vipUntil") ?: 0L
            val m = doc.get("svip") as? Map<*, *>
            toggles = m?.mapNotNull { (k, v) -> if (k is String && v == true) k to true else null }?.toMap() ?: emptyMap()
            myAd = doc.getString("svipAd") ?: ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
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
                                Text("›", color = Color(0xFF9E9E9E), fontSize = 26.sp,
                                    modifier = Modifier.clickable(enabled = unlocked) { editAd = true }
                                        .padding(horizontal = 10.dp))
                            } else {
                                ToggleGold(on = on, enabled = unlocked, onChange = { v ->
                                    toggles = toggles + (priv.key to v)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { }
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

/** Foto profil misterius: bulat gelap dengan kilau emas berjalan. */
@Composable
fun MysteryAvatar(size: Dp) {
    val t = rememberInfiniteTransition(label = "ma")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "mx")
    val glow by t.animateColor(Color(0xFF7A5A00), Color(0xFFFFE082), infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "mg")
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF141210))
            .border(2.dp, glow, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    (x * 0.6f) to Color(0x66FFD54F),
                    (x * 0.6f + 0.05f) to Color(0xCCFFF3C4),
                    (x * 0.6f + 0.1f) to Color(0x66FFD54F),
                    (x * 0.6f + 0.15f) to Color.Transparent,
                    1f to Color.Transparent,
                )
            )
        )
        Text("🕵️", fontSize = (size.value * 0.4f).sp)
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
