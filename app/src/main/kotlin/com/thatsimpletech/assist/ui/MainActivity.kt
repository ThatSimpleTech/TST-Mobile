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
import com.thatsimpletech.assist.core.policy.Conformance
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.secrets.SecretStoreLockedException
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.notif.AssistNotificationListener
import com.thatsimpletech.assist.task.TaskForegroundService

/**
 * The whole settings surface for now: status of the three grants, the provider key (Keystore
 * only, never shown back), a goal box, the kill switch, and the policy self-check. Framework
 * views only; a nicer surface is later work.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView

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

        val key = EditText(this).apply {
            hint = "Provider key (stored in Keystore, never shown again)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(key)
        col.addView(button("Save key") {
            val v = key.text.toString()
            if (v.isNotBlank()) {
                try {
                    Graph.secrets.set(SecretStore.account(Graph.CREDENTIAL_ID), v)
                } catch (e: SecretStoreLockedException) {
                    status.append("\nKeystore is locked; unlock the phone and try again.")
                }
                key.setText("")
            }
            render()
        })
        col.addView(button("Forget key") { Graph.secrets.delete(SecretStore.account(Graph.CREDENTIAL_ID)); render() })

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

    private fun render() {
        val a11y = AssistAccessibilityService.instance != null
        val notif = AssistNotificationListener.instance != null
        val assistant = Settings.Secure.getString(contentResolver, "assistant")?.startsWith(packageName) == true
        val hasKey = try {
            Graph.secrets.get(SecretStore.account(Graph.CREDENTIAL_ID)) != null
        } catch (e: SecretStoreLockedException) {
            false
        }
        status.text = buildString {
            append("TST Assist\n\n")
            append(if (a11y) "✓ screen driver on\n" else "✗ screen driver off (no-accessibility mode: answers, notifications, intents)\n")
            append(if (assistant) "✓ default assistant\n" else "✗ not the default assistant\n")
            append(if (notif) "✓ notification access\n" else "✗ notification access off\n")
            append(if (hasKey) "✓ provider key in Keystore\n" else "✗ no provider key (cloud/home calls will refuse)\n")
            append(if (GlobalKillSwitch.killed) "■ STOPPED by kill switch\n" else "● armed\n")
        }
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
