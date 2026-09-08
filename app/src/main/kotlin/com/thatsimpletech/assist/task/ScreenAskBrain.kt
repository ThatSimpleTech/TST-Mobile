package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.a11y.NodeExecutor
import com.thatsimpletech.assist.core.config.TierConfig
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.vision.VisionAsk
import kotlin.coroutines.cancellation.CancellationException

/** Captures a JPEG, then [VisionAsk] on the active brain. */
class ScreenAskBrain(
    private val capture: suspend () -> ByteArray,
    private val client: ProviderClient,
    private val meter: CostTracker,
    private val tier: TierConfig,
) : NodeExecutor.ScreenAsk {
    override suspend fun ask(question: String): ExecResult {
        val jpeg = try {
            capture()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExecResult.error(e.message ?: "screenshot failed")
        }
        return VisionAsk.run(client, meter, tier, question, jpeg)
    }
}
