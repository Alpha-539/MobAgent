package com.mobagent.app.history

import com.mobagent.app.i18n.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CmdStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

@Serializable
enum class DiffState { PENDING, ACCEPTED, REVERTED }

@Serializable
enum class DiffKind { ADDED, REMOVED, CONTEXT, GAP }

/** Diff'dagi bitta qator: qo'shilgan (yashil), o'chirilgan (qizil) yoki kontekst. */
@Serializable
data class DiffLine(
    val kind: DiffKind,
    val text: String,
    val oldNo: Int? = null,
    val newNo: Int? = null
)

/**
 * Bitta AI javobi (turn) ichidagi bo'laklar. Ilgari xabar, buyruq dialogi va
 * terminal natijasi ALOHIDA pufakchalar edi; endi hammasi bitta javob ichida.
 */
@Serializable
sealed class Block {
    @Serializable @SerialName("text")
    data class Text(val text: String) : Block()

    @Serializable @SerialName("command")
    data class Command(
        val id: String,
        val cmd: String,
        val status: CmdStatus = CmdStatus.PENDING,
        val output: String = "",
        val exitCode: Int? = null,
        val step: Int = 1
    ) : Block()

    @Serializable @SerialName("diff")
    data class Diff(
        val path: String,
        val lines: List<DiffLine>,
        val added: Int,
        val removed: Int,
        val isNewFile: Boolean = false,
        // Eski nusxa (bekor qilish uchun) diskda diff_backups/<id>.old da saqlanadi.
        val id: String = "",
        val state: DiffState = DiffState.PENDING
    ) : Block()
}

@Serializable
data class StoredMessage(
    val fromUser: Boolean,
    val text: String,
    val isTerminal: Boolean = false,
    // Bo'sh bo'lsa — eski (blokssiz) xabar, faqat `text` ishlatiladi.
    val blocks: List<Block> = emptyList(),
    // Xabarga biriktirilgan rasmlar (ImageStore'dagi fayl nomlari). Faqat onlayn chatda.
    val images: List<String> = emptyList()
)

/** Bitta suhbat: ro'yxatdagi kartochka + ichidagi barcha xabarlar. */
@Serializable
data class ChatSession(
    val id: String,
    val modelName: String,
    val modelPath: String?,
    val modelId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
    // Foydalanuvchi qo'lda bergan nom (bo'sh bo'lsa, oxirgi xabar ko'rsatiladi).
    val title: String? = null
) {
    /** Tarix panelida ko'rsatiladigan nom. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: preview
    /** Ro'yxat ekranida ko'rsatiladigan qisqa oldindan ko'rish matni. */
    val preview: String
        get() = messages.lastOrNull { it.fromUser }?.let { m ->
            m.text.ifBlank { if (m.images.isNotEmpty()) tr("Rasm") else "" }
        }?.takeIf { it.isNotBlank() }
            ?: messages.lastOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: tr("Bo'sh suhbat")
}
