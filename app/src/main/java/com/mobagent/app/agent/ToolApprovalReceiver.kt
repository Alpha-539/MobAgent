package com.mobagent.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tasdiq bildirishnomasidagi "Ruxsat berish" / "Rad etish" tugmalari shu yerga keladi. */
class ToolApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approve = intent.action == ACTION_APPROVE
        val accepted = ToolApprovalBus.submit(intent.getStringExtra(EXTRA_BLOCK_ID), approve)
        AgentTaskService.cancelApproval(context)
        // Buyruq endi kutilmayapti (ilova qayta ishga tushgan) — "ishlamoqda" bildirishnomasi ham qolib ketmasin.
        if (!accepted) AgentTaskService.stop(context)
    }

    companion object {
        const val ACTION_APPROVE = "com.mobagent.app.action.TOOL_APPROVE"
        const val ACTION_DENY = "com.mobagent.app.action.TOOL_DENY"
        const val EXTRA_BLOCK_ID = "block_id"
    }
}
