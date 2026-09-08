package com.thatsimpletech.assist.core.policy

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Role
import com.thatsimpletech.assist.core.observe.UiNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** policy/cases.yaml: the shared conformance suite (plan §4, decision 4). */
@Serializable
data class CaseFile(val cases: List<Case>)

@Serializable
data class Case(val name: String, val request: CaseRequest, val expect: CaseExpect)

@Serializable
data class CaseTarget(val role: String, val label: String = "", val id: String? = null, val password: Boolean = false)

@Serializable
data class CaseRequest(
    val verb: String,
    val app: String,
    @SerialName("goal_apps") val goalApps: List<String> = emptyList(),
    val keyguard: Boolean = false,
    val secure: Boolean = false,
    @SerialName("open_label") val openLabel: String? = null,
    val target: CaseTarget? = null,
    @SerialName("on_qs") val onQs: Boolean = false,
)

@Serializable
data class CaseExpect(val tier: Tier, val rule: String)

data class CaseResult(val case: Case, val tier: Tier, val rule: String) {
    val passed: Boolean get() = tier == case.expect.tier && rule == case.expect.rule
}

/** Runs the case file through an enforcer. The Android app can run this at startup as a self-check. */
object Conformance {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    fun parse(text: String): CaseFile = yaml.decodeFromString(CaseFile.serializer(), text)

    fun loadDefault(): CaseFile {
        val stream = Conformance::class.java.getResourceAsStream("/cases.yaml") ?: error("cases.yaml missing from the core jar")
        return stream.use { parse(it.readBytes().decodeToString()) }
    }

    fun run(enforcer: PolicyEnforcer, file: CaseFile): List<CaseResult> = file.cases.map { c ->
        val facts = factsOf(enforcer, c.request)
        val (tier, rule, _) = enforcer.classify(facts)
        CaseResult(c, tier, rule)
    }

    /** Builds facts the same way the enforcer does for a live action, from the case's request. */
    fun factsOf(enforcer: PolicyEnforcer, r: CaseRequest): ActionFacts {
        require(r.verb in VerbKey.ALL) { "case uses unknown verb '${r.verb}'" }
        val pack = enforcer.pack
        val openTarget = if (r.verb == VerbKey.OPEN) r.openLabel?.let { pack.resolveOpen(it) } else null
        val app = openTarget?.pkg ?: r.app
        val appEntry = pack.app(app)
        val node = r.target?.let {
            UiNode(
                identity = "case", role = Role.entries.firstOrNull { e -> e.word == it.role } ?: Role.VIEW,
                bounds = Rect(0, 0, 1, 1), label = it.label, resourceId = it.id, password = it.password, clickable = true,
            )
        }
        return ActionFacts(
            verb = r.verb,
            app = app,
            acts = r.verb !in VerbKey.PASSIVE,
            appScoped = r.verb in VerbKey.APP_SCOPED,
            keyguard = r.keyguard,
            secure = r.secure,
            targetPassword = node?.password == true,
            targetSensitive = node != null && enforcer.isSensitive(node, appEntry),
            appAllowlisted = appEntry != null,
            openAllowlisted = r.verb != VerbKey.OPEN || openTarget != null,
            appInGoal = app in r.goalApps,
            appSettings = app in pack.settingsPackages,
            onQs = r.onQs,
        )
    }
}
