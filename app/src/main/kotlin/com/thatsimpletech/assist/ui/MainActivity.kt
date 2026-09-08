package com.thatsimpletech.assist.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import com.thatsimpletech.assist.BuildConfig
import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.config.ProviderSettings
import com.thatsimpletech.assist.config.RunPrefs
import com.thatsimpletech.assist.core.config.Endpoint
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.grammar.HintCodec
import com.thatsimpletech.assist.core.net.ProviderKey
import com.thatsimpletech.assist.core.policy.Conformance
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.profile.ModelProfiles
import com.thatsimpletech.assist.core.profile.ModelSuite
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.secrets.SecretStoreLockedException
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.notif.AssistNotificationListener
import com.thatsimpletech.assist.task.LastRun
import com.thatsimpletech.assist.task.TaskForegroundService
import java.io.File

/**
 * Settings surface: grants, EZER home (default), OpenRouter/LAN fallbacks, Auto, spend cap,
 * hint codec, family mode, a goal box, kill switch, policy self-check, offline suite.
 * Framework views only; a nicer surface is later work.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var model: EditText
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var spendCap: EditText
    private lateinit var family: CheckBox
    private lateinit var ezerBtn: Button
    private lateinit var cloudBtn: Button
    private lateinit var localBtn: Button
    private lateinit var a11yBtn: Button
    private lateinit var assistantBtn: Button
    private lateinit var notifBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var writeSettingsBtn: Button
    private lateinit var dndBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var numericBtn: Button
    private lateinit var lettersBtn: Button
    private lateinit var setupHint: TextView
    private var mode: ProviderSettings.Mode = ProviderSettings.Mode.EZER
    private var codec: String = HintCodec.Numeric.word

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "EZER ${BuildConfig.VERSION_NAME}"
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        status = TextView(this)
        col.addView(status)

        a11yBtn = button("Accessibility settings (screen driver)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        assistantBtn = button("Default assistant") { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        notifBtn = button("Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        overlayBtn = button("Draw over other apps (so EZER can open WhatsApp)") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
        writeSettingsBtn = button("Write system settings (brightness)") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply { data = Uri.parse("package:$packageName") })
        }
        dndBtn = button("Do Not Disturb access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        col.addView(a11yBtn)
        col.addView(assistantBtn)
        col.addView(notifBtn)
        col.addView(overlayBtn)
        col.addView(writeSettingsBtn)
        col.addView(dndBtn)

        setupHint = TextView(this).apply {
            text = "Missing Android switches show above. EZER cannot turn those on for you."
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        col.addView(setupHint)

        autoBtn = button(autoLabel()) { RunPrefs.setAuto(this, !RunPrefs.auto(this)); render() }
        col.addView(autoBtn)

        col.addView(heading("Brain", dp))
        ezerBtn = button("EZER home") { setMode(ProviderSettings.Mode.EZER) }
        cloudBtn = button("OpenRouter") { setMode(ProviderSettings.Mode.CLOUD) }
        localBtn = button("Local / LAN") { setMode(ProviderSettings.Mode.LOCAL) }
        col.addView(ezerBtn)
        col.addView(cloudBtn)
        col.addView(localBtn)

        model = EditText(this).apply {
            hint = "Model id"
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        col.addView(model)
        url = EditText(this).apply {
            hint = "Base URL (OpenAI-compatible …/v1)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        col.addView(url)
        key = EditText(this).apply {
            hint = "API key (required for OpenRouter, optional otherwise)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        col.addView(key)
        spendCap = EditText(this).apply {
            hint = "Spend cap USD (empty = none)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        col.addView(spendCap)

        col.addView(heading("Hints", dp))
        numericBtn = button(HintCodec.Numeric.word) { setCodec(HintCodec.Numeric.word) }
        lettersBtn = button(HintCodec.Letters.word) { setCodec(HintCodec.Letters.word) }
        col.addView(numericBtn)
        col.addView(lettersBtn)

        family = CheckBox(this).apply { text = "Family mode (block Chinese cloud hosts)" }
        col.addView(family)

        col.addView(button("Save brain") { saveProvider() })
        col.addView(button("Forget key") { Graph.secrets.delete(Graph.secretAccount()); render() })

        val saved = ProviderSettings.load(this)
        mode = saved.mode
        codec = saved.codec().word
        model.setText(saved.model)
        url.setText(saved.baseUrl)
        spendCap.setText(saved.spendCapUsd?.toString() ?: "")
        family.isChecked = saved.familyMode
        setMode(saved.mode)
        setCodec(saved.codec().word)

        val goal = EditText(this).apply { hint = "What should I do? (e.g. reply to Maria confirming 7pm)" }
        col.addView(goal)
        col.addView(button("Run task") {
            val g = goal.text.toString().trim()
            if (g.isEmpty()) {
                status.append("\nno goal")
                return@button
            }
            if (AssistAccessibilityService.instance == null) {
                status.append("\nScreen driver is off — intents (call, torch, alarm) still run; tap/type need Accessibility.")
            }
            try {
                startForegroundService(Intent(this, TaskForegroundService::class.java).putExtra(TaskForegroundService.EXTRA_GOAL, g))
                LastRun.save(this, "started: $g")
                status.append("\nstarted: $g — watch the EZER notification")
            } catch (e: Exception) {
                LastRun.save(this, "could not start: ${e.message}")
                status.append("\ncould not start: ${e.message}")
            }
        })
        col.addView(button("Stop everything (kill switch)") { GlobalKillSwitch.kill(); render() })
        col.addView(button("Re-arm after stop") { GlobalKillSwitch.reset(); render() })
        col.addView(button("Policy self-check") {
            val results = Conformance.run(PolicyEnforcer(Graph.pack), Conformance.loadDefault())
            val failed = results.filter { !it.passed }
            status.append("\nPolicy pack v${Graph.pack.version}: ${results.size - failed.size}/${results.size} cases pass" +
                if (failed.isEmpty()) "" else "\n" + failed.joinToString("\n") { "  ${it.case.name}: got ${it.tier}/${it.rule}" })
        })
        col.addView(button("Run offline suite") {
            val report = ModelSuite.runOffline()
            ModelProfiles.upsert(Graph.profilesFile, report.profile)
            status.append("\n${report.summary}")
        })
        col.addView(button("Share what it did (CSV)") {
            shareCsv("ezer-actions.csv", Graph.audit.exportActionsCsv())
        })
        col.addView(button("Share spend (CSV)") {
            shareCsv("ezer-spend.csv", Graph.audit.exportModelCallsCsv())
        })

        setContentView(ScrollView(this).apply { addView(col) })

        val needed = arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun setMode(next: ProviderSettings.Mode) {
        mode = next
        ezerBtn.alpha = if (next == ProviderSettings.Mode.EZER) 1f else 0.45f
        cloudBtn.alpha = if (next == ProviderSettings.Mode.CLOUD) 1f else 0.45f
        localBtn.alpha = if (next == ProviderSettings.Mode.LOCAL) 1f else 0.45f
        url.visibility = if (next == ProviderSettings.Mode.CLOUD) View.GONE else View.VISIBLE
        model.hint = when (next) {
            ProviderSettings.Mode.EZER -> "EZER model (e.g. ezer-chat)"
            ProviderSettings.Mode.CLOUD -> "OpenRouter model (e.g. moonshotai/kimi-k3)"
            ProviderSettings.Mode.LOCAL -> "Local model name (e.g. llama3.1)"
        }
        url.hint = when (next) {
            ProviderSettings.Mode.EZER -> "EZER URL (e.g. https://llm.ezer-server.ts.net/v1)"
            ProviderSettings.Mode.LOCAL -> "Ollama/vLLM URL (e.g. http://192.168.1.10:11434/v1)"
            else -> url.hint
        }
        key.hint = when (next) {
            ProviderSettings.Mode.CLOUD -> "OpenRouter key (required)"
            ProviderSettings.Mode.EZER -> "EZER / LiteLLM key (optional)"
            ProviderSettings.Mode.LOCAL -> "API key (optional)"
        }
    }

    private fun setCodec(next: String) {
        codec = HintCodec.parse(next).word
        numericBtn.alpha = if (codec == HintCodec.Numeric.word) 1f else 0.45f
        lettersBtn.alpha = if (codec == HintCodec.Letters.word) 1f else 0.45f
    }

    private fun saveProvider() {
        val capRaw = spendCap.text.toString().trim()
        val cap = if (capRaw.isEmpty()) {
            null
        } else {
            capRaw.toDoubleOrNull() ?: run {
                status.append("\nspend cap must be a number (leave empty for none)")
                return
            }
        }
        val next = ProviderSettings(
            mode = mode,
            model = model.text.toString(),
            baseUrl = url.text.toString(),
            familyMode = family.isChecked,
            spendCapUsd = cap,
            hintCodec = codec,
        )
        val problem = next.problem()
        if (problem != null) {
            status.append("\n$problem")
            return
        }
        ProviderSettings.save(this, next)
        Graph.reloadProvider()
        val v = ProviderKey.sanitize(
            key.text.toString(),
            stripSkPrefix = next.mode == ProviderSettings.Mode.EZER,
        )
        if (v.isNotBlank()) {
            try {
                val account = SecretStore.account(next.credentialId ?: Graph.CREDENTIAL_ID)
                Graph.secrets.set(account, v)
            } catch (e: SecretStoreLockedException) {
                status.append("\nKeystore is locked; unlock the phone and try again.")
                return
            }
            key.setText("")
        }
        render()
    }

    private fun render() {
        val a11y = AssistAccessibilityService.instance != null
        val notif = AssistNotificationListener.instance != null
        val assistant = Settings.Secure.getString(contentResolver, "assistant")?.startsWith(packageName) == true
        val hasKey = try {
            Graph.secrets.get(Graph.secretAccount()) != null
        } catch (e: SecretStoreLockedException) {
            false
        }
        val settings = Graph.provider
        val brain = Graph.config.tier(TierName.BRAIN)
        val host = Endpoint.host(brain.baseUrl) ?: brain.baseUrl
        val overlayOk = Settings.canDrawOverlays(this)
        val writeOk = Settings.System.canWrite(this)
        val dndOk = getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        a11yBtn.visibility = if (a11y) View.GONE else View.VISIBLE
        assistantBtn.visibility = if (assistant) View.GONE else View.VISIBLE
        notifBtn.visibility = if (notif) View.GONE else View.VISIBLE
        overlayBtn.visibility = if (overlayOk) View.GONE else View.VISIBLE
        writeSettingsBtn.visibility = if (writeOk) View.GONE else View.VISIBLE
        dndBtn.visibility = if (dndOk) View.GONE else View.VISIBLE
        val missing = !a11y || !overlayOk
        setupHint.visibility = if (missing) View.VISIBLE else View.GONE
        autoBtn.text = autoLabel()
        title = "EZER ${BuildConfig.VERSION_NAME}"

        status.text = buildString {
            append("EZER ${BuildConfig.VERSION_NAME}\n\n")
            append(if (a11y) "✓ screen driver on\n" else "✗ screen driver off (intents still run: call, torch, alarm)\n")
            append(if (assistant) "✓ default assistant\n" else "✗ not the default assistant\n")
            append(if (notif) "✓ notification access\n" else "✗ notification access off\n")
            append(if (overlayOk) "✓ can open other apps\n" else "✗ draw-over-apps off (EZER cannot leave this screen)\n")
            append(if (writeOk) "✓ write settings\n" else "✗ write settings off (brightness)\n")
            append(if (dndOk) "✓ Do Not Disturb access\n" else "✗ Do Not Disturb access off\n")
            append(if (RunPrefs.auto(this@MainActivity)) "● Auto: Send and start-task cards are skipped\n" else "○ Auto off: EZER will ask before Send\n")
            append("${settings.label} · ${brain.slug} · $host\n")
            append(
                when {
                    hasKey -> "✓ API key in Keystore\n"
                    settings.requiresKey -> "✗ no API key (OpenRouter will refuse)\n"
                    else -> "○ no API key (ok for EZER / local)\n"
                },
            )
            append("hints: ${settings.codec().word}\n")
            if (settings.familyMode) append("family mode on\n")
            settings.spendCapUsd?.let { append("session pauses at \$$it\n") }
            append(if (GlobalKillSwitch.killed) "■ STOPPED by kill switch\n" else "● armed\n")
            val last = LastRun.load(this@MainActivity)
            if (last.isNotBlank()) append("\nLast run: $last\n")
        }
    }

    private fun shareCsv(fileName: String, csv: String) {
        val rows = csv.split("\r\n").count { it.isNotEmpty() } - 1
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, fileName)
        file.writeText(csv)
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, fileName)
                        putExtra(Intent.EXTRA_TEXT, csv)
                    },
                    fileName,
                ),
            )
            status.append("\n$fileName: $rows rows — pick Drive, Gmail, or Files")
        } catch (e: Exception) {
            status.append("\nsaved $fileName ($rows rows); no share app: ${e.message}")
        }
    }

    private fun autoLabel(): String =
        if (RunPrefs.auto(this)) "Auto mode: ON (skip approve cards)"
        else "Auto mode: OFF (ask before Send)"

    private fun heading(label: String, dp: Float): TextView = TextView(this).apply {
        text = label
        setTextSize(16f)
        setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"

        fun isDefaultAssistant(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "assistant")?.startsWith(context.packageName) == true
    }
}
