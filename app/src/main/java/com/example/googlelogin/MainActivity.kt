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

    var user by remember { mutableStateOf(store.load()) }
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
                    store.save(profile)
                    user = profile
                } else {
                    toast("Tipe kredensial tidak dikenal")
                }
            } catch (e: GetCredentialCancellationException) {
                toast("Dibatalkan [${e.type}]: ${e.message}")
            } catch (e: GetCredentialException) {
                Log.e("GoogleLogin", "Login gagal", e)
                toast("Login gagal [${e.type}]: ${e.message}")
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("GoogleLogin", "Token tidak valid", e)
                toast("Token Google tidak valid")
            } finally {
                loading = false
            }
        }
    }

    fun signOut() {
        scope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("GoogleLogin", "clearCredentialState gagal", e)
            }
            store.clear()
            user = null
        }
    }

    val current = user
    if (current == null) {
        LoginScreen(
            loading = loading,
            onGoogleClick = ::signIn,
            onEmailLogin = { _, _ -> toast("Login email belum tersedia, gunakan Google") },
            onForgotPassword = { toast("Lupa password belum tersedia") },
            onSignUp = { toast("Pendaftaran belum tersedia") },
        )
    } else {
        MainScreen(user = current, onSignOutClick = ::signOut)
    }
}
