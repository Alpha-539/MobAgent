package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mobagent.app.model.ModelItem
import com.mobagent.app.ui.theme.*
import java.io.File

/**
 * Chat sarlavhasidagi model nomini bosganda ochiladigan vertikal ro'yxat.
 * Birinchi qatorda "Modellarni boshqarish", pastida esa telefonga yuklab
 * olingan modellar (aylantiriladi). Tashqarini bossangiz yopiladi.
 */
@Composable
fun ModelSwitcherPopup(
    models: List<Pair<ModelItem, File>>,
    currentId: String?,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onSelect: (ModelItem, File) -> Unit
) {
    val density = LocalDensity.current
    val sideMargin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    // Ro'yxat sarlavhaning pastidan, ekranning chap chetidan 12dp masofada chiqadi.
    val positionProvider = remember(sideMargin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ) = IntOffset(sideMargin, anchorBounds.bottom + gap)
        }
    }
    val width = LocalConfiguration.current.screenWidthDp.dp - 24.dp

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.width(width),
            shape = RoundedCornerShape(16.dp),
            color = BgCard,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentPurpleBg)
                        .clickable(onClick = onManage)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Tune, null, tint = AccentPurpleText, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        tr("Modellarni boshqarish"),
                        color = AccentPurpleText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ChevronRight, null, tint = AccentPurpleText, modifier = Modifier.size(18.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    models.forEachIndexed { index, (model, file) ->
                        val isCurrent = model.id == currentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isCurrent) BgCardAlt else BgCard)
                                .clickable { if (isCurrent) onDismiss() else onSelect(model, file) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AgentAvatar(model.family, size = 34.dp, iconSize = 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(model.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${model.modeLabel} · ${"%.1f".format(model.baseSizeMb / 1000f)} GB",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            if (isCurrent) {
                                Icon(Icons.Filled.Check, tr("Tanlangan"), tint = AccentPurple, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (index < models.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Onlayn (o'z API kalitim bilan) chat sarlavhasidagi model ro'yxati. Oflayn [ModelSwitcherPopup]
 * bilan bir xil ko'rinishda, lekin ro'yxat telefon xotirasidan emas, API kalitning o'zidan
 * (xizmatning /models manzilidan) olinadi — ya'ni kalit qaysi modellarni taqdim etsa, shular chiqadi.
 */
@Composable
fun OnlineModelSwitcherPopup(
    providerName: String,
    loader: suspend () -> List<String>?,
    currentModel: String,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onSelect: (String) -> Unit
) {
    val density = LocalDensity.current
    val sideMargin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val positionProvider = remember(sideMargin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ) = IntOffset(sideMargin, anchorBounds.bottom + gap)
        }
    }
    val width = LocalConfiguration.current.screenWidthDp.dp - 24.dp

    var models by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        models = loader()
        loading = false
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.width(width),
            shape = RoundedCornerShape(16.dp),
            color = BgCard,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentPurpleBg)
                        .clickable(onClick = onManage)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Key, null, tint = AccentPurpleText, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        tr("API kalitlarni boshqarish"),
                        color = AccentPurpleText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ChevronRight, null, tint = AccentPurpleText, modifier = Modifier.size(18.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                val list = models
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = AccentPurple, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(tr("Modellar yuklanmoqda…"), color = TextMuted, fontSize = 13.sp)
                    }
                    list == null -> Column(
                        Modifier.fillMaxWidth().clickable { attempt++ }.padding(horizontal = 14.dp, vertical = 14.dp)
                    ) {
                        Text(tr("Modellar ro'yxatini olib bo'lmadi"), color = TextPrimary, fontSize = 13.sp)
                        Text(tr("Qayta urinish uchun bosing"), color = AccentPurpleText, fontSize = 12.sp)
                    }
                    else -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        // Joriy model API ro'yxatida bo'lmasa ham (masalan qo'lda kiritilgan) tepada ko'rinadi.
                        val items = if (currentModel in list) list else listOf(currentModel) + list
                        items.forEachIndexed { index, m ->
                            val isCurrent = m == currentModel
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isCurrent) BgCardAlt else BgCard)
                                    .clickable { if (isCurrent) onDismiss() else onSelect(m) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        m, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(providerName, color = TextMuted, fontSize = 11.sp)
                                }
                                if (isCurrent) {
                                    Icon(Icons.Filled.Check, tr("Tanlangan"), tint = AccentPurple, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (index < items.lastIndex) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                            }
                        }
                    }
                }
            }
        }
    }
}
