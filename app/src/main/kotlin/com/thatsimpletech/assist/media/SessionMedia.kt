package com.thatsimpletech.assist.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.core.media.MediaCommand
import com.thatsimpletech.assist.notif.AssistNotificationListener

/**
 * Transport controls on the active media session (Workstream G). Uses the
 * notification-listener component name only; [AssistNotificationListener] stays
 * the notif-reply path. Spotify grammar still goes through PartnerRouter.
 */
class SessionMedia(private val context: Context) {
    fun execute(command: MediaCommand): ExecResult {
        val msm = context.getSystemService(MediaSessionManager::class.java)
            ?: return ExecResult.error(NO_SESSION)
        val listener = ComponentName(context, AssistNotificationListener::class.java)
        val sessions = try {
            msm.getActiveSessions(listener)
        } catch (_: SecurityException) {
            return ExecResult.error(NO_SESSION)
        } catch (_: Exception) {
            return ExecResult.error(NO_SESSION)
        }
        val controller = sessions.firstOrNull() ?: return ExecResult.error(NO_SESSION)
        val controls = controller.transportControls
        return try {
            when (command) {
                MediaCommand.Play -> controls.play()
                MediaCommand.Pause -> controls.pause()
                MediaCommand.Next -> controls.skipToNext()
                MediaCommand.Prev -> controls.skipToPrevious()
            }
            ExecResult.OK
        } catch (_: Exception) {
            ExecResult.error(NO_SESSION)
        }
    }

    companion object {
        const val NO_SESSION = "no media session; play something first or use Spotify"
    }
}
