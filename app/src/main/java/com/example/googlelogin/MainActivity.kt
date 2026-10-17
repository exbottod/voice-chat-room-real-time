package com.example.googlelogin

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import coil.compose.AsyncImage
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

/** Simpan sesi login sederhana di SharedPreferences. */
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
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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

    fun signIn() {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.startsWith("GANTI")) {
            Toast.makeText(
                context,
                "Web Client ID belum diisi (lihat gradle.properties)",
                Toast.LENGTH_LONG,
            ).show()
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
                    Toast.makeText(context, "Tipe kredensial tidak dikenal", Toast.LENGTH_LONG).show()
                }
            } catch (e: GetCredentialCancellationException) {
                // User menutup dialog, abaikan.
            } catch (e: GetCredentialException) {
                Log.e("GoogleLogin", "Login gagal", e)
                Toast.makeText(context, "Login gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("GoogleLogin", "Token tidak valid", e)
                Toast.makeText(context, "Token Google tidak valid", Toast.LENGTH_LONG).show()
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
        LoginScreen(loading = loading, onSignInClick = ::signIn)
    } else {
        HomeScreen(user = current, onSignOutClick = ::signOut)
    }
}

@Composable
fun LoginScreen(loading: Boolean, onSignInClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Selamat datang", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Masuk dengan akun Google untuk melanjutkan",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onSignInClick, modifier = Modifier.fillMaxWidth()) {
                Text("Masuk dengan Google")
            }
        }
    }
}

@Composable
fun HomeScreen(user: UserProfile, onSignOutClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!user.photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = user.photoUrl,
                contentDescription = "Foto profil",
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(user.name, style = MaterialTheme.typography.headlineSmall)
        Text(user.email, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onSignOutClick, modifier = Modifier.fillMaxWidth()) {
            Text("Keluar")
        }
    }
}
