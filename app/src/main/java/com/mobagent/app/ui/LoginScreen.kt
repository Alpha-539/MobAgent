package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.auth.AuthResult
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Kirish ekrani (ChatGPT ilovasi uslubida): o'rtada belgi + nom, pastda
 * to'liq kenglikdagi "pill" tugmalar. Asosiy tugma — Google; "Boshqa usul"
 * — email/parol shakliga o'tkazadi.
 */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isDarkNow = ThemePreference.override ?: isSystemInDarkTheme()
    var showEmail by remember { mutableStateOf(false) }
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showEmail) { showEmail = false; error = null; info = null }

    fun handle(result: AuthResult) {
        loading = false
        if (result is AuthResult.Error) error = result.message
        // Success: AuthRepository.user o'zgaradi va AppRoot o'zi Home'ga o'tkazadi.
    }

    fun submit() {
        error = null; info = null
        when {
            email.isBlank() || password.isBlank() -> { error = tr("Email va parolni kiriting."); return }
            isSignUp && password.length < 6 -> { error = tr("Parol kamida 6 ta belgidan iborat bo'lsin."); return }
            isSignUp && password != confirm -> { error = tr("Parollar bir xil emas."); return }
        }
        loading = true
        scope.launch {
            handle(
                if (isSignUp) AuthRepository.signUpWithEmail(email, password)
                else AuthRepository.signInWithEmail(email, password)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        // Tungi/tongi rejim tugmasi (o'ng yuqori burchak, ikkala bosqichda ham)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.dp, end = 24.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(BgCardElevated)
                .border(1.dp, BorderColor, CircleShape)
                .clickable { ThemePreference.toggle(context, isDarkNow) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isDarkNow) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = tr("Rejimni almashtirish"),
                tint = AccentPurple,
                modifier = Modifier.size(18.dp)
            )
        }

        if (!showEmail) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    LoginBrand(large = true)
                }

                error?.let {
                    Text(it, color = DangerText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                }

                // 1) Asosiy tugma — Google (to'ldirilgan, teskari rangda)
                PillButton(filled = true, enabled = !loading, onClick = {
                    error = null; loading = true
                    scope.launch { handle(AuthRepository.signInWithGoogle(context)) }
                }) {
                    if (loading) {
                        CircularProgressIndicator(color = BgApp, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        GoogleG(20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(tr("Google bilan davom etish"), color = BgApp, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 2) Boshqa usul — email/parol
                PillButton(filled = false, enabled = !loading, onClick = { error = null; showEmail = true }) {
                    Text(tr("Email bilan kirish"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(BgCardElevated)
                        .border(1.dp, BorderColor, CircleShape)
                        .clickable { showEmail = false; error = null; info = null },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Orqaga"), tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(24.dp))
                LoginBrand(large = false)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isSignUp) tr("Yangi hisob yarating") else tr("Hisobingizga kiring"),
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(28.dp))

                AuthField(email, { email = it }, tr("Email"), KeyboardType.Email)
                Spacer(Modifier.height(12.dp))
                AuthField(
                    value = password, onChange = { password = it }, label = tr("Parol"),
                    keyboardType = KeyboardType.Password, isPassword = true,
                    showPassword = showPassword, onToggleShow = { showPassword = !showPassword }
                )
                if (isSignUp) {
                    Spacer(Modifier.height(12.dp))
                    AuthField(
                        value = confirm, onChange = { confirm = it }, label = tr("Parolni takrorlang"),
                        keyboardType = KeyboardType.Password, isPassword = true,
                        showPassword = showPassword, onToggleShow = { showPassword = !showPassword }
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr("Parolni unutdingizmi?"),
                        color = AccentPurpleText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !loading) {
                                error = null; info = null
                                if (email.isBlank()) {
                                    error = tr("Avval email manzilingizni kiriting.")
                                } else {
                                    loading = true
                                    scope.launch {
                                        val r = AuthRepository.sendPasswordReset(email)
                                        handle(r)
                                        if (r is AuthResult.Success) info = tr("Parolni tiklash havolasi emailingizga yuborildi.")
                                    }
                                }
                            }
                            .padding(4.dp)
                    )
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = DangerText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                info?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = SuccessGreenText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(20.dp))
                PillButton(filled = true, enabled = !loading, onClick = { submit() }) {
                    if (loading) {
                        CircularProgressIndicator(color = BgApp, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Text(
                            if (isSignUp) tr("Ro'yxatdan o'tish") else tr("Kirish"),
                            color = BgApp, fontSize = 15.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row {
                    Text(if (isSignUp) tr("Hisobingiz bormi? ") else tr("Hisobingiz yo'qmi? "), color = TextSecondary, fontSize = 13.sp)
                    Text(
                        if (isSignUp) tr("Kirish") else tr("Ro'yxatdan o'ting"),
                        color = AccentPurpleText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(enabled = !loading) {
                            isSignUp = !isSignUp; error = null; info = null; confirm = ""
                        }
                    )
                }
            }
        }
    }
}

/** Belgi (>_<) + "MobAgent" nomi. Ikkalasi ham ilova shriftida (belgi qalin). */
@Composable
fun LoginBrand(large: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            ">_<",
            color = TextPrimary,
            fontSize = if (large) 64.sp else 40.sp,
            fontFamily = appFontFamily(),
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(if (large) 28.dp else 12.dp))
        Text(
            "MobAgent",
            color = BrandPurple,
            fontSize = if (large) 40.sp else 30.sp,
            fontFamily = appFontFamily(),
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Ilova logotipi — chat sahifasidagi bilan bir xil ">_<" belgisi (ilova shriftida, qalin).
 * Eski PNG logotip o'rniga hamma joyda shu ishlatiladi.
 */
@Composable
fun LogoMark(fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, color: Color = TextPrimary) {
    Text(
        ">_<",
        color = color,
        fontSize = fontSize,
        fontFamily = appFontFamily(),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
    )
}

@Composable
fun PillButton(
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(Sizing.radiusPill))
            .background(if (filled) TextPrimary else BgApp)
            .then(if (filled) Modifier else Modifier.border(1.dp, BorderColorStrong, RoundedCornerShape(Sizing.radiusPill)))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Google "G" belgisi (rasmiy to'rt rangda), Canvas bilan chizilgan. */
@Composable
private fun GoogleG(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width * 0.2f
        val inset = w / 2f
        val arcSize = Size(this.size.width - w, this.size.height - w)
        val topLeft = Offset(inset, inset)
        fun arc(color: Color, start: Float, sweep: Float) =
            drawArc(color, start, sweep, false, topLeft, arcSize, style = Stroke(width = w))
        arc(Color(0xFFEA4335), 215f, 105f)
        arc(Color(0xFFFBBC05), 145f, 70f)
        arc(Color(0xFF34A853), 45f, 100f)
        arc(Color(0xFF4285F4), -10f, 55f)
        val cy = this.size.height / 2f
        drawRect(
            Color(0xFF4285F4),
            topLeft = Offset(this.size.width / 2f, cy - w / 2f),
            size = Size(this.size.width / 2f - 0.5f, w)
        )
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleShow: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 13.sp) },
        singleLine = true,
        shape = RoundedCornerShape(Sizing.radiusMd),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword && onToggleShow != null) {
            {
                Icon(
                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = tr("Parolni ko'rsatish"),
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onToggleShow() }
                )
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedContainerColor = BgCard,
            unfocusedContainerColor = BgCard,
            focusedBorderColor = AccentPurple,
            unfocusedBorderColor = BorderColor,
            focusedLabelColor = AccentPurpleText,
            unfocusedLabelColor = TextMuted,
            cursorColor = AccentPurple
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
