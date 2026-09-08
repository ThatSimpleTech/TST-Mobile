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
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.grammar.HintCodec
import com.thatsimpletech.assist.core.net.Endpoints
import com.thatsimpletech.assist.core.net.ProviderPolicy
import com.thatsimpletech.assist.core.policy.PolicyPack
import com.thatsimpletech.assist.core.profile.ModelProfiles
import com.thatsimpletech.assist.core.redact.Redactor
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.steering.DefaultInstructions
import com.thatsimpletech.assist.core.steering.InstructionStack
import com.thatsimpletech.assist.core.steering.RulesBoundary
import com.thatsimpletech.assist.secrets.KeystoreSecretStore
import com.thatsimpletech.assist.voice.OnDeviceSpeak
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
     * Active config: shipped YAML plus the settings-screen overlay (model, base URL, mode,
     * spend cap). Reloaded when the person taps Save provider, so a running task keeps the
     * old client.
     */
    val config: AssistConfig get() = _config
    val endpoints: Endpoints get() = _endpoints
    val provider: ProviderSettings get() = ProviderSettings.load(app)

    @Volatile private lateinit var _config: AssistConfig
    @Volatile private lateinit var _endpoints: Endpoints

    val secrets: SecretStore by lazy { KeystoreSecretStore(app) }
    val notifier: ApprovalNotifier by lazy { ApprovalNotifier(app) }
    val voice: OnDeviceSpeak by lazy { OnDeviceSpeak(app) }
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

    /** Per-endpoint suite stats. Not a rules file; the agent has no verb that writes it. */
    val profilesFile: File
        get() = File(app.filesDir, ModelProfiles.RELATIVE_PATH).also { it.parentFile?.mkdirs() }

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
        val profiles = File(dir, InstructionStack.PROFILES_DIR).apply { mkdirs() }
        val letters = File(profiles, "${InstructionStack.LETTERS_PROFILE}.md")
        if (!letters.exists()) letters.writeText(DefaultInstructions.lettersProfile())
        dir
    }
    val rulesBoundary: RulesBoundary by lazy { RulesBoundary(rulesDir.toPath()) }

    /**
     * Device instructions, plus the letters profile when that codec is on. [codec] is
     * captured at run start so a settings change cannot switch mid-task.
     */
    fun instructions(codec: HintCodec = provider.codec()): String {
        val profile = InstructionStack.LETTERS_PROFILE.takeIf { codec is HintCodec.Letters }
        val layers = InstructionStack.resolve(rulesDir.toPath(), profile, "")
        return InstructionStack.deviceAndProfile(layers, DefaultInstructions.load())
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
        val settings = provider
        val next = settings.toConfig(AssistConfig.loadDefault())
        _config = next
        val brain = next.tier(TierName.BRAIN).baseUrl
        val hosts = if (ProviderPolicy.hostAllowed(brain, settings.familyMode)) next.allowedHosts else emptySet()
        _endpoints = Endpoints(hosts)
    }
}
