package com.thatsimpletech.assist.core.policy

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.Direction
import com.thatsimpletech.assist.core.grammar.SwipeTarget
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
    /** The app the action lands in: the screen's app, or the poster for notification verbs. */
    val app: String,
    @SerialName("goal_apps") val goalApps: List<String> = emptyList(),
    val keyguard: Boolean = false,
    val secure: Boolean = false,
    @SerialName("open_label") val openLabel: String? = null,
    val target: CaseTarget? = null,
)

@Serializable
data class CaseExpect(val tier: Tier, val rule: String)

data class CaseResult(val case: Case, val tier: Tier, val rule: String) {
    val passed: Boolean get() = tier == case.expect.tier && rule == case.expect.rule
}

/**
 * Runs the case file through an enforcer, building a real [Action], target node and task
 * context for each case so the enforcer's own fact derivation is what gets tested. The
 * Android app can run this at startup as a self-check.
 */
object Conformance {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    fun parse(text: String): CaseFile = yaml.decodeFromString(CaseFile.serializer(), text)

    fun loadDefault(): CaseFile {
        val stream = Conformance::class.java.getResourceAsStream("/cases.yaml") ?: error("cases.yaml missing from the core jar")
        return stream.use { parse(it.readBytes().decodeToString()) }
    }

    fun run(enforcer: PolicyEnforcer, file: CaseFile): List<CaseResult> = file.cases.map { c ->
        val (tier, rule, _) = enforcer.classify(factsOf(enforcer, c.request))
        CaseResult(c, tier, rule)
    }

    /** The enforcer's facts for a case, derived exactly as they are for a live action. */
    fun factsOf(enforcer: PolicyEnforcer, r: CaseRequest): ActionFacts {
        val action = actionOf(r)
        val node = r.target?.let {
            UiNode(
                identity = "case", role = Role.entries.firstOrNull { e -> e.word == it.role } ?: Role.VIEW,
                bounds = Rect(0, 0, 1, 1), label = it.label, resourceId = it.id, password = it.password, clickable = true,
            )
        }
        val notif = r.verb == VerbKey.NOTIF_OPEN || r.verb == VerbKey.NOTIF_REPLY
        return enforcer.facts(
            action = action, screenApp = if (notif) "com.android.systemui" else r.app, target = node,
            keyguard = r.keyguard, secure = r.secure, task = TaskContext(goalApps = r.goalApps.toSet()),
            appOverride = if (notif) r.app else null,
        )
    }

    private fun actionOf(r: CaseRequest): Action = when (r.verb) {
        VerbKey.TAP -> Action.Tap(1)
        VerbKey.LONG -> Action.Long(1)
        VerbKey.TYPE -> Action.Type(1, "")
        VerbKey.CLEAR -> Action.Clear(1)
        VerbKey.SCROLL -> Action.Scroll(1, Direction.DOWN)
        VerbKey.SWIPE -> Action.Swipe(SwipeTarget.Screen, Direction.UP)
        VerbKey.DRAG -> Action.Drag(1, 2)
        VerbKey.BACK -> Action.Back
        VerbKey.HOME -> Action.Home
        VerbKey.RECENTS -> Action.Recents
        VerbKey.OPEN -> Action.Open(r.openLabel ?: "")
        VerbKey.NOTIF_LIST -> Action.NotifList
        VerbKey.NOTIF_REPLY -> Action.NotifReply("n1", "")
        VerbKey.NOTIF_OPEN -> Action.NotifOpen("n1")
        VerbKey.SCREEN_ASK -> Action.ScreenAsk("")
        VerbKey.WAIT -> Action.Wait(1)
        VerbKey.DONE -> Action.Done("")
        VerbKey.ASK -> Action.Ask("")
        VerbKey.MORE -> Action.More
        else -> throw IllegalArgumentException("case uses unknown verb '${r.verb}'")
    }
}
