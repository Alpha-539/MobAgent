package com.mobagent.app.ui

import com.mobagent.app.i18n.tr
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.history.ChatHistoryStore
import com.mobagent.app.history.ChatSession
import com.mobagent.app.linux.LinuxEnvironment
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import com.mobagent.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Yon panel (drawer). Tepadan pastga:
 *  1) Onlayn / Oflayn rejim tugmalari
 *  2) "Yangi suhbat" tugmasi va uning ostida suhbatlar tarixi (eng yangisi tepada).
 *     Suhbat ustini uzoq bosing: nomini o'zgartirish yoki o'chirish.
 *  3) Pastda: profil rasmi + ism/email (bosilsa Sozlamalar)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerContent(
    isOpen: Boolean,
    activeSessionId: String?,
    onlineSelected: Boolean,
    offlineSelected: Boolean,
    onOnlineClick: () -> Unit,
    onOfflineClick: () -> Unit,
    onOpenSession: (ChatSession) -> Unit,
    onSessionDeleted: (String) -> Unit,
    onNewChat: () -> Unit,
    onTerminalClick: () -> Unit,
    filesSelected: Boolean,
    onFilesClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ChatHistoryStore(context) }
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    suspend fun reload() {
        sessions = store.listSessions().filter { it.messages.isNotEmpty() }
        loaded = true
    }
    // Panel har ochilganda tarix yangilanadi (yangi suhbat saqlangan bo'lishi mumkin).
    LaunchedEffect(isOpen) {
        if (isOpen) {
            reload()
            store.syncFromCloud()
            reload()
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = BgApp,
        drawerTonalElevation = 0.dp,
        drawerShape = RoundedCornerShape(topEnd = Sizing.radiusLg, bottomEnd = Sizing.radiusLg),
        modifier = Modifier.widthIn(max = 340.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(20.dp))
            Text(
                "MobAgent", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, bottom = 16.dp)
            )

            // 1) Rejim tugmalari
            DrawerModeButton(Icons.Filled.CloudQueue, AccentPurple, AccentPurpleBg, tr("Onlayn rejim"), onlineSelected, onOnlineClick)
            Spacer(Modifier.height(8.dp))
            DrawerModeButton(Icons.Filled.PhoneAndroid, SuccessGreen, SuccessGreenBg, tr("Oflayn rejim"), offlineSelected, onOfflineClick)
            Spacer(Modifier.height(8.dp))
            // Terminal: Linux faol bo'lmasa ham bosiladi (ekran ichida yoqish yo'li tushuntiriladi).
            val linuxOn = LinuxEnvironment.isActive(context)
            DrawerModeButton(Icons.Outlined.Terminal, if (linuxOn) AccentPurple else TextMuted, AccentPurpleBg, tr("Terminal"), false, onTerminalClick)
            Spacer(Modifier.height(8.dp))
            // Fayl menejeri: Linux uyi, telefon xotirasi va ilova papkasini ko'rish, ulashish.
            DrawerModeButton(Icons.Outlined.Folder, AccentPurple, AccentPurpleBg, tr("Fayllar"), filesSelected, onFilesClick)

            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
            Spacer(Modifier.height(14.dp))

            // 2) Yangi suhbat + tarix
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(Sizing.radiusMd))
                    .background(BrandPurple)
                    .clickable(onClick = onNewChat),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Yangi suhbat"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                tr("Tarix"), color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
            if (loaded && sessions.isEmpty()) {
                Text(
                    tr("Hali saqlangan suhbat yo'q"), color = TextMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(sessions, key = { it.id }) { s ->
                    val active = s.id == activeSessionId
                    Box {
                        Text(
                            s.displayTitle.trim().replace('\n', ' '),
                            color = if (active) TextPrimary else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Sizing.radiusSm))
                                .background(if (active) BgCardElevated else Color.Transparent)
                                .combinedClickable(
                                    onClick = { onOpenSession(s) },
                                    onLongClick = { menuFor = s.id }
                                )
                                .padding(horizontal = 12.dp, vertical = 11.dp)
                        )
                        DropdownMenu(
                            expanded = menuFor == s.id,
                            onDismissRequest = { menuFor = null },
                            modifier = Modifier.background(BgCardElevated)
                        ) {
                            DropdownMenuItem(
                                text = { Text(tr("Nomini o'zgartirish"), color = TextPrimary) },
                                onClick = {
                                    menuFor = null
                                    renameText = s.title.orEmpty().ifBlank { s.preview.take(60) }
                                    renaming = s
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("O'chirish"), color = DangerText) },
                                onClick = {
                                    menuFor = null
                                    scope.launch {
                                        store.delete(s.id)
                                        reload()
                                        onSessionDeleted(s.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 3) Pastda: profil (Sozlamalar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp)
                    .clip(RoundedCornerShape(Sizing.radiusMd))
                    .clickable(onClick = onSettingsClick)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(size = 44.dp)
                Spacer(Modifier.width(12.dp))
                val user = AuthRepository.user
                val name = AuthRepository.displayName?.takeIf { it.isNotBlank() } ?: user?.email.orEmpty()
                Column(Modifier.weight(1f)) {
                    Text(name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("Sozlamalar"), color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = BgCardElevated,
            title = { Text(tr("Nomini o'zgartirish"), color = TextPrimary, fontSize = 17.sp) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(80) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentPurple,
                        unfocusedBorderColor = BorderColor
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newTitle = renameText
                    renaming = null
                    scope.launch { store.rename(target.id, newTitle); reload() }
                }) { Text(tr("Saqlash"), color = AccentPurple) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(tr("Bekor qilish"), color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun DrawerModeButton(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(Sizing.radiusMd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) BgCardElevated else Color.Transparent)
            .border(1.dp, if (selected) BorderColor else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
