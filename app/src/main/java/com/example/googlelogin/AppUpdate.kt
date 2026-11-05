package com.example.googlelogin

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pembaruan aplikasi. Setiap build menulis nomor versinya ke docs/build-info.json
 * (disajikan GitHub Pages). Saat aplikasi dibuka, nomor itu dibandingkan dengan
 * versi terpasang; kalau lebih baru, muncul dialog "Perbarui sekarang / Nanti".
 * Unduhan diambil dari link rilis terbaru, sesuai arsitektur HP.
 */
object AppUpdate {
    private const val INFO_URL = "https://exbottod.github.io/voice-chat-room-real-time/build-info.json"
    private const val REL = "https://github.com/exbottod/voice-chat-room-real-time/releases/download/terbaru/"
    private const val PATCH_URL = "https://exbottod.github.io/voice-chat-room-real-time/patch-info.json"

    class Info(val version: String, val notes: String, val url: String, val patch: Patch? = null)

    /**
     * File selisih untuk update kecil. Hanya dipakai kalau versi yang
     * terpasang sama persis dengan versi dasar patch; kalau tidak cocok,
     * aplikasi otomatis unduh APK penuh.
     */
    class Patch(val url: String, val ukuran: Long, val sha256: String)

    /** "1.0.683" -> 683. Nomor terakhir dipakai sebagai urutan build. */
    fun build(v: String): Int = v.trim().substringAfterLast('.').toIntOrNull() ?: 0

    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** Sidik jari file, untuk memastikan hasil gabungan patch benar. */
    fun sha256(f: File): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) { "" }

    private fun kunci(): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        return when {
            abis.any { it == "arm64-v8a" } -> "arm64"
            abis.any { it == "armeabi-v7a" } -> "armv7"
            else -> "universal"
        }
    }

    /** Info patch untuk versi ini, atau null kalau tidak tersedia / tidak cocok. */
    private fun patchInfo(versiBaru: String, versiTerpasang: String): Patch? = try {
        val c = URL(PATCH_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Cache-Control", "no-cache")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val dari = j.optString("versi_lama")
        val ke = j.optString("versi_baru")
        val entri = j.optJSONObject(kunci())
        if (dari == versiTerpasang && ke == versiBaru && entri != null) {
            val nama = entri.optString("file")
            if (nama.isNotBlank()) {
                Patch(REL + nama, entri.optLong("ukuran"), entri.optString("sha256"))
            } else null
        } else null
    } catch (e: Exception) { null }

    /** File APK yang cocok dengan HP ini (arm64, armv7, atau universal). */
    private fun apkName(): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        return when {
            abis.any { it == "arm64-v8a" } -> "REZO-CHAT-VOICE-arm64.apk"
            abis.any { it == "armeabi-v7a" } -> "REZO-CHAT-VOICE-armv7.apk"
            else -> "REZO-CHAT-VOICE-universal.apk"
        }
    }

    suspend fun check(ctx: Context): Info? = withContext(Dispatchers.IO) {
        try {
            val c = URL(INFO_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Cache-Control", "no-cache")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val latest = j.optString("versi")
            if (latest.isBlank() || j.optString("status") != "ok") return@withContext null
            if (build(latest) <= build(installedVersion(ctx))) return@withContext null
            // Catatan rilis: ambil dari daftar "fitur" bila ada.
            val notes = try {
                val arr = j.optJSONArray("fitur")
                val out = ArrayList<String>()
                if (arr != null) for (i in 0 until minOf(arr.length(), 3)) {
                    out.add(arr.getJSONObject(i).optString("nama"))
                }
                out.filter { it.isNotBlank() }.joinToString(" \u2022 ")
            } catch (e: Exception) { "" }
            // Catatan rilis bisa ditulis sendiri dari panel (rocketCfg/updateNote)
            // supaya tidak perlu build ulang hanya untuk mengubah kalimatnya.
            val panelNote = try {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("rocketCfg/updateNote").get().await()
                        .getValue(String::class.java)
                }?.trim().orEmpty()
            } catch (e: Exception) { "" }
            val akhir = panelNote.ifBlank { notes.ifBlank { "Perbaikan dan peningkatan untuk REZO." } }
            val terpasang = installedVersion(ctx)
            Info(latest, akhir, REL + apkName(), patchInfo(latest, terpasang))
        } catch (e: Exception) { null }
    }

    /**
     * Unduh file ke folder cache update. [minimal] mencegah file rusak/terpotong
     * dianggap berhasil; untuk patch batasnya jauh lebih kecil dari APK.
     */
    private suspend fun unduh(
        ctx: Context,
        url: String,
        nama: String,
        minimal: Long,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
                val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                val out = File(dir, nama)
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                c.connect()
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = (read * 100 / total).toInt()
                                if (p != last) { last = p; onProgress(p) }
                            }
                        }
                    }
                }
                if (out.length() < minimal) null else out
            } catch (e: Exception) { null }
        }

    /** Unduh APK penuh; [onProgress] 0..100. Mengembalikan file, atau null kalau gagal. */
    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? =
        unduh(ctx, url, "REZO-update.apk", 1_000_000L, onProgress)

    /**
     * Unduh patch lalu gabungkan dengan APK yang terpasang menjadi APK baru.
     * Hasilnya diperiksa sidik jarinya; kalau tidak cocok, file dibuang dan
     * pemanggil memakai unduhan penuh.
     */
    suspend fun downloadPatch(ctx: Context, patch: Patch, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val berkas = unduh(ctx, patch.url, "patch.bin", 1024L, onProgress) ?: return@withContext null
            try {
                val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                val hasil = File(dir, "REZO-patch.apk")
                if (hasil.exists()) hasil.delete()
                val lama = File(ctx.applicationInfo.sourceDir)
                val r = BsPatch.terapkan(lama, berkas, hasil)
                berkas.delete()
                if (!r.ok || !hasil.exists()) {
                    if (hasil.exists()) hasil.delete()
                    return@withContext null
                }
                if (patch.sha256.isNotBlank() && sha256(hasil) != patch.sha256) {
                    hasil.delete()
                    return@withContext null
                }
                hasil
            } catch (e: Exception) {
                null
            }
        }

    /** Buka pemasang Android. Kalau izin "pasang dari aplikasi ini" belum ada, arahkan ke pengaturan. */
    fun install(ctx: Context, apk: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return false
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updateprovider", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) { false }
    }
}

/** Dialog pembaruan. Pasang sekali di MainScreen; muncul sendiri kalau ada versi baru. */
@Composable
fun UpdateGate() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<AppUpdate.Info?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("app_update", Context.MODE_PRIVATE)
        val found = AppUpdate.check(ctx) ?: return@LaunchedEffect
        // "Nanti" hanya menunda untuk versi yang sama selama 6 jam.
        val skipV = prefs.getString("skip_v", "") ?: ""
        val skipAt = prefs.getLong("skip_at", 0L)
        if (skipV == found.version && System.currentTimeMillis() - skipAt < 6 * 3600_000L) return@LaunchedEffect
        info = found
    }

    val i = info ?: return
    if (dismissed) return

    Dialog(onDismissRequest = { if (progress < 0) dismissed = true }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1B1B1B))
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u2B07", color = Color(0xFFFFC21A), fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Text(tr("Pembaruan Tersedia"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "v" + i.version + "\n" + i.notes,
                color = Color(0xFFD0D0D0),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            val kecil = i.patch
            if (kecil != null && kecil.ukuran > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Unduhan kecil") + " ≈ " + "%.1f".format(kecil.ukuran / 1048576.0) + " MB",
                    color = Color(0xFFFFC21A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (progress >= 0) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color = Color(0xFFFFC21A),
                    trackColor = Color(0xFF3A3A3A),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (apk != null) tr("Unduhan selesai. Ketuk Pasang.") else tr("Mengunduh") + " " + progress + "%",
                    color = Color(0xFF9A9A9A),
                    fontSize = 12.sp,
                )
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = Color(0xFFFF7A7A), fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (progress < 0 || apk != null) {
                    Text(
                        tr("Nanti"),
                        color = Color(0xFFBDBDBD),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                ctx.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit()
                                    .putString("skip_v", i.version)
                                    .putLong("skip_at", System.currentTimeMillis())
                                    .apply()
                                dismissed = true
                            }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                val busy = progress in 0..99 && apk == null
                Text(
                    when {
                        apk != null -> tr("Pasang")
                        busy -> tr("Mengunduh...")
                        else -> tr("Perbarui Sekarang")
                    },
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (busy) Color(0xFF8A7020) else Color(0xFFFFC21A))
                        .clickable(enabled = !busy) {
                            error = ""
                            val ready = apk
                            if (ready != null) {
                                if (!AppUpdate.install(ctx, ready)) {
                                    error = tr("Izinkan REZO memasang aplikasi, lalu ketuk Pasang lagi.")
                                }
                            } else {
                                progress = 0
                                scope.launch {
                                    // Coba file selisih dulu (jauh lebih kecil). Kalau
                                    // gagal atau tidak cocok, otomatis unduh APK penuh.
                                    val kecil = i.patch
                                    var f = if (kecil != null) {
                                        AppUpdate.downloadPatch(ctx, kecil) { progress = it }
                                    } else null
                                    if (f == null) {
                                        progress = 0
                                        f = AppUpdate.download(ctx, i.url) { progress = it }
                                    }
                                    if (f == null) {
                                        progress = -1
                                        error = tr("Unduhan gagal. Periksa internet lalu coba lagi.")
                                    } else {
                                        apk = f
                                        progress = 100
                                        if (!AppUpdate.install(ctx, f)) {
                                            error = tr("Izinkan REZO memasang aplikasi, lalu ketuk Pasang lagi.")
                                        }
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 26.dp, vertical = 13.dp),
                )
            }
        }
    }
}
