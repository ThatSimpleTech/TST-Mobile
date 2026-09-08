package com.thatsimpletech.assist.core.policy

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Plan §4 verb tiers. The YAML words are silent / once / every / refused. */
@Serializable
enum class Tier {
    @SerialName("silent") SILENT,
    @SerialName("once") ONCE_PER_TASK,
    @SerialName("every") EVERY_TIME,
    @SerialName("refused") REFUSED,
}

@Serializable
data class AppEntry(
    @SerialName("package") val pkg: String,
    val label: String,
    val aliases: List<String> = emptyList(),
    @SerialName("sensitive_ids") val sensitiveIds: List<String> = emptyList(),
)

/**
 * A rule's conditions. Every non-null field must hold for the rule to match (AND).
 * Field names are the YAML keys; keep them boring so a second enforcer can copy them.
 */
@Serializable
data class When(
    val verb: List<String>? = null,
    val acts: Boolean? = null,
    @SerialName("app_scoped") val appScoped: Boolean? = null,
    val keyguard: Boolean? = null,
    val secure: Boolean? = null,
    @SerialName("target_password") val targetPassword: Boolean? = null,
    @SerialName("target_sensitive") val targetSensitive: Boolean? = null,
    @SerialName("app_allowlisted") val appAllowlisted: Boolean? = null,
    @SerialName("open_allowlisted") val openAllowlisted: Boolean? = null,
    @SerialName("app_in_goal") val appInGoal: Boolean? = null,
    @SerialName("app_settings") val appSettings: Boolean? = null,
)

@Serializable
data class Rule(
    val id: String,
    val `when`: When,
    val tier: Tier,
    val reason: String = "",
)

@Serializable
data class PolicyPack(
    val version: Int,
    @SerialName("approval_timeout_seconds") val approvalTimeoutSeconds: Int = 45,
    @SerialName("max_tier2_per_turn") val maxTier2PerTurn: Int = 1,
    @SerialName("step_budget") val stepBudget: Int = 12,
    @SerialName("loop_repeat_limit") val loopRepeatLimit: Int = 3,
    val apps: List<AppEntry> = emptyList(),
    @SerialName("settings_packages") val settingsPackages: List<String> = emptyList(),
    @SerialName("sensitive_labels") val sensitiveLabels: List<String> = emptyList(),
    val rules: List<Rule>,
    val default: Tier = Tier.REFUSED,
) {
    val packages: Set<String> by lazy { apps.mapTo(HashSet()) { it.pkg } }

    fun app(pkg: String): AppEntry? = apps.firstOrNull { it.pkg == pkg }

    /** Resolve an `open "label"` to an allowlisted app, by label or alias, any case, any accents. */
    fun resolveOpen(label: String): AppEntry? {
        val key = TextMatch.fold(label)
        return apps.firstOrNull { a -> TextMatch.fold(a.label) == key || a.aliases.any { TextMatch.fold(it) == key } }
    }

    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (version < 1) problems += "version must be >= 1"
        if (rules.isEmpty()) problems += "rules must not be empty"
        val ids = HashSet<String>()
        for (r in rules) {
            if (!ids.add(r.id)) problems += "duplicate rule id '${r.id}'"
            val unknown = r.`when`.verb.orEmpty().filter { it !in VerbKey.ALL }
            if (unknown.isNotEmpty()) problems += "rule '${r.id}' names unknown verbs $unknown"
        }
        val pkgs = HashSet<String>()
        for (a in apps) if (!pkgs.add(a.pkg)) problems += "duplicate app package '${a.pkg}'"
        if (maxTier2PerTurn < 1) problems += "max_tier2_per_turn must be >= 1"
        if (stepBudget < 1) problems += "step_budget must be >= 1"
        return problems
    }

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

        fun parse(text: String): PolicyPack {
            val pack = yaml.decodeFromString(serializer(), text)
            val problems = pack.validate()
            require(problems.isEmpty()) { "policy pack invalid: ${problems.joinToString("; ")}" }
            return pack
        }

        /** The pack shipped in the core jar (repo policy/policy.yaml). */
        fun loadDefault(): PolicyPack {
            val stream = PolicyPack::class.java.getResourceAsStream("/policy.yaml")
                ?: error("policy.yaml missing from the core jar")
            return stream.use { parse(it.readBytes().decodeToString()) }
        }
    }
}

/** The verb keys used in policy.yaml. Sub-verbs of notif and screen are their own keys. */
object VerbKey {
    const val TAP = "tap"
    const val LONG = "long"
    const val TYPE = "type"
    const val CLEAR = "clear"
    const val SCROLL = "scroll"
    const val SWIPE = "swipe"
    const val DRAG = "drag"
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val OPEN = "open"
    const val NOTIF_LIST = "notif_list"
    const val NOTIF_REPLY = "notif_reply"
    const val NOTIF_OPEN = "notif_open"
    const val SCREEN_ASK = "screen_ask"
    const val WAIT = "wait"
    const val DONE = "done"
    const val ASK = "ask"
    const val MORE = "more"

    val ALL: Set<String> = setOf(
        TAP, LONG, TYPE, CLEAR, SCROLL, SWIPE, DRAG, BACK, HOME, RECENTS, OPEN,
        NOTIF_LIST, NOTIF_REPLY, NOTIF_OPEN, SCREEN_ASK, WAIT, DONE, ASK, MORE,
    )

    /** Verbs that change nothing on the device. Everything else "acts". */
    val PASSIVE: Set<String> = setOf(WAIT, DONE, ASK, MORE, NOTIF_LIST)

    /**
     * Verbs whose effect lands inside one app: these are what the allowlist and the intent
     * lock judge. Global navigation (back, home, recents) is the way out of any app and is
     * never app-scoped. Notification open and reply land in the app that posted the
     * notification, so they are judged against that app.
     */
    val APP_SCOPED: Set<String> = setOf(TAP, LONG, TYPE, CLEAR, SCROLL, SWIPE, DRAG, OPEN, SCREEN_ASK, NOTIF_OPEN, NOTIF_REPLY)
}

/** Case- and accent-insensitive text matching for labels and aliases. */
object TextMatch {
    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun fold(s: String): String =
        marks.replace(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD), "")
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")

    /** Whole-word containment: "Enviar ahora" contains "enviar"; "Sender" does not contain "send". */
    fun containsWord(haystack: String, word: String): Boolean {
        val w = fold(word)
        if (w.isEmpty()) return false
        return fold(haystack).split(nonWord).any { it == w }
    }
}
