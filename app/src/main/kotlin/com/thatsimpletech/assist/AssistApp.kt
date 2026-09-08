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
     * Agent-unwritable rules dir (plan §6): ASSISTANT.md, profiles/, policy.yaml, and the
     * person's config.yaml. Seeded from the core jar on first run. The boundary's root is the
     * whole files dir, with this subtree refused, the way the desktop refuses .tst/rules.
     */
    val rulesDir: File by lazy {
        val dir = File(app.filesDir, "rules").apply { mkdirs() }
        val assistant = File(dir, InstructionStack.DEVICE_FILE)
        if (!assistant.exists()) assistant.writeText(DefaultInstructions.load())
        dir
    }
    val rulesBoundary: RulesBoundary by lazy { RulesBoundary(app.filesDir.toPath()) }
    private val userConfigFile: File get() = File(rulesDir, "config.yaml")

    /** Why the user's config was not used, if it was not; shown on the settings screen. */
    @Volatile
    var configProblem: String? = null
        private set

    /** The person's config when it parses, else the shipped presets. Replaced by [saveConfig]. */
    @Volatile
    var config: AssistConfig = AssistConfig.loadDefault()
        private set

    /** The one place any outbound connection is made (plan §1, no telemetry), for the current config. */
    private var endpointsFor: Pair<AssistConfig, Endpoints>? = null

    @Synchronized
    fun endpoints(): Endpoints {
        val current = config
        endpointsFor?.let { (cfg, ep) -> if (cfg === current) return ep }
        return Endpoints(current.allowedHosts).also { endpointsFor = current to it }
    }

    @Synchronized
    fun reloadConfig() {
        val f = userConfigFile
        if (!f.exists()) {
            config = AssistConfig.loadDefault()
            configProblem = null
            return
        }
        try {
            config = AssistConfig.parse(f.readText())
            configProblem = null
        } catch (e: Exception) {
            config = AssistConfig.loadDefault()
            configProblem = Redactor.throwableMessage(e)
        }
    }

    /** Validates, writes the person's config.yaml, and makes it current. */
    @Synchronized
    fun saveConfig(cfg: AssistConfig) {
        val problems = cfg.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        userConfigFile.writeText(cfg.toYaml())
        config = cfg
        configProblem = null
    }

    /** Back to the shipped presets: the person's file goes away. */
    @Synchronized
    fun resetConfig() {
        userConfigFile.delete()
        reloadConfig()
    }

    val secrets: SecretStore by lazy { KeystoreSecretStore(app) }
    val notifier: ApprovalNotifier by lazy { ApprovalNotifier(app) }
    val approvals: AndroidApprovalSurface by lazy {
        AndroidApprovalSurface(
            notifier = notifier,
            overlay = { AssistAccessibilityService.instance?.let { OverlayCard(it) } },
            timeoutSeconds = pack.approvalTimeoutSeconds,
        )
    }

    val audit: AuditStore by lazy {
        val file = File(app.filesDir, "audit.db")
        val store = AuditStore(AndroidSqlExecutor(SQLiteDatabase.openOrCreateDatabase(file, null)), clock, Redactor::text)
        store.migrate()
        store
    }

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

    /** Provider credential account for Cloud-key mode (the implicit desktop credential). */
    const val CREDENTIAL_ID = "openrouter"

    /** Credential id for a home box that wants a key (a LiteLLM key, say). */
    const val HOME_CREDENTIAL_ID = "home"

    fun init(application: Application) {
        app = application
        reloadConfig()
    }
}
