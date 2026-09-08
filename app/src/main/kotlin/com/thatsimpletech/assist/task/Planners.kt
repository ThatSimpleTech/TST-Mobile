package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.config.ProviderSettings
import com.thatsimpletech.assist.core.config.EndpointKind
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.loop.Planner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.net.ProviderClient
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
                val slug = tier.slug ?: return Choice(null, mode(tier.kind), "the brain tier has no model slug; set one in config")
                val credentialId = tier.credentialId
                val key = if (credentialId == null) {
                    null
                } else {
                    try {
                        Graph.secrets.get(SecretStore.account(credentialId))
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

    private fun mode(kind: EndpointKind) = when (kind) {
        EndpointKind.ON_DEVICE -> "device"
        else -> when (Graph.provider.mode) {
            ProviderSettings.Mode.CLOUD -> "cloud-key"
            ProviderSettings.Mode.LOCAL -> "local"
            ProviderSettings.Mode.CUSTOM -> "custom"
        }
    }
}
