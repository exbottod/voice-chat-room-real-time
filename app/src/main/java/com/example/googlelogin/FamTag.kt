package com.example.googlelogin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Teks label family: pill emas kecil. */
@Composable
fun FamTagText(fam: String, modifier: Modifier = Modifier) {
    Text(
        "\uD83E\uDEE1 $fam",
        color = GoldLight,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Label family otomatis dari profil publik user (field "fam" di userIds). */
@Composable
fun FamTagByUid(uid: String?, modifier: Modifier = Modifier) {
    if (uid.isNullOrBlank()) return
    var fam by remember(uid) { mutableStateOf("") }
    LaunchedEffect(uid) {
        fam = try {
            UserDirectory.findByUid(uid)?.fam ?: ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }
    if (fam.isNotBlank()) FamTagText(fam, modifier)
}
