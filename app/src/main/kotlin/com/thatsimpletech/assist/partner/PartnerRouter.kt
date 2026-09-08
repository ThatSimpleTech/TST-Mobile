package com.thatsimpletech.assist.partner

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.intent.IntentSpec
import com.thatsimpletech.assist.core.intent.PartnerIntents
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.core.media.SpotifyCommand
import com.thatsimpletech.assist.core.partner.PartnerLadder
import com.thatsimpletech.assist.core.partner.PartnerPath
import com.thatsimpletech.assist.intent.IntentExecutor

/**
 * WhatsApp / Spotify / Gmail: App Function if published, else documented intent,
 * else an honest error. Never falls through to `tap`/`type` (D6, M6).
 */
class PartnerRouter(
    private val functions: AppFunctionExecutor,
    private val intents: IntentExecutor,
) {
    suspend fun execute(action: Action): ExecResult {
        val request = requestOf(action) ?: return ExecResult.error("not a partner verb")
        val available = request.functionId != null && functions.available(request.pkg, request.functionId)
        val path = PartnerLadder.choose(
            partner = request.partner,
            pkg = request.pkg,
            functionId = request.functionId,
            functionAvailable = available,
            spec = request.spec,
        )
        return when (path) {
            is PartnerPath.AppFunction -> {
                val r = functions.invoke(path.pkg, path.functionId)
                if (r.ok) ExecResult(true, path.kind) else r
            }
            is PartnerPath.Intent -> {
                val r = intents.start(path.spec)
                if (r.ok) ExecResult(true, path.kind)
                else ExecResult.error(PartnerLadder.missingMessage(request.partner))
            }
            is PartnerPath.Missing -> ExecResult.error(path.reason)
        }
    }

    private fun requestOf(action: Action): PartnerRequest? = when (action) {
        is Action.WhatsApp -> PartnerRequest(
            partner = "WhatsApp",
            pkg = PartnerIntents.PKG_WHATSAPP,
            functionId = PartnerFunctions.WHATSAPP_SEND,
            spec = PartnerIntents.whatsapp(action.to, action.body),
        )
        is Action.Spotify -> PartnerRequest(
            partner = "Spotify",
            pkg = PartnerIntents.PKG_SPOTIFY,
            functionId = when (action.command) {
                is SpotifyCommand.Play -> PartnerFunctions.SPOTIFY_PLAY
                SpotifyCommand.Pause -> PartnerFunctions.SPOTIFY_PAUSE
                SpotifyCommand.Next -> PartnerFunctions.SPOTIFY_NEXT
                SpotifyCommand.Prev -> PartnerFunctions.SPOTIFY_PREV
            },
            spec = PartnerIntents.spotifyCommand(action.command),
        )
        is Action.Gmail -> PartnerRequest(
            partner = "Gmail",
            pkg = PartnerIntents.PKG_GMAIL,
            functionId = PartnerFunctions.GMAIL_SEND,
            spec = PartnerIntents.gmailCompose(action.to, action.subject, action.body),
        )
        else -> null
    }

    private data class PartnerRequest(
        val partner: String,
        val pkg: String,
        val functionId: String?,
        val spec: IntentSpec,
    )
}
