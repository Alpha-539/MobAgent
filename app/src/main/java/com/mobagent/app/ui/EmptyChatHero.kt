package com.mobagent.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.i18n.tr
import com.mobagent.app.ui.theme.TextMuted
import kotlinx.coroutines.delay

private val HeroPhrases = listOf(
    "Bugun sizga qanday yordam bera olaman?",
    "Savol bering — javobni o'ylab topaman.",
    "Fayllar bilan ishlashni so'rang.",
    "Kod yozish, tushuntirish yoki tarjima — bemalol.",
    "Telefoningizdagi vazifalarni birga bajaramiz."
)

/**
 * Bo'sh chat ekrani: login sahifasidagi logotip (LoginBrand) va uning tagida
 * kichik shriftdagi, "yozilib-o'chib" almashib turuvchi xabar (katta AI
 * ilovalaridagi kabi). Yozish paytida oxirida miltillovchi kursor turadi.
 */
@Composable
fun EmptyChatHero(modifier: Modifier = Modifier) {
    var phraseIndex by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val phrase = tr(HeroPhrases[phraseIndex % HeroPhrases.size])
            // Harfma-harf yozish
            for (i in 1..phrase.length) {
                shown = phrase.substring(0, i)
                delay(38)
            }
            delay(2200)
            // Harfma-harf o'chirish (yozishdan tezroq)
            for (i in phrase.length - 1 downTo 0) {
                shown = phrase.substring(0, i)
                delay(16)
            }
            delay(350)
            phraseIndex++
        }
    }

    val cursorAlpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LoginBrand(large = false)
        Spacer(Modifier.height(20.dp))
        // Qator balandligi doimiy (2 qator) — matn uzunligi o'zgarganda logotip sakramaydi.
        Text(
            text = shown + if (cursorAlpha > 0.5f) "▍" else " ",
            color = TextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2
        )
    }
}
