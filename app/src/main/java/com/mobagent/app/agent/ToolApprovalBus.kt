package com.mobagent.app.agent

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bildirishnomadagi "Ruxsat berish" / "Rad etish" tugmalari ([ToolApprovalReceiver]) bilan ChatScreen o'rtasidagi ko'prik.
 * Tasdiq kutayotgan buyruqning id'si [pendingBlockId]da turadi; qaror faqat shu id mos kelsa qabul qilinadi
 * (eskirgan bildirishnoma — masalan ilova qayta ishga tushgandan keyingi — hech narsani bajarmaydi).
 */
object ToolApprovalBus {
    data class Decision(val blockId: String, val approve: Boolean)

    @Volatile var pendingBlockId: String? = null
    val decisions = MutableStateFlow<Decision?>(null)

    /** @return qaror qabul qilinsa true; buyruq endi kutilmayotgan bo'lsa false. */
    fun submit(blockId: String?, approve: Boolean): Boolean {
        if (blockId == null || blockId != pendingBlockId) return false
        decisions.value = Decision(blockId, approve)
        return true
    }
}
