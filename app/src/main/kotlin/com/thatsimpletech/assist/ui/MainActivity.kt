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
import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.EndpointKind
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.net.ChatMessage
import com.thatsimpletech.assist.core.net.ProviderClient
import com.thatsimpletech.assist.core.policy.Conformance
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.redact.Redactor
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.secrets.SecretStoreLockedException
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.notif.AssistNotificationListener
import com.thatsimpletech.assist.task.TaskForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole settings surface for now: status of the three grants, which brain is in use, the
 * provider key (Keystore only, never shown back), the home box, a goal box, the kill switch,
 * and the policy self-check. Framework views only; a nicer surface is later work.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            text = "First run: turn the screen driver on, pick a brain below, open the app the task is about, " +
                "write the goal naming that app, then Run task. Tier 1 asks once at the start; anything that sends, " +
                "calls, pays, deletes, installs or shares asks every time. Stop is on the task notification and the " +
                "Quick Settings tile. Default assistant is optional (voice is not built yet)."
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        })

        // ---- Cloud key ----
        col.addView(heading("Cloud brain (OpenRouter or any OpenAI-compatible host)"))
        val key = EditText(this).apply {
            hint = "Provider key (stored in Keystore, never shown again)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(key)
        col.addView(button("Save key and use the cloud preset") {
            val v = key.text.toString()
            if (v.isNotBlank()) {
                try {
                    Graph.secrets.set(SecretStore.account(Graph.CREDENTIAL_ID), v)
                    Graph.saveConfig(Graph.config.copy(preset = AssistConfig.DEFAULT_PRESET))
                    say("Cloud key saved; preset is ${AssistConfig.DEFAULT_PRESET}.")
                } catch (e: SecretStoreLockedException) {
                    say("Keystore is locked; unlock the phone and try again.")
                } catch (e: Exception) {
                    say("Could not save: ${Redactor.throwableMessage(e)}")
                }
                key.setText("")
            }
            render()
        })
        col.addView(button("Forget cloud key") { Graph.secrets.delete(SecretStore.account(Graph.CREDENTIAL_ID)); render() })

        // ---- Home box ----
        col.addView(heading("Home brain (your own box over Tailscale)"))
        col.addView(TextView(this).apply {
            text = "Use the box's MagicDNS name, e.g. https://llm.ezer-server.ts.net/v1 (LiteLLM) or " +
                "http://100.64.x.y:8000/v1 (vLLM). Plain http is allowed only inside the tailnet. " +
                "The key is optional: LiteLLM wants its own key, a bare vLLM does not."
        })
        val homeUrl = EditText(this).apply { hint = "Base URL"; setText(currentHomeUrl()) }
        val homeSlug = EditText(this).apply { hint = "Model name the box serves (e.g. ezer-chat)"; setText(currentHomeSlug()) }
        val homeKey = EditText(this).apply {
            hint = "Box key (optional, Keystore only)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(homeUrl)
        col.addView(homeSlug)
        col.addView(homeKey)
        col.addView(button("Save home box and use it") {
            val url = homeUrl.text.toString().trim()
            val slug = homeSlug.text.toString().trim()
            val k = homeKey.text.toString()
            try {
                val cred = if (k.isNotBlank()) Graph.HOME_CREDENTIAL_ID else null
                if (cred != null) Graph.secrets.set(SecretStore.account(cred), k)
                Graph.saveConfig(Graph.config.withHome(url, slug, cred))
                say("Home box saved; preset is home. Brain: $slug at ${Graph.config.resolveBaseUrl(Graph.config.tier(TierName.BRAIN))}")
            } catch (e: SecretStoreLockedException) {
                say("Keystore is locked; unlock the phone and try again.")
            } catch (e: Exception) {
                say("Not saved: ${Redactor.throwableMessage(e)}")
            }
            homeKey.setText("")
            render()
        })
        col.addView(button("Test the current brain (one tiny call)") { testBrain() })
        col.addView(button("Back to the shipped presets") { Graph.resetConfig(); render() })

        // ---- Task ----
        col.addView(heading("Task"))
        val goal = EditText(this).apply { hint = "What should I do? (e.g. reply to Maria on WhatsApp confirming 7pm)" }
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
            say("Policy pack v${Graph.pack.version}: ${results.size - failed.size}/${results.size} cases pass" +
                if (failed.isEmpty()) "" else "\n" + failed.joinToString("\n") { "  ${it.case.name}: got ${it.tier}/${it.rule}" })
        })

        log = TextView(this).apply { setPadding(0, (12 * dp).toInt(), 0, 0) }
        col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun currentHomeUrl(): String = Graph.config.presets[AssistConfig.HOME_PRESET]?.brain?.baseUrl ?: ""
    private fun currentHomeSlug(): String = Graph.config.presets[AssistConfig.HOME_PRESET]?.brain?.slug ?: ""

    /** One short call to the brain the config points at, off the main thread, with the result in the log. */
    private fun testBrain() {
        val cfg = Graph.config
        val tier = cfg.tier(TierName.BRAIN)
        if (tier.kind == EndpointKind.ON_DEVICE) {
            say("Device mode is not built yet.")
            return
        }
        val slug = tier.slug ?: run { say("The brain tier has no model name."); return }
        val key = try {
            tier.credentialId?.let { Graph.secrets.get(SecretStore.account(it)) }
        } catch (e: SecretStoreLockedException) {
            say("Keystore is locked."); return
        }
        if (tier.credentialId != null && key == null) {
            say("No key stored for '${tier.credentialId}'."); return
        }
        val url = cfg.resolveBaseUrl(tier)
        say("Calling $slug at $url ...")
        scope.launch {
            val started = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val r = ProviderClient(Graph.endpoints(), url, key, slug).chat(
                        listOf(ChatMessage("user", "Reply with the single word: ready")), maxTokens = 8, temperature = 0.0,
                    )
                    "ok in ${System.currentTimeMillis() - started} ms: \"${r.text.trim().take(40)}\" (${r.usage.promptTokens}+${r.usage.completionTokens} tokens)"
                } catch (e: Exception) {
                    "failed: ${Redactor.throwableMessage(e)}"
                }
            }
            say(outcome)
        }
    }

    private fun render() {
        val a11y = AssistAccessibilityService.instance != null
        val notif = AssistNotificationListener.instance != null
        val assistant = isDefaultAssistant(this)
        val cfg = Graph.config
        val tier = cfg.tier(TierName.BRAIN)
        val keyState = try {
            when (val id = tier.credentialId) {
                null -> "no key needed"
                else -> if (Graph.secrets.get(SecretStore.account(id)) != null) "key '$id' in Keystore" else "no key stored for '$id'"
            }
        } catch (e: SecretStoreLockedException) {
            "Keystore locked"
        }
        status.text = buildString {
            append("TST Assist\n\n")
            append(if (a11y) "✓ screen driver on\n" else "✗ screen driver off (no-accessibility mode: answers, notifications, intents)\n")
            append(if (assistant) "✓ default assistant\n" else "✗ not the default assistant (optional)\n")
            append(if (notif) "✓ notification access\n" else "✗ notification access off\n")
            append("● brain: preset ").append(cfg.preset).append(", ").append(tier.slug ?: "?").append(" at ")
                .append(cfg.resolveBaseUrl(tier)).append(" (").append(keyState).append(")\n")
            Graph.configProblem?.let { append("! your config.yaml was ignored: ").append(it).append('\n') }
            append(if (GlobalKillSwitch.killed) "■ STOPPED by kill switch\n" else "● armed\n")
        }
    }

    private fun say(text: String) {
        log.text = text
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
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
