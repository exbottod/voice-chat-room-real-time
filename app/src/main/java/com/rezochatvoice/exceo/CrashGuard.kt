package com.rezochatvoice.exceo

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Penangkap kesalahan supaya aplikasi tidak langsung hilang tanpa kabar.
 * Pesan kesalahan terakhir disimpan lalu ditampilkan saat aplikasi dibuka lagi,
 * jadi pengguna bisa mengirimkannya untuk diperbaiki.
 */
object CrashGuard {
    private const val PREF = "rezo_crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        if (old is Saver) return
        Thread.setDefaultUncaughtExceptionHandler(Saver(app, old))
    }

    fun take(context: Context): String? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val v = p.getString("last", null)
        if (v != null) p.edit().remove("last").apply()
        return v
    }

    private class Saver(
        private val app: Context,
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val trace = Log.getStackTraceString(e).take(3500)
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString("last", trace).apply()
            } catch (_: Exception) {
            }
            next?.uncaughtException(t, e)
        }
    }
}

/** Layar pesan kesalahan terakhir, ditampilkan setelah aplikasi dibuka ulang. */
@Composable
fun CrashReportOverlay(text: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xF2101010)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1B1B1B))
                .padding(14.dp),
        ) {
            Text(tr("Aplikasi sempat berhenti"), color = Color(0xFFFF8A80), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(tr("Kirim tulisan di bawah ini ke pengembang supaya bisa diperbaiki."),
                color = Color(0xFFBDBDBD), fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text,
                color = Color(0xFFE0E0E0),
                fontSize = 10.sp,
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFD4AF37))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("Tutup"), color = Color(0xFF231A00), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
