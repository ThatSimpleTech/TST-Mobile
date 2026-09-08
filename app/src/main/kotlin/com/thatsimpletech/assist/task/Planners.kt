package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.config.ProviderSettings
import com.thatsimpletech.assist.core.config.EndpointKind
import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.loop.CompositeEndStateValidator
import com.thatsimpletech.assist.core.loop.EndStateValidator
import com.thatsimpletech.assist.core.loop.ModelEndStateValidator
import com.thatsimpletech.assist.core.loop.Planner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.net.ProviderKey
import com.thatsimpletech.assist.core.net.ProviderPolicy

import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.secrets.SecretStoreLockedException

/** Picks the brain for the configured preset (plan §3 planner modes). Says plainly when it cannot. */
object Planners {
    data class Choice(val planner: Planner?, val mode: String, val reason: String)

    fun forConfig(meter: CostTracker): Choice {
        val config = Graph.config
        val tier = config.tier(TierName.BRAIN)
        return when (tier.kind) {
            EndpointKind.ON_DEVICE ->
                Choice(null, "device", "Device mode (on-device model) is not built yet; pick a cloud or home preset")
            EndpointKind.ON_BOX, EndpointKind.TAILNET, EndpointKind.REMOTE -> {
                ProviderPolicy.familyBlockReason(tier.baseUrl, Graph.provider.familyMode)?.let { reason ->
                    return Choice(null, mode(tier.kind), reason)
                }
                val slug = tier.slug ?: return Choice(null, mode(tier.kind), "the brain tier has no model slug; set one in config")
                val credentialId = tier.credentialId
                val key = if (credentialId == null) {
                    null
                } else {
                    try {
                        Graph.secrets.get(SecretStore.account(credentialId))
                            ?.let { ProviderKey.sanitize(it, stripSkPrefix = Graph.provider.mode == ProviderSettings.Mode.EZER) }
                            ?.ifBlank { null }
                    } catch (e: SecretStoreLockedException) {
                        return Choice(null, mode(tier.kind), "the key store is locked; unlock the phone")
                    }
                }
                if (key == null && Graph.provider.requiresKey) {
                    return Choice(null, mode(tier.kind), "no provider key stored for '$credentialId'; add it in the app")
                }
                val client = ProviderClient(Graph.endpoints, tier.baseUrl, key, slug)
                Choice(CloudPlanner(client, meter, tier, TierName.BRAIN, Graph.pack), mode(tier.kind), "")
            }
        }
    }

    /**
     * Deterministic end-state gate always; a validator-tier model call only when that
     * client can be built. Missing key / device mode / locked store → deterministic only.
     */
    fun endStateValidator(meter: CostTracker): EndStateValidator =
        CompositeEndStateValidator(model = modelValidator(meter))

    private fun modelValidator(meter: CostTracker): ModelEndStateValidator? {
        val tier = Graph.config.tier(TierName.VALIDATOR)
        val client = clientFor(tier) ?: return null
        return ModelEndStateValidator(client, meter, tier)
    }

    /** Optional: null when the tier cannot open a socket or has no slug/key. */
    private fun clientFor(tier: TierConfig): ProviderClient? {
        if (tier.kind == EndpointKind.ON_DEVICE) return null
        val slug = tier.slug ?: return null
        val credentialId = tier.credentialId
        val key = if (credentialId == null) {
            null
        } else {
            try {
                Graph.secrets.get(SecretStore.account(credentialId))
            } catch (_: SecretStoreLockedException) {
                return null
            }
        }
        if (key == null && Graph.provider.requiresKey) return null
        return ProviderClient(Graph.endpoints, tier.baseUrl, key, slug)
    }

    private fun mode(kind: EndpointKind) = when (kind) {
        EndpointKind.ON_DEVICE -> "device"
        else -> when (Graph.provider.mode) {
            ProviderSettings.Mode.EZER -> "ezer"
            ProviderSettings.Mode.CLOUD -> "cloud-key"
            ProviderSettings.Mode.LOCAL -> "local"
        }
    }
}
