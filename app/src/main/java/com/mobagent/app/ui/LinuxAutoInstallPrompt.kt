package com.mobagent.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.mobagent.app.linux.LinuxAutoInstall
import com.mobagent.app.linux.LinuxInstallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kirgandan keyin bir marta ishlaydi: Linux (Ubuntu) hali o'rnatilmagan bo'lsa, uni fonda o'zi
 * o'rnatishni boshlaydi (UserLAnd kabi). Dialog yo'q; jarayon bildirishnomada va
 * Sozlamalar > Linux muhiti'da ko'rinadi.
 */
@Composable
fun LinuxAutoInstallPrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (withContext(Dispatchers.IO) { LinuxAutoInstall.decide(context) } == LinuxAutoInstall.Decision.START) {
            LinuxInstallService.start(context, auto = true)
        }
    }
}
