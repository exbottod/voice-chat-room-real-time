package com.example.googlelogin

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth

/** URL logo label family per level. Kirim link level lain nanti, tinggal diisi. */
val FamLogoUrls = mapOf(
    1 to "https://i.ibb.co.com/0VtPnGjJ/ip-9.png", // hijau - level 1
    2 to "https://i.ibb.co.com/hJTC85c4/a55.webp", // biru - level 2
    3 to "https://i.ibb.co.com/hJTC85c4/a55.webp", // ungu - level 3
    4 to "https://i.ibb.co.com/NdJ23LNy/a57.webp", // kuning - level 4
    5 to "https://i.ibb.co.com/XrswkKVX/b685f30b9384c77c9871b6a0ef4dd059.png", // merah - level 5
)
private const val FamLogoFallback = "https://i.ibb.co.com/XrswkKVX/b685f30b9384c77c9871b6a0ef4dd059.png"

/** Teks label family versi lama (emas kecil). */
@Composable
fun FamTagText(fam: String, modifier: Modifier = Modifier) {
    Text(
        "🫡 $fam",
        color = GoldLight,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Label family: logo per level (URL) + nama family di depan.
 *  Diketuk -> langsung buka halaman family. */
@Composable
fun FamTag3D(fam: String, modifier: Modifier = Modifier) {
    if (fam.isBlank()) return
    val context = LocalContext.current
    var famId by remember(fam) { mutableStateOf<String?>(null) }
    var level by remember(fam) { mutableStateOf(5) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(fam) {
        val f = FamilyRepo.findByName(fam)
        famId = f?.id
        if (f != null) level = familyLevel(f.exp).coerceIn(1, 5)
    }
    val url = FamLogoUrls[level]?.ifBlank { FamLogoFallback } ?: FamLogoFallback
    Box(
        modifier
            .height(20.dp)
            .width(72.dp)
            .clickable {
                if (famId == null) Toast.makeText(context, "Family tidak ditemukan", Toast.LENGTH_SHORT).show()
                else open = true
            },
    ) {
        AsyncImage(
            model = url,
            contentDescription = fam,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High,
        )
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(0.36f))
            Text(
                fam,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color(0xAA000000), Offset(0.8f, 1f), 2f)),
                modifier = Modifier.weight(0.64f, fill = true),
            )
        }
    }
    if (open && famId != null) {
        FamilyProfileDialog(
            fid = famId!!,
            myUid = FirebaseAuth.getInstance().currentUser?.uid ?: "",
            myName = "",
            myAvatar = null,
            onDismiss = { open = false },
        )
    }
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
    if (fam.isNotBlank()) FamTag3D(fam, modifier)
}
