package com.thatsimpletech.assist.core.grammar

/**
 * The closed action grammar (plan §7). One action per turn, plain text, never JSON.
 * Every verb here has an executor on the phone; nothing else executes, ever.
 */
enum class Verb(val word: String) {
    TAP("tap"), LONG("long"), TYPE("type"), CLEAR("clear"), SCROLL("scroll"), SWIPE("swipe"),
    DRAG("drag"), BACK("back"), HOME("home"), RECENTS("recents"), OPEN("open"), NOTIF("notif"),
    SCREEN("screen"), WAIT("wait"), DONE("done"), ASK("ask"), MORE("more");

    companion object {
        private val byWord = entries.associateBy { it.word }
        fun of(word: String): Verb? = byWord[word.lowercase()]
    }
}

enum class Direction(val word: String) {
    UP("up"), DOWN("down"), LEFT("left"), RIGHT("right");

    companion object {
        private val byWord = entries.associateBy { it.word }
        fun of(word: String): Direction? = byWord[word.lowercase()]
    }
}

sealed interface SwipeTarget {
    data class Hint(val hint: Int) : SwipeTarget
    data object Screen : SwipeTarget
}

/** Quoting used everywhere an action is rendered back to text (trailers, audit rows, cards). */
object Quote {
    fun q(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}

/**
 * A parsed action. [hints] lists every hint the action touches so the loop can refuse
 * anything that is not on the current screen before the executor ever sees it.
 */
sealed class Action(val verb: Verb) {
    open val hints: List<Int> get() = emptyList()

    /** The canonical one-line grammar form. Parsing the output of render() yields an equal action. */
    abstract fun render(): String

    /** Plain words for the approval card and the notification, no grammar. */
    abstract fun plainWords(): String

    data class Tap(val hint: Int) : Action(Verb.TAP) {
        override val hints get() = listOf(hint)
        override fun render() = "tap $hint"
        override fun plainWords() = "Tap item $hint"
    }

    data class Long(val hint: Int) : Action(Verb.LONG) {
        override val hints get() = listOf(hint)
        override fun render() = "long $hint"
        override fun plainWords() = "Long-press item $hint"
    }

    data class Type(val hint: Int, val text: String) : Action(Verb.TYPE) {
        override val hints get() = listOf(hint)
        override fun render() = "type $hint ${Quote.q(text)}"
        override fun plainWords() = "Type into item $hint: ${Quote.q(text.take(80))}"
    }

    data class Clear(val hint: Int) : Action(Verb.CLEAR) {
        override val hints get() = listOf(hint)
        override fun render() = "clear $hint"
        override fun plainWords() = "Clear item $hint"
    }

    data class Scroll(val hint: Int, val direction: Direction) : Action(Verb.SCROLL) {
        override val hints get() = listOf(hint)
        override fun render() = "scroll $hint ${direction.word}"
        override fun plainWords() = "Scroll item $hint ${direction.word}"
    }

    data class Swipe(val target: SwipeTarget, val direction: Direction) : Action(Verb.SWIPE) {
        override val hints get() = (target as? SwipeTarget.Hint)?.let { listOf(it.hint) } ?: emptyList()
        override fun render() = when (target) {
            is SwipeTarget.Hint -> "swipe ${target.hint} ${direction.word}"
            SwipeTarget.Screen -> "swipe screen ${direction.word}"
        }
        override fun plainWords() = when (target) {
            is SwipeTarget.Hint -> "Swipe item ${target.hint} ${direction.word}"
            SwipeTarget.Screen -> "Swipe the screen ${direction.word}"
        }
    }

    data class Drag(val from: Int, val to: Int) : Action(Verb.DRAG) {
        override val hints get() = listOf(from, to)
        override fun render() = "drag $from to $to"
        override fun plainWords() = "Drag item $from onto item $to"
    }

    data object Back : Action(Verb.BACK) {
        override fun render() = "back"
        override fun plainWords() = "Go back"
    }

    data object Home : Action(Verb.HOME) {
        override fun render() = "home"
        override fun plainWords() = "Go to the home screen"
    }

    data object Recents : Action(Verb.RECENTS) {
        override fun render() = "recents"
        override fun plainWords() = "Open recent apps"
    }

    /** Launch by label from the allowlist. The label is looked up, never executed. */
    data class Open(val app: String) : Action(Verb.OPEN) {
        override fun render() = "open ${Quote.q(app)}"
        override fun plainWords() = "Open $app"
    }

    data object NotifList : Action(Verb.NOTIF) {
        override fun render() = "notif list"
        override fun plainWords() = "List notifications"
    }

    data class NotifReply(val id: String, val text: String) : Action(Verb.NOTIF) {
        override fun render() = "notif reply $id ${Quote.q(text)}"
        override fun plainWords() = "Reply to notification $id: ${Quote.q(text.take(80))}"
    }

    data class NotifOpen(val id: String) : Action(Verb.NOTIF) {
        override fun render() = "notif open $id"
        override fun plainWords() = "Open notification $id"
    }

    data class ScreenAsk(val question: String) : Action(Verb.SCREEN) {
        override fun render() = "screen ask ${Quote.q(question)}"
        override fun plainWords() = "Ask the vision model: ${Quote.q(question.take(80))}"
    }

    /** Seconds are clamped to [MAX_WAIT_SECONDS] at parse time. */
    data class Wait(val seconds: Int) : Action(Verb.WAIT) {
        override fun render() = "wait $seconds"
        override fun plainWords() = "Wait $seconds seconds"
    }

    data class Done(val summary: String) : Action(Verb.DONE) {
        override fun render() = "done ${Quote.q(summary)}"
        override fun plainWords() = "Finish the task"
    }

    data class Ask(val question: String) : Action(Verb.ASK) {
        override fun render() = "ask ${Quote.q(question)}"
        override fun plainWords() = "Ask you a question"
    }

    data object More : Action(Verb.MORE) {
        override fun render() = "more"
        override fun plainWords() = "Show more of the screen"
    }

    companion object {
        const val MAX_WAIT_SECONDS = 10
    }
}
