package com.mobagent.app.agent

import com.mobagent.app.engine.LlamaEngine
import com.mobagent.app.history.StoredMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * ILGARI: AI generatsiyasi to'g'ridan-to'g'ri ChatScreen'ning
 * rememberCoroutineScope() ichida ishlardi. Bu scope Compose "ekrani"ga
 * bog'liq — tizim Activity'ni qayta yaratsa yoki yo'q qilsa (ekranni
 * aylantirish, xotira tanqisligi, ba'zi OEM'larning fon ilovalarni
 * tozalash siyosati, yoki foydalanuvchi "so'nggi ilovalar" ro'yxatida
 * boshqa oynaga o'tishi), o'sha coroutine DARHOL bekor qilinardi.
 *
 * Muammo shundaki, llama_decode() — bloklovchi (sinxron) native
 * chaqiruv: Kotlin'ning "bekor qilish" signali uni TO'XTATA OLMAYDI.
 * Natijada: model hisoblashda davom etardi, lekin natijani kutib turgan
 * coroutine allaqachon o'lgan bo'lardi — javob HECH QACHON UI'ga
 * yetib bormasdi. Foydalanuvchiga bu "AI abadiy osilib qoldi" bo'lib
 * ko'rinardi (logcat buni aynan shunday tasdiqladi: "Generatsiya
 * boshlandi" dan keyin hech qanday keyingi log yo'q edi).
 *
 * Bu obyekt esa PROCESS darajasida yashaydi (Kotlin `object` — singleton).
 * Activity qayta yaratilsa ham, ChatScreen shu yergga qayta ulanadi va
 * joriy holatni (hatto oldin boshlangan javobning yarmini ham) yo'qotmasdan
 * ko'rsatishda davom etadi.
 */
object GenerationController {
    enum class Phase { IDLE, THINKING, TOOL_PENDING, DONE, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val sessionKey: String = "",
        val partialText: String = "",
        val toolCall: ToolCall? = null,
        val preamble: String = "",
        val chainStep: Int = 1,
        val finalText: String = "",
        val errorMessage: String? = null,
        val cancelled: Boolean = false,
        // Har voqea o'z id'siga ega — ChatScreen bitta DONE/ERROR/TOOL_PENDING
        // holatini ikki marta qayta ishlab qo'ymasligi uchun (masalan,
        // Activity qayta yaratilib, eski holat qayta kuzatilganda).
        val eventId: Long = 0L
    )

    val state = MutableStateFlow(State())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var eventCounter = 0L

    fun startThink(
        sessionKey: String,
        orchestrator: AgentOrchestrator,
        prompt: String,
        history: List<StoredMessage>,
        images: List<String> = emptyList()
    ) {
        job?.cancel()
        state.value = State(phase = Phase.THINKING, sessionKey = sessionKey, eventId = ++eventCounter)
        job = scope.launch {
            try {
                val step = orchestrator.think(prompt, history, { tok -> appendToken(tok) }, images)
                applyResult(step, chainStep = 1)
            } catch (e: Exception) {
                state.value = state.value.copy(
                    phase = Phase.ERROR,
                    errorMessage = e.message ?: e::class.simpleName,
                    eventId = ++eventCounter
                )
            }
        }
    }

    fun continueAfterTool(
        sessionKey: String,
        orchestrator: AgentOrchestrator,
        userPrompt: String,
        toolResult: String,
        history: List<StoredMessage>,
        nextChainStep: Int
    ) {
        job?.cancel()
        state.value = State(phase = Phase.THINKING, sessionKey = sessionKey, eventId = ++eventCounter)
        job = scope.launch {
            try {
                val step = orchestrator.continueAfterTool(userPrompt, toolResult, history) { tok -> appendToken(tok) }
                applyResult(step, chainStep = nextChainStep)
            } catch (e: Exception) {
                state.value = state.value.copy(
                    phase = Phase.ERROR,
                    errorMessage = e.message ?: e::class.simpleName,
                    eventId = ++eventCounter
                )
            }
        }
    }

    private fun appendToken(tok: String) {
        state.value = state.value.copy(partialText = state.value.partialText + tok)
    }

    private fun applyResult(step: AgentStep, chainStep: Int) {
        state.value = when (step) {
            is AgentStep.AssistantText -> state.value.copy(
                phase = Phase.DONE,
                finalText = step.text,
                eventId = ++eventCounter
            )
            is AgentStep.ToolRequest -> state.value.copy(
                phase = Phase.TOOL_PENDING,
                toolCall = step.call,
                preamble = step.preamble,
                chainStep = chainStep,
                eventId = ++eventCounter
            )
        }
    }

    /** "To'xtatish" tugmasi bosilganda. */
    fun cancel(engine: LlamaEngine) {
        engine.cancel()
        // Onlayn rejimda ochiq tarmoq ulanishini uzadi (oflayn rejimda ta'siri yo'q).
        com.mobagent.app.online.OnlineApi.cancel()
        state.value = state.value.copy(cancelled = true)
    }

    /** ChatScreen natijani UI ro'yxatiga (va diskka) qo'shib bo'lgach, holatni tozalaydi. */
    fun consume() {
        state.value = State()
    }
}
