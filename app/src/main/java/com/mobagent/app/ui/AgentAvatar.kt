package com.mobagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mobagent.app.prompt.ModelFamily
import com.mobagent.app.ui.theme.*

/**
 * Har model oilasining o'z SVG ikonkasi va rangi — ilgari BARCHA agentlar
 * bitta xil "robot" belgisi bilan ko'rsatilardi. Bular Material Icons —
 * ya'ni vektor (SVG) ikonkalar, hech qanday emoji ishlatilmagan.
 */
private fun iconFor(family: ModelFamily?): ImageVector = when (family) {
    ModelFamily.QWEN -> Icons.Filled.Code           // kod yozishga ixtisoslashgan
    ModelFamily.DEEPSEEK_R1 -> Icons.Filled.Psychology // qadam-baqadam fikrlash
    ModelFamily.LLAMA3 -> Icons.Filled.Forum         // umumiy suhbat
    ModelFamily.GEMMA -> Icons.Filled.Translate      // tarjima/qisqartirish
    ModelFamily.PHI3 -> Icons.Filled.Bolt            // kichik va tezkor
    ModelFamily.MISTRAL -> Icons.Filled.AutoAwesome  // umumiy, "aqlli"
    else -> Icons.Filled.SmartToy                    // GENERIC / noma'lum
}

@Composable
private fun colorsFor(family: ModelFamily?): Pair<Color, Color> = when (family) {
    ModelFamily.QWEN -> CodingBg to CodingText
    ModelFamily.DEEPSEEK_R1 -> ReasoningBg to ReasoningText
    ModelFamily.LLAMA3 -> ChatFamilyBg to ChatFamilyText
    ModelFamily.GEMMA -> TranslateFamilyBg to TranslateFamilyText
    ModelFamily.PHI3 -> ReasoningBg to ReasoningText
    ModelFamily.MISTRAL -> CodingBg to CodingText
    else -> AccentPurpleBg to AccentPurple
}

@Composable
fun AgentAvatar(family: ModelFamily?, size: androidx.compose.ui.unit.Dp = 34.dp, iconSize: androidx.compose.ui.unit.Dp = 18.dp, neutral: Boolean = false) {
    // neutral = true: chat ekranida login uslubidagi monoxrom ko'rinish (rang o'rniga faqat ikonka farqlanadi).
    val (bg, tint) = if (neutral) BgCardElevated to TextPrimary else colorsFor(family)
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(iconFor(family), contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
