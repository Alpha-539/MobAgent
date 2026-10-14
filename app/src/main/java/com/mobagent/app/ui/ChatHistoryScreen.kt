package com.mobagent.app.ui

import com.mobagent.app.i18n.mirrorInRtl
import com.mobagent.app.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.R
import com.mobagent.app.history.ChatHistoryStore
import com.mobagent.app.history.ChatSession
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ChatHistoryScreen(
    modelId: String?,
    modelName: String,
    onBack: () -> Unit,
    onOpenSession: (ChatSession) -> Unit
) {
    val context = LocalContext.current
    val store = remember { ChatHistoryStore(context) }
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        sessions = store.listSessions(modelId = modelId, modelName = modelName)
        loaded = true
    }

    LaunchedEffect(modelId, modelName) {
        reload()
        // Onlayn suhbatlar hisobga bog'langan: bulutdagi yangilarini tortib, ro'yxatni yangilaymiz.
        store.syncFromCloud()
        reload()
    }

    Column(Modifier.fillMaxSize().background(BgApp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBackIosNew,
                contentDescription = tr("Orqaga"),
                tint = TextSecondary,
                modifier = Modifier.size(16.dp).clickable(onClick = onBack).mirrorInRtl()
            )
            Spacer(Modifier.width(14.dp))
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).background(AccentPurpleBg),
                contentAlignment = Alignment.Center
            ) {
                LogoMark(fontSize = 11.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(tr("Suhbatlar tarixi"), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(modelName, color = TextMuted, fontSize = 11.sp)
            }
        }

        if (loaded && sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = TextMuted, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Hali saqlangan suhbat yo'q"), color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessions, key = { it.id }) { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Sizing.radiusMd))
                            .background(AccentPurpleBg)
                            .border(1.dp, BorderColor, RoundedCornerShape(Sizing.radiusMd))
                            .clickable { onOpenSession(s) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(30.dp).clip(CircleShape).background(AccentPurpleBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.preview, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = tr("O'chirish"),
                            tint = DangerText,
                            modifier = Modifier.size(16.dp).clickable {
                                scope.launch {
                                    store.delete(s.id)
                                    reload()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
