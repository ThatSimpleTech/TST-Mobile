package com.thatsimpletech.assist.core.partner

import com.thatsimpletech.assist.core.intent.IntentSpec

/**
 * Partner-app ladder (Workstream F). App Function if published, else a documented
 * intent, else an honest miss. There is no tree-drive rung in M2.
 */
sealed class PartnerPath {
    data class AppFunction(val pkg: String, val functionId: String) : PartnerPath()
    data class Intent(val spec: IntentSpec) : PartnerPath()
    data class Missing(val reason: String) : PartnerPath()

    /** Recorded on [com.thatsimpletech.assist.core.loop.ExecResult.detail]. */
    val kind: String get() = when (this) {
        is AppFunction -> "app-function"
        is Intent -> "intent"
        is Missing -> "missing"
    }
}

object PartnerLadder {
    fun choose(
        partner: String,
        pkg: String,
        functionId: String?,
        functionAvailable: Boolean,
        spec: IntentSpec?,
    ): PartnerPath {
        if (functionId != null && functionAvailable) {
            return PartnerPath.AppFunction(pkg, functionId)
        }
        if (spec != null) return PartnerPath.Intent(spec)
        return PartnerPath.Missing(missingMessage(partner))
    }

    fun missingMessage(partner: String): String =
        "$partner App Function is unpublished and the intent was not accepted; UI driving is not in this version"
}
