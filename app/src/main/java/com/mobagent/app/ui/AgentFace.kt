package com.mobagent.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mobagent.app.i18n.tr
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mobagent.app.ui.theme.*

/**
 * Agentning "kayfiyati" — foydalanuvchi so'radi: emoji ISHLATMA, buning
 * o'rniga oddiy ASCII yuzlar (bular emoji EMAS, oddiy matn belgilari) va
 * ularning animatsiyasi orqali agent nima qilayotgani intuitiv ko'rinsin.
 */
enum class AgentMood {
    IDLE,       // kutmoqda / hali hech narsa boshlanmagan
    THINKING,   // javob generatsiya qilmoqda — "o_o"
    WORKING,    // buyruq bajarmoqda / tasdiq kutmoqda (zo'riqqan) — ">_<"
    SUCCESS     // muvaffaqiyatli yakunladi — "^_^" (bir necha soniya ko'rinib, so'ng g'oyib bo'ladi)
}

private fun faceFor(mood: AgentMood): String = when (mood) {
    AgentMood.IDLE -> "\u2022_\u2022"
    AgentMood.THINKING -> "o_o"
    AgentMood.WORKING -> ">_<"
    AgentMood.SUCCESS -> "^_^"
}

/**
 * Kichik indikator — sarlavha/avatar ichida yoki xabarlar oqimida
 * "AI yozmoqda..." o'rnida ishlatiladi. THINKING holatida yumshoq
 * pulsatsiya (nafas olish) animatsiyasi, WORKING holatida tezroq
 * "tebranish" (zo'riqish hissi) beradi.
 */
@Composable
fun AgentFace(mood: AgentMood, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 15.sp) {
    val transition = rememberInfiniteTransition(label = "agent-face")

    val pulse by transition.animateFloat(
        initialValue = if (mood == AgentMood.WORKING) 0.85f else 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (mood == AgentMood.WORKING) 260 else 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "agent-face-scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (mood == AgentMood.WORKING) 260 else 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "agent-face-alpha"
    )

    val animated = mood == AgentMood.THINKING || mood == AgentMood.WORKING
    Text(
        text = faceFor(mood),
        color = when (mood) {
            AgentMood.SUCCESS -> SuccessGreenText
            AgentMood.WORKING -> DangerText
            else -> AccentPurple
        },
        fontSize = fontSize,
        fontWeight = FontWeight.Normal,
        fontFamily = JetBrainsMonoFamily,
        modifier = modifier
            .let { if (animated) it.scale(pulse).alpha(alpha) else it }
    )
}

/**
 * "o_o" + o'tgan soniyalar. Ba'zi onlayn modellar javobni to'liq tayyor qilib beradi (oqim yo'q) — shu paytda
 * foydalanuvchi ilova qotib qolmaganini ko'rishi uchun 3 soniyadan keyin "Model o'ylayapti… 12s" chiqadi.
 * Birinchi matn kelishi bilan chaqiruvchi bu komponentni olib tashlaydi, hisoblagich keyingi safar 0 dan boshlanadi.
 */
@Composable
fun ThinkingFace(modifier: Modifier = Modifier) {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds++
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentFace(AgentMood.THINKING)
        if (seconds >= 3) {
            Text(tr("Model o'ylayapti… %1\$ss", seconds), color = TextMuted, fontSize = 12.sp)
        }
    }
}
