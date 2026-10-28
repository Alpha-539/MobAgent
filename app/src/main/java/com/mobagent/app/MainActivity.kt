package com.mobagent.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.mobagent.app.i18n.AppLanguage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mobagent.app.auth.AuthRepository
import com.mobagent.app.history.ChatSession
import com.mobagent.app.model.ModelItem
import com.mobagent.app.online.OnlineChatConfig
import com.mobagent.app.online.OnlineProviders
import com.mobagent.app.model.ContextPreference
import com.mobagent.app.model.ModelCatalog
import com.mobagent.app.ui.theme.BgApp
import com.mobagent.app.ui.theme.FontPreference
import com.mobagent.app.ui.*
import com.mobagent.app.ui.theme.MobAgentTheme
import com.mobagent.app.ui.theme.ThemePreference
import java.io.File

class MainActivity : ComponentActivity() {
    // Android 13+: e'lon bildirishnomalari uchun ruxsat (bir marta so'raladi).
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppForeground.init(application)
        ThemePreference.init(applicationContext)
        FontPreference.init(applicationContext)
        ContextPreference.init(applicationContext)
        AppLanguage.init(applicationContext)
        AuthRepository.init()
        com.mobagent.app.push.Push.ensureChannel(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            com.mobagent.app.push.Push.shouldAskPermission(this)
        ) {
            com.mobagent.app.push.Push.markAsked(this)
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        com.mobagent.app.model.RemoteCatalog.loadCached(applicationContext)
        com.mobagent.app.model.ModelCatalog.custom = com.mobagent.app.model.CustomModelStore.load(applicationContext)
        com.mobagent.app.billing.BillingManager.init(applicationContext)
        com.mobagent.app.model.ApiClient.event("app_open")
        com.mobagent.app.history.ImageStore.init(applicationContext)
        // Eski (filesDir'dagi) modellarni ilova o'chirilsa ham saqlanadigan joyga nusxalash.
        Thread {
            runCatching { com.mobagent.app.model.syncModelsToPersistent(applicationContext) }
            runCatching { com.mobagent.app.model.ModelDownloadService.restorePausedIfAny(applicationContext) }
        }.start()
        setContent {
            val darkTheme = ThemePreference.override ?: isSystemInDarkTheme()
            // Arabcha — o'ngdan chapga yozuv; boshqa tillarda odatdagidek.
            // Til o'zgarsa, e'lon bildirishnomalari ham yangi til topic'iga o'tadi.
            val pushLang = AppLanguage.effective.code
            LaunchedEffect(pushLang) { com.mobagent.app.push.Push.syncTopics(applicationContext, pushLang) }
            val direction = if (AppLanguage.effective.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MobAgentTheme(darkTheme = darkTheme) {
                    AppRoot()
                }
            }
        }
    }
}

private sealed class Screen {
    object Home : Screen()
    object Online : Screen()
    /** `from` — tariflar ekranidan qaytiladigan joy (odatda chat). */
    data class CloudPlans(val from: Screen? = null) : Screen()
    object ApiKeys : Screen()
    object OfflineModelList : Screen()
    object ExploreHuggingFace : Screen()
    object Settings : Screen()
    object Profile : Screen()
    object SettingsModels : Screen()
    object SettingsLinux : Screen()
    object Files : Screen()
    data class History(val modelId: String?, val modelName: String, val backTo: Screen) : Screen()
    data class Downloading(val model: ModelItem, val backTo: Screen) : Screen()
    data class Chat(
        val name: String,
        val modelPath: String? = null,
        val modelId: String? = null,
        val sessionId: String? = null,
        val nCtx: Int = ContextPreference.value,
        val backTo: Screen = Home,
        // "Yangi suhbat" bir xil model bilan bosilganda ekran qayta yaratilishi uchun.
        val nonce: Int = 0
    ) : Screen()
}

@Composable
fun AppRoot() {
    // targetSdk 35+ da ilova majburan tizim panellari ostigacha chiziladi (edge-to-edge) va `adjustResize`
    // oynani klaviatura uchun kichraytirmaydi. Chetlar shu yerda bir marta ayriladi; ichidagi ekranlar
    // o'zlarining statusBarsPadding()/imePadding()'ini qo'shsa ham, qo'sh bo'shliq bo'lmaydi (inset iste'mol qilingan).
    Box(Modifier.fillMaxSize().background(BgApp).systemBarsPadding().imePadding()) {
        // Kirmagan foydalanuvchi — Login; kirgach — asosiy ilova. Chiqib ketganda
        // SignedInRoot() yo'qoladi va uning ichki holati (ekran) o'z-o'zidan tozalanadi.
        when {
            AuthRepository.user == null -> LoginScreen()
            AuthRepository.needsVerification -> VerifyEmailScreen()
            else -> SignedInRoot()
        }
    }
}

@Composable
private fun SignedInRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // Yuklab olish xatosi matni: DownloadScreen'dan do'konga qaytganda ko'rsatiladi.
    var downloadError by remember { mutableStateOf<String?>(null) }
    // Terminal qatlami yon paneldan ochiladi va hamma ekran ustida turadi (ostidagi ekran o'chmaydi).
    var showTerminal by remember { mutableStateOf(false) }

    // Linux muhitini (Ubuntu) kerak bo'lsa fonda o'zi o'rnatadi — sozlamaga kirish shart emas.
    com.mobagent.app.ui.LinuxAutoInstallPrompt()

    // API kalitlar: kirilishi bilan boshqa telefondan tushadi / bu telefondagilar hisobga yuklanadi (jimgina, fonda).
    val keysContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(AuthRepository.user?.uid) { com.mobagent.app.online.CloudKeys.sync(keysContext) }

    // Ilgari qurilmaning tizim/orqaga tugmasi (yoki gesture) hech qanday
    // ekran ichida ushlanmagani uchun to'g'ridan-to'g'ri ilovadan chiqib
    // ketardi. Endi u shu ekranning o'z "orqaga" mantig'iga yo'naltiriladi.
    val current = screen
    BackHandler(enabled = current !is Screen.Home) {
        screen = when (current) {
            is Screen.Home -> Screen.Home
            is Screen.Online -> Screen.Home
            is Screen.ApiKeys -> Screen.Online
            is Screen.CloudPlans -> current.from ?: Screen.Online
            is Screen.OfflineModelList -> Screen.Home
            is Screen.ExploreHuggingFace -> Screen.OfflineModelList
            is Screen.Settings -> Screen.Home
            is Screen.Profile -> Screen.Settings
            is Screen.SettingsModels -> Screen.Settings
            is Screen.SettingsLinux -> Screen.Settings
            is Screen.Files -> Screen.Home
            is Screen.History -> current.backTo
            is Screen.Downloading -> current.backTo
            is Screen.Chat -> current.backTo
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Oxirgi ochilgan suhbat — "Yangi suhbat" tugmasi shu model bilan yangi suhbat ochadi.
    var lastChat by remember { mutableStateOf<Screen.Chat?>(null) }
    LaunchedEffect(current) { if (current is Screen.Chat) lastChat = current }

    // Panel ochiq bo'lsa, "orqaga" avval panelni yopadi.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    fun go(target: Screen) {
        screen = target
        scope.launch { drawerState.close() }
    }

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        // Surish ishorasi faqat bosh sahifada; boshqa ekranlarda panel ochiq bo'lsa yopish uchun.
        gesturesEnabled = current is Screen.Home || drawerState.isOpen,
        drawerContent = {
            AppDrawerContent(
                isOpen = drawerState.isOpen,
                activeSessionId = (current as? Screen.Chat)?.sessionId,
                onlineSelected = current is Screen.Online || current is Screen.ApiKeys || (current is Screen.Chat && current.modelPath == null),
                offlineSelected = current is Screen.OfflineModelList || (current is Screen.Chat && current.modelPath != null),
                onOnlineClick = { go(Screen.Online) },
                onOfflineClick = { go(Screen.OfflineModelList) },
                onOpenSession = { s ->
                    go(Screen.Chat(s.modelName, s.modelPath, s.modelId, s.id, nCtx = ModelCatalog.byId(s.modelId)?.contextSize ?: ContextPreference.value, backTo = Screen.Home))
                },
                onSessionDeleted = { id ->
                    // Ochiq turgan suhbat o'chirilsa — bosh sahifaga qaytamiz.
                    if ((current as? Screen.Chat)?.sessionId == id) screen = Screen.Home
                },
                onNewChat = {
                    val base = (current as? Screen.Chat) ?: lastChat
                    go(
                        if (base != null) base.copy(sessionId = null, nonce = base.nonce + 1, backTo = Screen.Home)
                        else Screen.Home
                    )
                },
                onTerminalClick = { showTerminal = true; scope.launch { drawerState.close() } },
                filesSelected = current is Screen.Files,
                onFilesClick = { go(Screen.Files) },
                onSettingsClick = { go(Screen.Settings) }
            )
        }
    ) {
        when (current) {
            is Screen.Home -> HomeScreen(
                onOnlineClick = { screen = Screen.Online },
                onOfflineClick = { screen = Screen.OfflineModelList },
                onMenuClick = { scope.launch { drawerState.open() } }
            )

            is Screen.Settings -> SettingsScreen(
                onBack = { screen = Screen.Home },
                onOpenProfile = { screen = Screen.Profile },
                onOpenModels = { screen = Screen.SettingsModels },
                onOpenLinux = { screen = Screen.SettingsLinux },
                onOpenBilling = { screen = Screen.CloudPlans(from = Screen.Settings) }
            )

            is Screen.SettingsLinux -> com.mobagent.app.ui.LinuxSettingsScreen(onBack = { screen = Screen.Settings })

            is Screen.Files -> FileManagerScreen(onBack = { screen = Screen.Home })

            is Screen.SettingsModels -> ModelsSettingsScreen(
                onBack = { screen = Screen.Settings },
                onOpenStore = { screen = Screen.OfflineModelList }
            )

            is Screen.Profile -> ProfileScreen(onBack = { screen = Screen.Settings })

            is Screen.Online -> OnlineModeScreen(
                onBack = { screen = Screen.Home },
                // Pullik rejim: avval chatning o'zi ko'rinadi, tarif esa xabar yuborilganda so'raladi.
                onConfirm = {
                    // Standart pullik model ochiladi; tarifi yo'q foydalanuvchiga xabar yuborilganda tarif taklif qilinadi.
                    screen = Screen.Chat(
                        com.mobagent.app.billing.CloudApi.DEFAULT_MODEL_NAME,
                        modelId = OnlineChatConfig(com.mobagent.app.billing.CloudApi.PROVIDER_ID, com.mobagent.app.billing.CloudApi.DEFAULT_MODEL_ID).encode(),
                        backTo = Screen.Online
                    )
                },
                onOpenApiKeys = { screen = Screen.ApiKeys },
                onMenuClick = { scope.launch { drawerState.open() } }
            )

            is Screen.CloudPlans -> com.mobagent.app.ui.CloudPlansScreen(
                onBack = { screen = current.from ?: Screen.Online },
                onOpenModel = { id, name ->
                    screen = Screen.Chat(
                        name,
                        modelId = OnlineChatConfig(com.mobagent.app.billing.CloudApi.PROVIDER_ID, id).encode(),
                        backTo = Screen.Online
                    )
                },
                onMenuClick = { scope.launch { drawerState.open() } }
            )

            is Screen.ApiKeys -> ApiKeysScreen(
                onBack = { screen = Screen.Online },
                // Tanlangan xizmat/kalit keyingi bosqichda (onlayn chat ulanishi) ishlatiladi.
                // Kalit tekshirildi va model tanlandi: suhbat shu xizmat/model bilan ochiladi.
                // Xizmat va model modelId'da kodlanadi — tarixdan qayta ochilganda ham saqlanadi.
                onConfirm = { choice ->
                    if (choice is OnlineChoice.OwnApiKey) {
                        val provider = OnlineProviders.byId(choice.providerId)
                        screen = Screen.Chat(
                            provider?.name ?: "Onlayn AI",
                            modelId = OnlineChatConfig(choice.providerId, choice.model).encode(),
                            backTo = Screen.Online
                        )
                    }
                }
            )

            is Screen.OfflineModelList -> ModelStoreScreen(
                onBack = { screen = Screen.Home },
                onModelSelected = { model -> screen = Screen.Downloading(model, Screen.OfflineModelList) },
                onModelOpen = { model, file ->
                    screen = Screen.Chat(model.name, file.absolutePath, model.id, nCtx = model.contextSize, backTo = Screen.OfflineModelList)
                },
                onMenuClick = { scope.launch { drawerState.open() } },
                startupError = downloadError,
                onStartupErrorShown = { downloadError = null },
                onAddClick = { screen = Screen.ExploreHuggingFace }
            )

            is Screen.ExploreHuggingFace -> ExploreHuggingFaceScreen(
                onBack = { screen = Screen.OfflineModelList },
                onModelDownload = { model -> screen = Screen.Downloading(model, Screen.OfflineModelList) },
                onModelImported = { _ -> screen = Screen.OfflineModelList }
            )

            is Screen.History -> ChatHistoryScreen(
                modelId = current.modelId,
                modelName = current.modelName,
                onBack = { screen = current.backTo },
                onOpenSession = { session: ChatSession ->
                    screen = Screen.Chat(session.modelName, session.modelPath, session.modelId, session.id, nCtx = ModelCatalog.byId(session.modelId)?.contextSize ?: ContextPreference.value, backTo = current.backTo)
                }
            )

            is Screen.Downloading -> DownloadScreen(
                model = current.model,
                onFinished = { file: File ->
                    screen = Screen.Chat(current.model.name, file.absolutePath, current.model.id, nCtx = current.model.contextSize, backTo = Screen.OfflineModelList)
                },
                onError = { msg ->
                    downloadError = msg
                    screen = Screen.OfflineModelList
                },
                onCancel = { screen = Screen.OfflineModelList }
            )

            // key(...): model almashtirilganda (Chat -> Chat) ekran holati va native
            // engine to'liq yangilanishi uchun — eski model yopilib, yangisi yuklanadi.
            is Screen.Chat -> key(current.modelId, current.sessionId, current.name, current.nonce) {
                ChatScreen(
                    modelName = current.name,
                    modelPath = current.modelPath,
                    modelId = current.modelId,
                    sessionId = current.sessionId,
                    nCtx = current.nCtx,
                    onBack = { screen = current.backTo },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onNewChat = { /* ChatScreen o'z holatini o'zi tozalaydi */ },
                    onSwitchModel = { model, file ->
                        screen = Screen.Chat(
                            model.name, file.absolutePath, model.id,
                            nCtx = model.contextSize, backTo = current.backTo
                        )
                    },
                    onManageModels = { screen = Screen.OfflineModelList },
                    // Onlayn: boshqa model tanlansa — shu xizmat/kalit bilan yangi suhbat ochiladi.
                    onSwitchOnlineModel = { providerId, model ->
                        screen = Screen.Chat(
                            OnlineProviders.byId(providerId)?.name ?: "Onlayn AI",
                            modelId = OnlineChatConfig(providerId, model).encode(),
                            backTo = current.backTo
                        )
                    },
                    onManageApiKeys = { screen = Screen.ApiKeys },
                    // Pullik rejim: yozish maydonidagi model tugmasidan tanlanganda va tarif kerak bo'lganda.
                    onSwitchCloudModel = { id, name ->
                        screen = Screen.Chat(
                            name,
                            modelId = OnlineChatConfig(com.mobagent.app.billing.CloudApi.PROVIDER_ID, id).encode(),
                            backTo = current.backTo
                        )
                    },
                    onOpenPlans = { screen = Screen.CloudPlans(from = current) }
                )
            }
        }
    }
    if (showTerminal) LinuxTerminalScreen(onBack = { showTerminal = false })
    }
}
