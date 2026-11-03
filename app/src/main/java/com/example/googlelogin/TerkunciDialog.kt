package com.example.googlelogin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogProperties

/**
 * Pengganti Dialog Compose yang ikut kunci tampilan 360 dp.
 *
 * Masalahnya: kunci layout dipasang di Activity, tetapi Dialog membuat jendela
 * sendiri yang memakai kepadatan layar ASLI HP. Akibatnya isi ranking dan
 * semua game dilukis lebih lebar dari layar dan terpotong di sisi kanan.
 * Di sini kepadatan dihitung ulang dengan rumus yang sama seperti
 * [LayoutLock] (sisi pendek layar = 360 dp, font skala 1) lalu dipasang
 * untuk seluruh isi dialog.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val dm = ctx.resources.displayMetrics
    val sisiPendek = minOf(dm.widthPixels, dm.heightPixels).toFloat()
    val terkunci = Density(
        density = if (sisiPendek > 0f) sisiPendek / 360f else LocalDensity.current.density,
        fontScale = 1f,
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(LocalDensity provides terkunci) { content() }
    }
}
