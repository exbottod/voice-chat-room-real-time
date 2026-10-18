package com.example.googlelogin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    loading: Boolean,
    onGoogleClick: () -> Unit,
    onEmailLogin: (String, String) -> Unit,
    onForgotPassword: () -> Unit,
    onSignUp: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        RoyalBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crown(Modifier.size(width = 150.dp, height = 105.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "REZO CHAT\nVOICE",
                textAlign = TextAlign.Center,
                style = TextStyle(
                    brush = Brush.verticalGradient(listOf(GoldLight, Gold, GoldDark)),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp,
                    lineHeight = 44.sp,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(10.dp))
            OrnamentDivider(Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBlack.copy(alpha = 0.92f))
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(GoldDark, GoldLight, GoldDark)),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Welcome Back",
                    color = GoldLight,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(16.dp))

                FieldLabel("Email")
                GoldField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "email@example.com",
                    leading = { Icon(Icons.Default.Email, null) },
                    keyboardType = KeyboardType.Email,
                )
                Spacer(Modifier.height(16.dp))

                FieldLabel("Password")
                GoldField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Password",
                    leading = { Icon(Icons.Default.Lock, null) },
                    trailing = {
                        Box(
                            Modifier
                                .clickable { showPassword = !showPassword }
                                .padding(4.dp)
                        ) { EyeIcon(visible = showPassword) }
                    },
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = Gold.copy(alpha = 0.4f))
                    Text(
                        "Forgot password?",
                        color = GoldLight,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable(onClick = onForgotPassword)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    HorizontalDivider(Modifier.weight(1f), color = Gold.copy(alpha = 0.4f))
                }
                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(GoldBrush)
                        .clickable { onEmailLogin(email, password) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "LOGIN",
                        color = Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(tr("or continue with"), color = Color(0xFF9A9A9A), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF7F7F7))
                        .clickable(enabled = !loading, onClick = onGoogleClick),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = GoldDark,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GoogleLogo()
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Continue with Google",
                                color = Color(0xFF1F1F1F),
                                fontSize = 17.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(tr("Don't have an account?"), color = Color(0xFFBDBDBD), fontSize = 14.sp)
            Text(
                "Sign Up",
                color = GoldLight,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable(onClick = onSignUp)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        color = GoldLight,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    )
}

@Composable
private fun GoldField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leading: @Composable () -> Unit,
    keyboardType: KeyboardType,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = leading,
        trailingIcon = trailing,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Gold,
            focusedBorderColor = Gold,
            unfocusedBorderColor = Gold.copy(alpha = 0.6f),
            focusedLeadingIconColor = GoldLight,
            unfocusedLeadingIconColor = GoldLight,
            focusedPlaceholderColor = Color(0xFF8A8A8A),
            unfocusedPlaceholderColor = Color(0xFF8A8A8A),
        ),
    )
}
