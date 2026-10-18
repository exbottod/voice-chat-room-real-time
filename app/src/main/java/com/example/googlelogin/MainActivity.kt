package com.example.googlelogin

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
            idToken = prefs.getString("token", "") ?: "",
        )
    }

    fun save(user: UserProfile) {
        prefs.edit()
            .putString("name", user.name)
            .putString("email", user.email)
            .putString("photo", user.photoUrl)
            .putString("token", user.idToken)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AppLang.load(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    AppRoot()
                }
            }
        }
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
