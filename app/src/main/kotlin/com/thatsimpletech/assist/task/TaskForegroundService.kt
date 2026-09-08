package com.thatsimpletech.assist.task

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.approval.ApprovalNotifier
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one task as a foreground service so the OS keeps it alive, and owns the persistent
 * notification: goal, spend chip, Stop. The kill switch cancels the job; the runner also polls
 * it before every step, so a stop never has to wait for a coroutine to notice.
 */
class TaskForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val killListener: (Boolean) -> Unit = { killed -> if (killed) job?.cancel() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GlobalKillSwitch.addListener(killListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val goal = intent?.getStringExtra(EXTRA_GOAL)?.trim().orEmpty()
        if (goal.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            ApprovalNotifier.TASK_NOTIFICATION_ID,
            Graph.notifier.taskNotification(goal, "starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        job?.cancel()
        job = scope.launch {
            val outcome = try {
                TaskController.run(goal) { meter -> Graph.notifier.updateTask(goal, meter) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "stopped: ${e.message ?: "error"}"
            }
            Graph.notifier.updateTask(goal, outcome)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        GlobalKillSwitch.removeListener(killListener)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GOAL = "goal"
    }
}
