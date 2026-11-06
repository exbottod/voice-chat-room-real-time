package com.rezochatvoice.exceo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Akun resmi Customer Service.
 *
 * Akun CS memakai ID 100001 (akun owner). Semua chat user masuk ke akun itu
 * sebagai chat pribadi biasa, jadi tidak perlu sistem pesan terpisah: cukup
 * tampilan di tab Pesan yang dikunci (nama, logo REZO, badge verified).
 */
object Cs {
    const val ID = "100001"

    /** Nomor WhatsApp yang dikirim ke user saat CS sedang offline. */
    const val WA = "6285282656572"

    const val NAME = "Customer Service"
    const val TAG = "CS-Resmi"

    @Volatile private var cachedUid: String? = null

    fun isCs(u: PublicUser?): Boolean = u != null && u.userId.trim() == ID
    fun isCsId(id: String?): Boolean = id != null && id.trim() == ID

    /** UID Firebase dari akun 100001 (dicari sekali lalu disimpan di memori). */
    suspend fun uid(): String? {
        cachedUid?.let { return it }
        val u = try {
            UserDirectory.uidFromId(ID)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (u != null) cachedUid = u
        return u
    }

    /** true kalau akun CS sedang membuka aplikasi (status/{uid} di RTDB). */
    suspend fun online(): Boolean {
        val u = uid() ?: return false
        return try {
            val s = withTimeoutOrNull(3000L) { Rtdb.ref("status/$u").get().await() }
            s?.value == true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun profile(uid: String) = PublicUser(
        userId = ID,
        uid = uid,
        name = NAME,
        countryCode = "ID",
        bio = "",
        avatarB64 = null,
        photoUrl = null,
    )

    /** Teks balasan otomatis saat owner tidak online. */
    fun autoReplyText(): String =
        "Admin sedang offline. Silakan hubungi WhatsApp $WA untuk respon lebih cepat."
}

/** Foto profil akun CS: logo resmi REZO, selalu bulat. */
@Composable
fun CsLogo(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_logo),
        contentDescription = tr("Customer Service"),
        modifier = Modifier.size(size).clip(CircleShape).background(Color(0xFF111111)),
    )
}

/**
 * Badge verified (GIF animasi dari owner). Dibundel di aset APK supaya tetap
 * tampil walau jaringan lambat.
 */
@Composable
fun VerifiedBadge(size: Dp = 16.dp) {
    AsyncImage(
        model = "file:///android_asset/badge_cs_verified.gif",
        contentDescription = tr("Terverifikasi"),
        modifier = Modifier.size(size),
    )
}

/** Nama akun CS lengkap dengan badge verified dan tag resmi. */
@Composable
fun CsName(fontSize: Int = 16, tag: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            Cs.NAME,
            color = GoldLight,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        VerifiedBadge((fontSize + 2).dp)
        if (tag) {
            Spacer(Modifier.width(5.dp))
            Text(
                Cs.TAG,
                color = Color(0xFF111111),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1D9BF0))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Penanda "sudah dibaca" khusus thread CS (badge di tab Pesan). */
object CsRead {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("cs_read", Context.MODE_PRIVATE)

    fun at(ctx: Context, myUid: String): Long = sp(ctx).getLong(myUid, 0L)

    fun mark(ctx: Context, myUid: String) {
        sp(ctx).edit().putLong(myUid, System.currentTimeMillis()).apply()
    }
}

/* ------------------------------------------------------------------ */
/*  Balasan otomatis CS (disimpan lokal, dibatasi sekali per jam)      */
/* ------------------------------------------------------------------ */

object CsAuto {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("cs_auto", Context.MODE_PRIVATE)

    private fun key(myUid: String) = "last_$myUid"

    fun lastAt(ctx: Context, myUid: String): Long = sp(ctx).getLong(key(myUid), 0L)

    /** true kalau balasan otomatis boleh ditampilkan sekarang (jeda 1 jam). */
    fun boleh(ctx: Context, myUid: String): Boolean =
        System.currentTimeMillis() - lastAt(ctx, myUid) > 3_600_000L

    fun tandai(ctx: Context, myUid: String) {
        sp(ctx).edit().putLong(key(myUid), System.currentTimeMillis()).apply()
    }

    /**
     * Balasan otomatis tampil setelah pesan user terkirim. Disimpan lokal
     * (bukan ditulis ke database) karena aturan database hanya mengizinkan
     * pemilik akun menulis pesan atas namanya sendiri.
     */
    fun jadwalkan(ctx: Context, myUid: String, onShow: (String, Long) -> Unit) {
        kotlinx.coroutines.GlobalScope.launch {
            try {
                if (!boleh(ctx, myUid)) return@launch
                if (Cs.online()) return@launch
                tandai(ctx, myUid)
                onShow(Cs.autoReplyText(), System.currentTimeMillis())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Pengumuman Resmi (dibaca dari Realtime Database, diisi dari panel) */
/* ------------------------------------------------------------------ */

object AnnRepo {
    data class Ann(
        val id: String,
        val title: String,
        val body: String,
        val at: Long,
        val pinned: Boolean,
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("pengumuman", Context.MODE_PRIVATE)

    fun lastRead(ctx: Context): Long = sp(ctx).getLong("last_read", 0L)

    fun markRead(ctx: Context) {
        sp(ctx).edit().putLong("last_read", System.currentTimeMillis()).apply()
    }

    fun unread(ctx: Context, list: List<Ann>): Int {
        val lr = lastRead(ctx)
        return list.count { it.at > lr }
    }

    /** Daftar pengumuman aktif, yang disematkan di atas, lalu terbaru. */
    fun observe(onChange: (List<Ann>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("pengumuman")) { s ->
            val list = s.children.mapNotNull { c ->
                val aktif = c.child("active").getValue(Boolean::class.java) ?: true
                if (!aktif) return@mapNotNull null
                val judul = c.child("title").getValue(String::class.java) ?: ""
                val isi = c.child("body").getValue(String::class.java) ?: ""
                if (judul.isBlank() && isi.isBlank()) return@mapNotNull null
                Ann(
                    id = c.key ?: return@mapNotNull null,
                    title = judul.ifBlank { "Pengumuman" },
                    body = isi,
                    at = c.child("at").getValue(Long::class.java) ?: 0L,
                    pinned = c.child("pinned").getValue(Boolean::class.java) ?: false,
                )
            }.sortedWith(compareByDescending<Ann> { it.pinned }.thenByDescending { it.at })
            onChange(list)
        }
}

private fun tanggal(ms: Long): String {
    if (ms <= 0L) return ""
    return try {
        SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date(ms))
    } catch (e: Exception) {
        ""
    }
}

/** Halaman baca Pengumuman Resmi. */
@Composable
fun AnnouncementScreen(list: List<AnnRepo.Ann>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(list.size) { if (list.isNotEmpty()) AnnRepo.markRead(ctx) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = GoldLight)
            }
            Text(tr("Pengumuman Resmi"), color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("Belum ada pengumuman."), color = Color(0xFF8A8A8A))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
                items(list, key = { it.id }) { a ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF161616))
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (a.pinned) {
                                Text(tr("📌"),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                            Text(
                                a.title,
                                color = Gold,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (a.at > 0) {
                            Text(tanggal(a.at), color = Color(0xFF8A8A8A), fontSize = 11.sp)
                        }
                        if (a.body.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(a.body, color = Color(0xFFE0E0E0), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Notifikasi chat: channel terpisah CS dan chat pribadi              */
/* ------------------------------------------------------------------ */

/** Status app dipakai untuk memutuskan perlu notifikasi atau tidak. */
object AppState {
    @Volatile var foreground: Boolean = false

    /** id chat yang sedang dibuka, supaya tidak memunculkan notifikasi. */
    @Volatile var openChatId: String? = null
}

object ChatNotif {
    const val CH_CS = "cs_chat"
    const val CH_PRIV = "chat_pribadi"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CH_CS, "Customer Service", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pesan dari Customer Service REZO"
                    enableVibration(true)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_PRIV, "Chat pribadi", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Pesan dari pengguna lain"
                    enableVibration(true)
                },
            )
        }
    }

    private fun dialogIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun idOf(chatId: String): Int = (chatId.hashCode() and 0x7FFFFFFF)

    fun post(ctx: Context, chatId: String, name: String, text: String, isCs: Boolean) {
        ensureChannels(ctx)
        val besar = BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_launcher_logo)
        val b = NotificationCompat.Builder(ctx, if (isCs) CH_CS else CH_PRIV)
            .setSmallIcon(R.drawable.ic_tab_pesan)
            .setLargeIcon(besar)
            .setContentTitle(if (isCs) Cs.NAME else name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(dialogIntent(ctx))
        try {
            if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                NotificationManagerCompat.from(ctx).notify(idOf(chatId), b.build())
            }
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }

    fun clear(ctx: Context, chatId: String) {
        try {
            NotificationManagerCompat.from(ctx).cancel(idOf(chatId))
        } catch (e: Exception) {
        }
    }
}

/**
 * Memantau daftar chat user dan memunculkan notifikasi untuk pesan baru saat
 * aplikasi sedang tidak dibuka atau user sedang bukan di thread tersebut.
 */
object ChatWatch {
    private var reg: Reg? = null
    private var startedFor: String? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("chat_notif", Context.MODE_PRIVATE)

    private fun seen(ctx: Context, chatId: String): Long = sp(ctx).getLong(chatId, 0L)

    private fun setSeen(ctx: Context, chatId: String, at: Long) {
        sp(ctx).edit().putLong(chatId, at).apply()
    }

    fun start(ctx: Context, myUid: String) {
        if (startedFor == myUid && reg != null) return
        reg?.remove()
        startedFor = myUid
        reg = Rtdb.listen(Rtdb.ref("chatsByUser/$myUid")) { s ->
            s.children.forEach { c ->
                val chatId = c.key ?: return@forEach
                val at = c.child("lastAt").getValue(Long::class.java) ?: return@forEach
                val from = c.child("lastFrom").getValue(String::class.java) ?: ""
                val teks = c.child("lastText").getValue(String::class.java) ?: ""
                val nama = c.child("otherName").getValue(String::class.java) ?: tr("Pengguna")
                val idLawan = c.child("otherId").getValue(String::class.java) ?: ""
                val lama = seen(ctx, chatId)
                if (lama == 0L) {
                    // Pertama kali dipantau: jangan notifikasi pesan lama.
                    setSeen(ctx, chatId, at)
                    return@forEach
                }
                if (at <= lama) return@forEach
                setSeen(ctx, chatId, at)
                if (from.isNotEmpty() && from == myUid) return@forEach
                if (AppState.openChatId == chatId) return@forEach
                if (AppState.foreground) return@forEach
                if (teks.isBlank()) return@forEach
                ChatNotif.post(ctx, chatId, nama, teks, Cs.isCsId(idLawan))
            }
        }
    }

    /** Tandai chat sudah dilihat user (dipakai saat thread dibuka). */
    fun markSeenNow(ctx: Context, chatId: String) {
        setSeen(ctx, chatId, System.currentTimeMillis())
    }

    fun stop() {
        reg?.remove()
        reg = null
        startedFor = null
    }
}
