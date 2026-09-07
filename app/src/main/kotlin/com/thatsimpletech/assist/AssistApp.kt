package com.thatsimpletech.assist

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.approval.AndroidApprovalSurface
import com.thatsimpletech.assist.approval.ApprovalNotifier
import com.thatsimpletech.assist.approval.OverlayCard
import com.thatsimpletech.assist.audit.AndroidSqlExecutor
import com.thatsimpletech.assist.core.policy.PolicyPack
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.secrets.KeystoreSecretStore
import java.io.File

class AssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/**
 * The app's object graph, by hand. Everything here is either core (pure) or a thin Android
 * adapter over it. No DI framework, no reflection, nothing to audit twice.
 */
object Graph {
    lateinit var app: Application
        private set

    val pack: PolicyPack by lazy { PolicyPack.loadDefault() }
    val secrets: SecretStore by lazy { KeystoreSecretStore(app) }
    val notifier: ApprovalNotifier by lazy { ApprovalNotifier(app) }
    val approvals: AndroidApprovalSurface by lazy {
        AndroidApprovalSurface(
            notifier = notifier,
            overlay = { AssistAccessibilityService.instance?.let { OverlayCard(it) } },
            timeoutSeconds = pack.approvalTimeoutSeconds,
        )
    }
    val auditExecutor: AndroidSqlExecutor by lazy {
        val file = File(app.filesDir, "audit.db")
        AndroidSqlExecutor(SQLiteDatabase.openOrCreateDatabase(file, null))
    }

    /** Provider credential account for Cloud-key mode. */
    const val CREDENTIAL_ID = "default"

    fun init(application: Application) {
        app = application
    }
}
