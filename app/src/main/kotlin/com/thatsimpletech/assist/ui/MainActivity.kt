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
import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.config.ProviderSettings
import com.thatsimpletech.assist.core.config.Endpoint
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.grammar.HintCodec
import com.thatsimpletech.assist.core.net.ProviderPolicy
import com.thatsimpletech.assist.core.policy.Conformance
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.secrets.SecretStoreLockedException
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.notif.AssistNotificationListener
import com.thatsimpletech.assist.task.TaskForegroundService

/**
 * The whole settings surface for now: status of the three grants, the provider (OpenRouter,
 * local/LAN, or a custom OpenAI-compatible server), spend cap, hint codec, family mode,
 * a goal box, the kill switch, and the policy self-check. Framework views only; a nicer
 * surface is later work.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var model: EditText
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var spendCap: EditText
    private lateinit var family: CheckBox
    private lateinit var cloudBtn: Button
    private lateinit var localBtn: Button
    private lateinit var customBtn: Button
    private lateinit var numericBtn: Button
    private lateinit var lettersBtn: Button
    private var mode: ProviderSettings.Mode = ProviderSettings.Mode.CLOUD
    private var codec: String = HintCodec.Numeric.word

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
        col.addView(button("Write system settings") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply { data = Uri.parse("package:$packageName") })
        })
        col.addView(button("Do Not Disturb access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })

        col.addView(TextView(this).apply {
            text = "First smoke: Accessibility on → pick a provider and model → Save provider → open WhatsApp → goal names WhatsApp → Run task. Approve type, then Send. Stop is on the task notification and the Quick Settings tile. Default assistant is optional (voice is not built)."
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })

        col.addView(heading("Provider", dp))
        val picker = ProviderPolicy.defaultPickerModes()
        cloudBtn = button(picker[0]) { setMode(ProviderSettings.Mode.CLOUD) }
        localBtn = button(picker[1]) { setMode(ProviderSettings.Mode.LOCAL) }
        customBtn = button(picker[2]) { setMode(ProviderSettings.Mode.CUSTOM) }
        col.addView(cloudBtn)
        col.addView(localBtn)
        col.addView(customBtn)

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

        col.addView(heading("Hint codec", dp))
        numericBtn = button(HintCodec.Numeric.word) { setCodec(HintCodec.Numeric.word) }
        lettersBtn = button(HintCodec.Letters.word) { setCodec(HintCodec.Letters.word) }
        col.addView(numericBtn)
        col.addView(lettersBtn)

        family = CheckBox(this).apply { text = "Family mode" }
        col.addView(family)

        col.addView(button("Save provider") { saveProvider() })
        col.addView(button("Forget key") { Graph.secrets.delete(SecretStore.account(Graph.CREDENTIAL_ID)); render() })

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
        cloudBtn.alpha = if (next == ProviderSettings.Mode.CLOUD) 1f else 0.45f
        localBtn.alpha = if (next == ProviderSettings.Mode.LOCAL) 1f else 0.45f
        customBtn.alpha = if (next == ProviderSettings.Mode.CUSTOM) 1f else 0.45f
        url.visibility = if (next == ProviderSettings.Mode.CLOUD) View.GONE else View.VISIBLE
        model.hint = when (next) {
            ProviderSettings.Mode.CLOUD -> "OpenRouter model (e.g. moonshotai/kimi-k3)"
            ProviderSettings.Mode.LOCAL -> "Local model name (e.g. llama3.1)"
            ProviderSettings.Mode.CUSTOM -> "Model id as your server lists it"
        }
        url.hint = when (next) {
            ProviderSettings.Mode.LOCAL -> "Ollama/vLLM URL (e.g. http://192.168.1.10:11434/v1)"
            ProviderSettings.Mode.CUSTOM -> "Your server URL (OpenAI-compatible …/v1)"
            else -> url.hint
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
        val v = key.text.toString()
        if (v.isNotBlank()) {
            try {
                Graph.secrets.set(SecretStore.account(Graph.CREDENTIAL_ID), v)
            } catch (e: SecretStoreLockedException) {
                status.append("\nKeystore is locked; unlock the phone and try again.")
                return
            }
            key.setText("")
        }
        ProviderSettings.save(this, next)
        Graph.reloadProvider()
        render()
    }

    private fun render() {
        val a11y = AssistAccessibilityService.instance != null
        val notif = AssistNotificationListener.instance != null
        val assistant = Settings.Secure.getString(contentResolver, "assistant")?.startsWith(packageName) == true
        val hasKey = try {
            Graph.secrets.get(SecretStore.account(Graph.CREDENTIAL_ID)) != null
        } catch (e: SecretStoreLockedException) {
            false
        }
        val settings = Graph.provider
        val brain = Graph.config.tier(TierName.BRAIN)
        val host = Endpoint.host(brain.baseUrl) ?: brain.baseUrl
        status.text = buildString {
            append("TST Assist\n\n")
            append(if (a11y) "✓ screen driver on\n" else "✗ screen driver off (no-accessibility mode: answers, notifications, intents)\n")
            append(if (assistant) "✓ default assistant\n" else "✗ not the default assistant\n")
            append(if (notif) "✓ notification access\n" else "✗ notification access off\n")
            append(if (Settings.System.canWrite(this@MainActivity)) "✓ write settings\n" else "✗ write settings off (brightness)\n")
            val dnd = getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
            append(if (dnd) "✓ Do Not Disturb access\n" else "✗ Do Not Disturb access off\n")
            append("${settings.label} · ${brain.slug} · $host\n")
            append(
                when {
                    hasKey -> "✓ API key in Keystore\n"
                    settings.requiresKey -> "✗ no API key (OpenRouter will refuse)\n"
                    else -> "○ no API key (ok for local/custom)\n"
                },
            )
            append("hints: ${settings.codec().word}\n")
            if (settings.familyMode) append("family mode on\n")
            settings.spendCapUsd?.let { append("session pauses at \$$it\n") }
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
