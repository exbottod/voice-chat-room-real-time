package com.example.googlelogin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

data class UserProfile(
    val name: String,
    val email: String,
    val photoUrl: String?,
    val idToken: String,
)

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun load(): UserProfile? {
        val email = prefs.getString("email", null) ?: return null
        return UserProfile(
            name = prefs.getString("name", "") ?: "",
            email = email,
            photoUrl = prefs.getString("photo", null),
            idToken = "", // token Google tidak disimpan di perangkat
        )
    }

    fun save(user: UserProfile) {
        prefs.edit()
            .putString("name", user.name)
            .putString("email", user.email)
            .putString("photo", user.photoUrl)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

/**
 * Deep link room: rezo://room/<roomId> (dari fitur bagikan room).
 * Kalau link dibuka dan app sudah terpasang, otomatis masuk room setelah login.
 */
object RoomDeepLink {
    var pendingRoomId by mutableStateOf<String?>(null)

    fun parse(intent: Intent?) {
        val d = intent?.data ?: return
        if (d.scheme == "rezo" && d.host == "room") {
            pendingRoomId = d.pathSegments.firstOrNull()
        }
    }
}

class MainActivity : ComponentActivity() {
    /**
     * Kunci tata letak: tampilan app dibuat SAMA di semua HP.
     *
     * Semua ukuran di app memakai dp. Kalau HP punya layar padat, atau pengguna
     * memperbesar "ukuran tampilan"/font di pengaturan, semuanya ikut
     * membesar (medali jadi raksasa, angka level menonjol dari ikon).
     * Karena itu lebar layar dipatok ke 360 dp (lebar standar HP Android):
     * kepadatan dihitung dari lebar layar fisik, tidak dari pengaturan HP.
     * Font juga dikunci ke skala 1.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        val res = newBase.resources
        val cfg = android.content.res.Configuration(res.configuration)
        cfg.fontScale = 1.0f
        val lebarPx = minOf(res.displayMetrics.widthPixels, res.displayMetrics.heightPixels)
        if (lebarPx > 0) {
            val acuanDp = 360f
            // dpi = piksel per 160 / (dp). Skala supaya lebar layar selalu 360 dp.
            val dpi = (lebarPx / acuanDp * 160f).toInt().coerceIn(120, 640)
            cfg.densityDpi = dpi
        }
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        RoomDeepLink.parse(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        AppLang.load(this)
        WalletCache.init(this)
        setContent {
            // Bahasa Arab: layout kanan-ke-kiri (RTL).
            CompositionLocalProvider(
                LocalLayoutDirection provides if (AppLang.code == AppLang.AR) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                // Kunci skala font di seluruh teks Compose.
                val kepadatan = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        density = kepadatan.density,
                        fontScale = 1f,
                    ),
                ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                        var crashText by remember { mutableStateOf(CrashGuard.take(this@MainActivity)) }
                        PermissionWarmup()
                        AppRoot()
                        crashText?.let { CrashReportOverlay(it) { crashText = null } }
                    }
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppAktif.ya = true
    }

    override fun onPause() {
        AppAktif.ya = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RoomDeepLink.parse(intent)
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { SessionStore(context) }
    val credentialManager = remember { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()

    var user by remember { mutableStateOf(if (FirebaseSession.isSignedIn()) store.load() else null) }
    var loading by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun signIn() {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.startsWith("GANTI")) {
            toast("Web Client ID belum diisi")
            return
        }
        loading = true
        scope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val google = GoogleIdTokenCredential.createFrom(credential.data)
                    val profile = UserProfile(
                        name = google.displayName ?: google.id,
                        email = google.id,
                        photoUrl = google.profilePictureUri?.toString(),
                        idToken = google.idToken,
                    )
                    val uid = FirebaseSession.signIn(google.idToken)
                    store.save(profile)
                    user = profile
                    FirebaseSession.saveProfile(uid, profile)?.let {
                        toast("Profil belum tersimpan di server: ${it.message}")
                    }
                } else {
                    toast("Tipe kredensial tidak dikenal")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("GoogleLogin", "Login dibatalkan / ditolak", e)
                toast("Login Google dibatalkan. Jika dialog langsung menutup sendiri, periksa SHA-1 & package di Google Cloud Console.")
            } catch (e: NoCredentialException) {
                Log.e("GoogleLogin", "Tidak ada akun Google di perangkat", e)
                toast("Tidak ada akun Google di perangkat. Tambahkan akun Google di Pengaturan lalu coba lagi.")
            } catch (e: GetCredentialException) {
                Log.e("GoogleLogin", "Login gagal", e)
                toast("Login gagal [${e.type}]: ${e.message}")
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("GoogleLogin", "Token tidak valid", e)
                toast("Token Google tidak valid")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.e("GoogleLogin", "Gagal masuk Firebase", e)
                toast("Gagal masuk: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    fun emailLogin(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            toast("Isi email dan password")
            return
        }
        loading = true
        scope.launch {
            try {
                val fb = FirebaseSession.signInEmail(email.trim(), password)
                val (name, photo) = FirebaseSession.fetchBasic(fb.uid, email.trim().substringBefore("@"))
                val profile = UserProfile(name = name, email = email.trim(), photoUrl = photo, idToken = "")
                store.save(profile)
                user = profile
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.e("EmailLogin", "Gagal", e)
                toast(FirebaseSession.friendlyError(e))
            } finally {
                loading = false
            }
        }
    }

    fun signOut() {
        scope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w("GoogleLogin", "clearCredentialState gagal", e)
            }
            FirebaseSession.signOut()
            store.clear()
            user = null
        }
    }

    val current = user
    if (current == null) {
        LoginScreen(
            loading = loading,
            onGoogleClick = ::signIn,
            onEmailLogin = { e, p -> emailLogin(e, p) },
            onForgotPassword = { toast("Lupa password belum tersedia") },
            onSignUp = { toast("Pendaftaran belum tersedia") },
        )
    } else {
        MainScreen(user = current, onSignOutClick = ::signOut)
    }
}

/**
 * Minta izin penting sekali saat aplikasi dibuka: mikrofon, kamera, kontak,
 * telepon, penyimpanan/gambar, dan notifikasi. Izin yang sudah diberikan
 * tidak ditanyakan lagi.
 */
@Composable
fun PermissionWarmup() {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        val perlu = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) {
                add("android.permission.READ_MEDIA_IMAGES")
                add("android.permission.READ_MEDIA_VIDEO")
                add("android.permission.READ_MEDIA_AUDIO")
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (perlu.isNotEmpty()) launcher.launch(perlu.toTypedArray())
    }
}
