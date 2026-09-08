package com.thatsimpletech.assist

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.approval.AndroidApprovalSurface
import com.thatsimpletech.assist.approval.ApprovalNotifier
import com.thatsimpletech.assist.approval.OverlayCard
import com.thatsimpletech.assist.audit.AndroidSqlExecutor
import com.thatsimpletech.assist.core.audit.AuditStore
import com.thatsimpletech.assist.config.ProviderSettings
import com.thatsimpletech.assist.config.RunPrefs
import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.net.Endpoints
import com.thatsimpletech.assist.core.policy.PolicyPack
import com.thatsimpletech.assist.core.redact.Redactor
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.steering.DefaultInstructions
import com.thatsimpletech.assist.core.steering.InstructionStack
import com.thatsimpletech.assist.core.steering.RulesBoundary
import com.thatsimpletech.assist.secrets.KeystoreSecretStore
import java.io.File
import java.util.UUID

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

    /** Epoch seconds, the audit store's clock. */
    val clock: () -> Double = { System.currentTimeMillis() / 1000.0 }

    val pack: PolicyPack by lazy { PolicyPack.loadDefault() }

    /**
     * Active config: shipped YAML plus the settings-screen overlay (model, base URL, mode).
     * Reloaded when the person taps Save provider, so a running task keeps the old client.
     */
    val config: AssistConfig get() = _config
    val endpoints: Endpoints get() = _endpoints
    val provider: ProviderSettings get() = ProviderSettings.load(app)

    @Volatile private lateinit var _config: AssistConfig
    @Volatile private lateinit var _endpoints: Endpoints

    val secrets: SecretStore by lazy { KeystoreSecretStore(app) }
    val notifier: ApprovalNotifier by lazy { ApprovalNotifier(app) }
    val approvals: AndroidApprovalSurface by lazy {
        AndroidApprovalSurface(
            notifier = notifier,
            overlay = { AssistAccessibilityService.instance?.let { OverlayCard(it) } },
            timeoutSeconds = pack.approvalTimeoutSeconds,
            auto = { RunPrefs.auto(app) },
        )
    }

    val audit: AuditStore by lazy {
        val file = File(app.filesDir, "audit.db")
        val store = AuditStore(AndroidSqlExecutor(SQLiteDatabase.openOrCreateDatabase(file, null)), clock, Redactor::text)
        store.migrate()
        store
    }

    /**
     * Agent-unwritable rules dir: ASSISTANT.md, CHARTER.md, profiles/, policy.yaml (plan §6).
     * Seeded from the core jar on first run; the boundary refuses any agent write into it.
     */
    val rulesDir: File by lazy {
        val dir = File(app.filesDir, "rules").apply { mkdirs() }
        val assistant = File(dir, InstructionStack.DEVICE_FILE)
        // Sideload upgrades must replace the shipped copy; a stale first-run file was
        // telling the model to ask about the GOAL.
        assistant.writeText(DefaultInstructions.load())
        dir
    }
    val rulesBoundary: RulesBoundary by lazy { RulesBoundary(rulesDir.toPath()) }

    /** The resolved instruction stack for a run (device layer only until profiles exist). */
    fun instructions(): String {
        val layers = InstructionStack.resolve(rulesDir.toPath(), null, "")
        return layers.first { it.name == InstructionStack.DEVICE }.text.ifBlank { DefaultInstructions.load() }
    }

    /** A random id minted once; it only ever appears in this phone's own audit rows. */
    val deviceId: String by lazy {
        val prefs = app.getSharedPreferences("device", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    /** Provider credential account for Cloud-key mode. */
    const val CREDENTIAL_ID = "openrouter"

    /** Keystore account for the EZER home box. */
    const val EZER_CREDENTIAL_ID = AssistConfig.EZER_CREDENTIAL

    fun secretAccount(): String = SecretStore.account(provider.credentialId ?: CREDENTIAL_ID)

    fun init(application: Application) {
        app = application
        reloadProvider()
    }

    /** Rebuilds config and the outbound host gate from what is on the settings screen. */
    fun reloadProvider() {
        val next = provider.toConfig(AssistConfig.loadDefault())
        _config = next
        _endpoints = Endpoints(next.allowedHosts)
    }
}
