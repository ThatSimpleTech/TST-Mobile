package com.thatsimpletech.assist.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.config.ProviderSettings
import com.thatsimpletech.assist.core.config.Endpoint
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.policy.Conformance
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.secrets.SecretStoreLockedException
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.notif.AssistNotificationListener
import com.thatsimpletech.assist.task.TaskForegroundService

/**
 * The whole settings surface for now: status of the three grants, EZER home (default),
 * OpenRouter and LAN fallbacks, a goal box, the kill switch, and the policy self-check.
 * Framework views only; a nicer surface is later work.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var model: EditText
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var ezerBtn: Button
    private lateinit var cloudBtn: Button
    private lateinit var localBtn: Button
    private var mode: ProviderSettings.Mode = ProviderSettings.Mode.EZER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        status = TextView(this)
        col.addView(status)

        col.addView(button("Accessibility settings (screen driver)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(button("Default assistant") { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) })
        col.addView(button("Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })

        col.addView(TextView(this).apply {
            text = "EZER drives this phone. First smoke: Accessibility on → Save EZER (Tailscale MagicDNS, model ezer-chat) → open WhatsApp → goal names WhatsApp → Run task. Approve type, then Send. OpenRouter is a fallback when the box is down. Stop is on the task notification and the Quick Settings tile."
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })

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
        col.addView(button("Save brain") { saveProvider() })
        col.addView(button("Forget key") { Graph.secrets.delete(Graph.secretAccount()); render() })

        val saved = ProviderSettings.load(this)
        mode = saved.mode
        model.setText(saved.model)
        url.setText(saved.baseUrl)
        setMode(saved.mode)

        val goal = EditText(this).apply { hint = "What should I do? (e.g. reply to Maria confirming 7pm)" }
        col.addView(goal)
        col.addView(button("Run task") {
            val g = goal.text.toString().trim()
            if (g.isNotEmpty()) {
                startForegroundService(Intent(this, TaskForegroundService::class.java).putExtra(TaskForegroundService.EXTRA_GOAL, g))
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

        setContentView(ScrollView(this).apply { addView(col) })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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

    private fun saveProvider() {
        val next = ProviderSettings(mode, model.text.toString(), url.text.toString())
        val problem = next.problem()
        if (problem != null) {
            status.append("\n$problem")
            return
        }
        ProviderSettings.save(this, next)
        Graph.reloadProvider()
        val v = key.text.toString()
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
        status.text = buildString {
            append("EZER\n\n")
            append(if (a11y) "✓ screen driver on\n" else "✗ screen driver off (no-accessibility mode: answers, notifications, intents)\n")
            append(if (assistant) "✓ default assistant\n" else "✗ not the default assistant\n")
            append(if (notif) "✓ notification access\n" else "✗ notification access off\n")
            append("${settings.label} · ${brain.slug} · $host\n")
            append(
                when {
                    hasKey -> "✓ API key in Keystore\n"
                    settings.requiresKey -> "✗ no API key (OpenRouter will refuse)\n"
                    else -> "○ no API key (ok for EZER / local)\n"
                },
            )
            append(if (GlobalKillSwitch.killed) "■ STOPPED by kill switch\n" else "● armed\n")
        }
    }

    private fun heading(label: String, dp: Float): TextView = TextView(this).apply {
        text = label
        textSize = 16f
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
