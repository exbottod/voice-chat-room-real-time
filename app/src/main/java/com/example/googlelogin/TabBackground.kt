package com.example.googlelogin

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Background per tab home (Party, Moment, Family, Pesan, Saya).
 * Mode: "default" (kerajaan), "image" (file upload), "gradient" (2 warna).
 * Disimpan di RTDB node "tabBg" + cache lokal biar hemat kuota dan instan.
 */
data class TabBg(
    val mode: String = "default",
    val url: String = "",
    val c1: String = "",
    val c2: String = "",
)

val TAB_KEYS = listOf("party", "moment", "family", "pesan", "saya")

fun tabBgTitle(k: String): String = when (k) {
    "party" -> "Tab Party"
    "moment" -> "Tab Moment"
    "family" -> "Tab Family"
    "pesan" -> "Tab Pesan"
    else -> "Tab Saya"
}

/** Parse warna hex ("#RRGGBB" atau "#AARRGGBB"); salah format pakai fallback. */
fun parseHexColor(s: String, fallback: Color): Color = try {
    if (s.isBlank()) fallback
    else Color(android.graphics.Color.parseColor(s.trim()))
} catch (e: Exception) {
    fallback
}

object TabBgs {
    private const val TTL = 5 * 60_000L
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tab_bg", Context.MODE_PRIVATE)

    /** State reaktif: UI langsung ikut berubah saat config berganti. */
    var current by mutableStateOf<Map<String, TabBg>>(emptyMap())
        private set

    private var loadedAt = 0L

    /** Muat cache lokal dulu (instan, tanpa kuota). */
    fun loadLocal(ctx: Context) {
        if (current.isNotEmpty()) return
        val p = prefs(ctx)
        current = TAB_KEYS.associateWith { k ->
            val parts = p.getString(k, null)?.split('|')
            if (parts != null && parts.size >= 4) TabBg(parts[0], parts[1], parts[2], parts[3]) else TabBg()
        }
    }

    /** Ambil config terbaru dari Firestore (1 dokumen). Cache 5 menit; offline pakai cache. */
    suspend fun refresh(force: Boolean = false) {
        if (!force && current.isNotEmpty() && System.currentTimeMillis() - loadedAt < TTL) return
        try {
            val doc = FirebaseFirestore.getInstance().collection("vipConfig")
                .document("tabbg").get().await()
            val m = mutableMapOf<String, TabBg>()
            for (k in TAB_KEYS) {
                val d = doc.get(k) as? Map<*, *>
                m[k] = TabBg(
                    d?.get("mode") as? String ?: "default",
                    d?.get("url") as? String ?: "",
                    d?.get("c1") as? String ?: "",
                    d?.get("c2") as? String ?: "",
                )
            }
            current = m
            loadedAt = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline / limit: tetap pakai cache lokal.
            QuotaGuard.handle(e)
        }
    }

    /** Simpan lokal + state (instan) tanpa menunggu server. */
    fun saveLocal(ctx: Context, tab: String, bg: TabBg) {
        current = current.toMutableMap() + (tab to bg)
        loadedAt = System.currentTimeMillis()
        prefs(ctx).edit().putString(tab, "${bg.mode}|${bg.url}|${bg.c1}|${bg.c2}").apply()
    }
}

/** Simpan config satu tab ke Firestore (vipConfig/tabbg). Return null kalau sukses. */
suspend fun tabBgSaveRemote(tab: String, bg: TabBg): String? = try {
    FirebaseFirestore.getInstance().collection("vipConfig").document("tabbg").set(
        mapOf(
            tab to mapOf(
                "mode" to bg.mode,
                "url" to bg.url,
                "c1" to bg.c1,
                "c2" to bg.c2,
                "ts" to System.currentTimeMillis(),
            )
        ),
        SetOptions.merge(),
    ).await()
    null
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    e.message ?: "Gagal menyimpan ke server"
}

/**
 * Latar belakang full-screen untuk sebuah tab.
 * Panggil di belakang Scaffold MainScreen; tabKey = party/moment/family/pesan/saya.
 */
@Composable
fun TabBackground(tabKey: String) {
    val bg = TabBgs.current[tabKey] ?: TabBg()
    when {
        bg.mode == "image" && bg.url.isNotBlank() ->
            AsyncImage(
                bg.url,
                contentDescription = null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        bg.mode == "gradient" ->
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            parseHexColor(bg.c1, Color(0xFF1A0B2E)),
                            parseHexColor(bg.c2, Color(0xFF0B0B0B)),
                        )
                    )
                )
            )
        else -> RoyalBackground()
    }
}

/** Panel Developer: atur background tiap tab (upload gambar atau gradien warna). */
@Composable
fun TabBgPanel(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        TabBgs.loadLocal(context)
        TabBgs.refresh(force = true)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        GoldTopBar("Panel Developer: Background Tab", onBack)
        Spacer(Modifier.height(12.dp))
        Text(
            "Atur latar belakang tiap tab home. Pilih Default (kerajaan), Gambar (upload dari perangkat), " +
                "atau Gradien (2 warna hex, contoh #1A0B2E). Perubahan tampil di semua user.",
            color = Color(0xFFBDBDBD),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        TAB_KEYS.forEach { k ->
            TabBgEditor(k)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TabBgEditor(tab: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = TabBgs.current[tab] ?: TabBg()
    var mode by remember(tab, saved) { mutableStateOf(saved.mode) }
    var url by remember(tab, saved) { mutableStateOf(saved.url) }
    var c1 by remember(tab, saved) { mutableStateOf(if (saved.c1.isBlank()) "#1A0B2E" else saved.c1) }
    var c2 by remember(tab, saved) { mutableStateOf(if (saved.c2.isBlank()) "#0B0B0B" else saved.c2) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            msg = "Mengupload..."
            scope.launch {
                val (u, err) = GiftFx.uploadMedia(context, uri)
                if (u != null) {
                    url = u
                    msg = "Gambar terupload. Tekan SIMPAN."
                } else {
                    msg = err ?: "Upload gagal"
                }
                busy = false
            }
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(CardBlack).border(1.dp, Gold.copy(alpha = 0.35f), shape).padding(12.dp)
    ) {
        Text(tabBgTitle(tab), color = GoldLight, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))

        // Pilihan mode
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("default" to "Default", "image" to "Gambar", "gradient" to "Gradien").forEach { (m, label) ->
                val active = mode == m
                OutlinedButton(
                    onClick = { mode = m },
                    enabled = !busy,
                    colors = if (active) ButtonDefaults.outlinedButtonColors(
                        containerColor = Gold, contentColor = Color.Black
                    ) else ButtonDefaults.outlinedButtonColors(),
                ) { Text(label, fontSize = 12.sp) }
            }
        }

        if (mode == "image") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                Text(if (url.isBlank()) "Upload gambar (png/jpg/webp/gif)" else "Ganti gambar", fontSize = 12.sp)
            }
            if (url.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    url, contentDescription = null,
                    Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        if (mode == "gradient") {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    c1, { c1 = it },
                    label = { Text("Warna atas", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    c2, { c2 = it },
                    label = { Text("Warna bawah", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Preview gradien
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(
                    Brush.verticalGradient(listOf(parseHexColor(c1, Color(0xFF1A0B2E)), parseHexColor(c2, Color(0xFF0B0B0B))))
                )
            )
        }

        msg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = GoldLight, fontSize = 12.sp)
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                val bg = TabBg(mode = mode, url = url, c1 = c1, c2 = c2)
                TabBgs.saveLocal(context, tab, bg)
                busy = true
                msg = "Menyimpan ke server..."
                scope.launch {
                    val err = tabBgSaveRemote(tab, bg)
                    msg = err ?: "Tersimpan. Semua user akan melihat perubahan."
                    busy = false
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SIMPAN") }
    }
}
