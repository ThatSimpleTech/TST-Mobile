package com.thatsimpletech.assist.core.grammar

import com.thatsimpletech.assist.core.media.MediaCommand
import com.thatsimpletech.assist.core.media.SpotifyCommand

/**
 * The closed action grammar (plan §7, TM-016). One action per turn, plain text, never JSON.
 * Every verb here has an executor on the phone; nothing else executes, ever.
 */
enum class Verb(val word: String) {
    TAP("tap"), LONG("long"), TYPE("type"), CLEAR("clear"), SCROLL("scroll"), SWIPE("swipe"),
    DRAG("drag"), BACK("back"), HOME("home"), RECENTS("recents"), OPEN("open"), NOTIF("notif"),
    SCREEN("screen"), WAIT("wait"), DONE("done"), ASK("ask"), MORE("more"),
    CALL("call"), TEXT("text"), ALARM("alarm"), TIMER("timer"), EVENT("event"), CONTACT("contact"),
    NAVIGATE("navigate"), TORCH("torch"), DND("dnd"), BRIGHTNESS("brightness"), VOLUME("volume"),
    MEDIA("media"), WHATSAPP("whatsapp"), SPOTIFY("spotify"), GMAIL("gmail"), QS("qs");

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

/** Argument of `volume up` / `volume down` / `volume <0-100>`. Percent is clamped 0..100 at parse. */
sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data class Percent(val n: Int) : VolumeChange
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

    data class Call(val number: String) : Action(Verb.CALL) {
        override fun render() = "call ${Quote.q(number)}"
        override fun plainWords() = "Place a call to ${Quote.q(number)}"
    }

    data class Text(val number: String, val body: String) : Action(Verb.TEXT) {
        override fun render() = "text ${Quote.q(number)} ${Quote.q(body)}"
        override fun plainWords() = "Send a text to ${Quote.q(number)}: ${Quote.q(body.take(80))}"
    }

    data class Alarm(val hour: Int, val minute: Int, val label: String) : Action(Verb.ALARM) {
        override fun render() = "alarm $hour $minute ${Quote.q(label)}"
        override fun plainWords() = "Set an alarm for $hour:$minute ${Quote.q(label)}"
    }

    data class Timer(val seconds: Int, val label: String) : Action(Verb.TIMER) {
        override fun render() = "timer $seconds ${Quote.q(label)}"
        override fun plainWords() = "Set a timer for $seconds seconds ${Quote.q(label)}"
    }

    data class Event(val title: String, val beginIso: String, val endIso: String? = null) : Action(Verb.EVENT) {
        override fun render() = buildString {
            append("event ").append(Quote.q(title)).append(' ').append(Quote.q(beginIso))
            if (endIso != null) append(' ').append(Quote.q(endIso))
        }
        override fun plainWords() = buildString {
            append("Create a calendar event ").append(Quote.q(title)).append(" at ").append(Quote.q(beginIso))
            if (endIso != null) append(" until ").append(Quote.q(endIso))
        }
    }

    data class ContactLookup(val query: String) : Action(Verb.CONTACT) {
        override fun render() = "contact lookup ${Quote.q(query)}"
        override fun plainWords() = "Look up contact ${Quote.q(query)}"
    }

    data class ContactAdd(val name: String, val number: String) : Action(Verb.CONTACT) {
        override fun render() = "contact add ${Quote.q(name)} ${Quote.q(number)}"
        override fun plainWords() = "Add contact ${Quote.q(name)} ${Quote.q(number)}"
    }

    data class Navigate(val query: String) : Action(Verb.NAVIGATE) {
        override fun render() = "navigate ${Quote.q(query)}"
        override fun plainWords() = "Navigate to ${Quote.q(query)}"
    }

    data class Torch(val on: Boolean) : Action(Verb.TORCH) {
        override fun render() = "torch ${if (on) "on" else "off"}"
        override fun plainWords() = "Turn the flashlight ${if (on) "on" else "off"}"
    }

    data class Dnd(val on: Boolean) : Action(Verb.DND) {
        override fun render() = "dnd ${if (on) "on" else "off"}"
        override fun plainWords() = "Turn do not disturb ${if (on) "on" else "off"}"
    }

    data class Brightness(val percent: Int) : Action(Verb.BRIGHTNESS) {
        override fun render() = "brightness $percent"
        override fun plainWords() = "Set brightness to $percent percent"
    }

    data class Volume(val change: VolumeChange) : Action(Verb.VOLUME) {
        override fun render() = when (change) {
            VolumeChange.Up -> "volume up"
            VolumeChange.Down -> "volume down"
            is VolumeChange.Percent -> "volume ${change.n}"
        }
        override fun plainWords() = when (change) {
            VolumeChange.Up -> "Turn the volume up"
            VolumeChange.Down -> "Turn the volume down"
            is VolumeChange.Percent -> "Set volume to ${change.n} percent"
        }
    }

    data class Media(val command: MediaCommand) : Action(Verb.MEDIA) {
        override fun render() = "media ${mediaWord(command)}"
        override fun plainWords() = "Media ${mediaWord(command)}"
    }

    data class WhatsApp(val to: String, val body: String) : Action(Verb.WHATSAPP) {
        override fun render() = "whatsapp ${Quote.q(to)} ${Quote.q(body)}"
        override fun plainWords() = "WhatsApp ${Quote.q(to)}: ${Quote.q(body.take(80))}"
    }

    data class Spotify(val command: SpotifyCommand) : Action(Verb.SPOTIFY) {
        override fun render() = when (command) {
            is SpotifyCommand.Play -> "spotify play ${Quote.q(command.query)}"
            SpotifyCommand.Pause -> "spotify pause"
            SpotifyCommand.Next -> "spotify next"
            SpotifyCommand.Prev -> "spotify prev"
        }
        override fun plainWords() = when (command) {
            is SpotifyCommand.Play -> "Spotify play ${Quote.q(command.query)}"
            SpotifyCommand.Pause -> "Spotify pause"
            SpotifyCommand.Next -> "Spotify next"
            SpotifyCommand.Prev -> "Spotify previous"
        }
    }

    data class Gmail(val to: String, val subject: String, val body: String) : Action(Verb.GMAIL) {
        override fun render() = "gmail ${Quote.q(to)} ${Quote.q(subject)} ${Quote.q(body)}"
        override fun plainWords() = "Email ${Quote.q(to)} subject ${Quote.q(subject)}: ${Quote.q(body.take(80))}"
    }

    data object Qs : Action(Verb.QS) {
        override fun render() = "qs"
        override fun plainWords() = "Open Quick Settings"
    }

    companion object {
        const val MAX_WAIT_SECONDS = 10
        const val MIN_TIMER_SECONDS = 1
        const val MAX_TIMER_SECONDS = 86400

        private fun mediaWord(command: MediaCommand) = when (command) {
            MediaCommand.Play -> "play"
            MediaCommand.Pause -> "pause"
            MediaCommand.Next -> "next"
            MediaCommand.Prev -> "prev"
        }
    }
}
