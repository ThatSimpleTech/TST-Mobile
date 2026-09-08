package com.thatsimpletech.assist.core.grammar

import com.thatsimpletech.assist.core.media.MediaCommand
import com.thatsimpletech.assist.core.media.SpotifyCommand

sealed interface ParseResult {
    data class Ok(val action: Action) : ParseResult
    /** [reason] is returned to the model once, verbatim, as the repair message (plan §7). */
    data class Error(val reason: String) : ParseResult
}

/**
 * Strict parser for the closed grammar. First non-empty line only; unknown verbs and
 * hints that are not on the current screen are errors, never guesses.
 */
class ActionParser(private val codec: HintCodec = HintCodec.Numeric) {

    /**
     * @param raw the model's whole reply
     * @param known hints present in the current observation; when given, any hint outside
     *   it is refused here, before policy or execution
     */
    fun parse(raw: String, known: Set<Int>? = null): ParseResult {
        val line = firstLine(raw) ?: return ParseResult.Error("empty reply: answer with one action line")
        val tokens = try {
            tokenize(line)
        } catch (e: IllegalArgumentException) {
            return ParseResult.Error(e.message ?: "unbalanced quotes")
        }
        if (tokens.isEmpty()) return ParseResult.Error("empty reply: answer with one action line")
        val head = tokens.first()
        if (head.quoted) return ParseResult.Error("the line must start with a verb, not a quoted string")
        val verb = Verb.of(head.text) ?: return ParseResult.Error("unknown verb '${head.text}'")
        val args = tokens.drop(1)
        val parsed = when (verb) {
            Verb.TAP -> oneHint(args, verb) { Action.Tap(it) }
            Verb.LONG -> oneHint(args, verb) { Action.Long(it) }
            Verb.CLEAR -> oneHint(args, verb) { Action.Clear(it) }
            Verb.TYPE -> parseType(args)
            Verb.SCROLL -> parseScroll(args)
            Verb.SWIPE -> parseSwipe(args)
            Verb.DRAG -> parseDrag(args)
            Verb.BACK -> bare(args, verb, Action.Back)
            Verb.HOME -> bare(args, verb, Action.Home)
            Verb.RECENTS -> bare(args, verb, Action.Recents)
            Verb.MORE -> bare(args, verb, Action.More)
            Verb.OPEN -> parseOpen(args)
            Verb.NOTIF -> parseNotif(args)
            Verb.SCREEN -> parseScreen(args)
            Verb.WAIT -> parseWait(args)
            Verb.DONE -> tail(args, verb) { Action.Done(it) }
            Verb.ASK -> tail(args, verb) { Action.Ask(it) }
            Verb.CALL -> parseCall(args)
            Verb.TEXT -> parseText(args)
            Verb.ALARM -> parseAlarm(args)
            Verb.TIMER -> parseTimer(args)
            Verb.EVENT -> parseEvent(args)
            Verb.CONTACT -> parseContact(args)
            Verb.NAVIGATE -> parseNavigate(args)
            Verb.TORCH -> parseTorch(args)
            Verb.DND -> parseDnd(args)
            Verb.BRIGHTNESS -> parseBrightness(args)
            Verb.VOLUME -> parseVolume(args)
            Verb.MEDIA -> parseMedia(args)
            Verb.WHATSAPP -> parseWhatsApp(args)
            Verb.SPOTIFY -> parseSpotify(args)
            Verb.GMAIL -> parseGmail(args)
            Verb.QS -> bare(args, verb, Action.Qs)
        }
        if (parsed is ParseResult.Ok && known != null) {
            val missing = parsed.action.hints.firstOrNull { it !in known }
            if (missing != null) {
                return ParseResult.Error("hint ${codec.encode(missing)} is not on screen; use only hints from the current observation")
            }
        }
        return parsed
    }

    // ---- verbs ----

    private fun oneHint(args: List<Token>, verb: Verb, build: (Int) -> Action): ParseResult {
        if (args.size != 1 || args[0].quoted) return ParseResult.Error("${verb.word} takes exactly one hint, like `${verb.word} 3`")
        val hint = hint(args[0]) ?: return ParseResult.Error("'${args[0].text}' is not a hint")
        return ParseResult.Ok(build(hint))
    }

    private fun parseType(args: List<Token>): ParseResult {
        if (args.size != 2 || args[0].quoted || !args[1].quoted) {
            return ParseResult.Error("type takes a hint and quoted text, like `type 1 \"hello\"`")
        }
        val hint = hint(args[0]) ?: return ParseResult.Error("'${args[0].text}' is not a hint")
        return ParseResult.Ok(Action.Type(hint, args[1].text))
    }

    private fun parseScroll(args: List<Token>): ParseResult {
        if (args.size != 2 || args.any { it.quoted }) return ParseResult.Error("scroll takes a hint and a direction, like `scroll 4 down`")
        val hint = hint(args[0]) ?: return ParseResult.Error("'${args[0].text}' is not a hint")
        val dir = Direction.of(args[1].text) ?: return ParseResult.Error("direction must be up, down, left or right")
        return ParseResult.Ok(Action.Scroll(hint, dir))
    }

    private fun parseSwipe(args: List<Token>): ParseResult {
        if (args.size != 2 || args.any { it.quoted }) return ParseResult.Error("swipe takes a hint or `screen`, then a direction, like `swipe screen up`")
        val target: SwipeTarget = if (args[0].text.equals("screen", ignoreCase = true)) {
            SwipeTarget.Screen
        } else {
            SwipeTarget.Hint(hint(args[0]) ?: return ParseResult.Error("'${args[0].text}' is not a hint"))
        }
        val dir = Direction.of(args[1].text) ?: return ParseResult.Error("direction must be up, down, left or right")
        return ParseResult.Ok(Action.Swipe(target, dir))
    }

    private fun parseDrag(args: List<Token>): ParseResult {
        if (args.size != 3 || args.any { it.quoted } || !args[1].text.equals("to", ignoreCase = true)) {
            return ParseResult.Error("drag takes two hints, like `drag 2 to 5`")
        }
        val from = hint(args[0]) ?: return ParseResult.Error("'${args[0].text}' is not a hint")
        val to = hint(args[2]) ?: return ParseResult.Error("'${args[2].text}' is not a hint")
        return ParseResult.Ok(Action.Drag(from, to))
    }

    private fun bare(args: List<Token>, verb: Verb, action: Action): ParseResult =
        if (args.isEmpty()) ParseResult.Ok(action) else ParseResult.Error("${verb.word} takes no arguments")

    private fun parseOpen(args: List<Token>): ParseResult {
        if (args.size != 1 || !args[0].quoted) return ParseResult.Error("open takes a quoted app name, like `open \"Messages\"`")
        val label = args[0].text.trim()
        if (label.isEmpty()) return ParseResult.Error("open needs an app name")
        return ParseResult.Ok(Action.Open(label))
    }

    private fun parseNotif(args: List<Token>): ParseResult {
        if (args.isEmpty() || args[0].quoted) return ParseResult.Error("notif takes list, reply <id> \"text\", or open <id>")
        return when (args[0].text.lowercase()) {
            "list" -> if (args.size == 1) ParseResult.Ok(Action.NotifList) else ParseResult.Error("notif list takes no arguments")
            "reply" -> {
                if (args.size != 3 || args[1].quoted || !args[2].quoted) ParseResult.Error("notif reply takes an id and quoted text, like `notif reply n2 \"On my way\"`")
                else ParseResult.Ok(Action.NotifReply(args[1].text, args[2].text))
            }
            "open" -> {
                if (args.size != 2 || args[1].quoted) ParseResult.Error("notif open takes an id, like `notif open n2`")
                else ParseResult.Ok(Action.NotifOpen(args[1].text))
            }
            else -> ParseResult.Error("notif takes list, reply <id> \"text\", or open <id>")
        }
    }

    private fun parseScreen(args: List<Token>): ParseResult {
        if (args.size != 2 || args[0].quoted || !args[0].text.equals("ask", ignoreCase = true) || !args[1].quoted) {
            return ParseResult.Error("screen takes ask and a quoted question, like `screen ask \"what is the total?\"`")
        }
        return ParseResult.Ok(Action.ScreenAsk(args[1].text))
    }

    private fun parseWait(args: List<Token>): ParseResult {
        if (args.size != 1 || args[0].quoted) return ParseResult.Error("wait takes a number of seconds, like `wait 2`")
        val n = args[0].text.toIntOrNull() ?: return ParseResult.Error("'${args[0].text}' is not a number of seconds")
        if (n < 1) return ParseResult.Error("wait needs at least 1 second")
        return ParseResult.Ok(Action.Wait(minOf(n, Action.MAX_WAIT_SECONDS)))
    }

    private fun parseCall(args: List<Token>): ParseResult {
        if (args.size != 1 || !args[0].quoted) return ParseResult.Error("call takes a quoted number, like `call \"5550100\"`")
        if (args[0].text.isBlank()) return ParseResult.Error("call needs a number")
        return ParseResult.Ok(Action.Call(args[0].text))
    }

    private fun parseText(args: List<Token>): ParseResult {
        if (args.size != 2 || !args[0].quoted || !args[1].quoted) {
            return ParseResult.Error("text takes a quoted number and quoted body, like `text \"5550100\" \"On my way\"`")
        }
        if (args[0].text.isBlank()) return ParseResult.Error("text needs a number")
        return ParseResult.Ok(Action.Text(args[0].text, args[1].text))
    }

    private fun parseAlarm(args: List<Token>): ParseResult {
        if (args.size != 3 || args[0].quoted || args[1].quoted || !args[2].quoted) {
            return ParseResult.Error("alarm takes an hour, a minute and a quoted label, like `alarm 7 30 \"wake\"`")
        }
        val hour = args[0].text.toIntOrNull() ?: return ParseResult.Error("'${args[0].text}' is not an hour")
        val minute = args[1].text.toIntOrNull() ?: return ParseResult.Error("'${args[1].text}' is not a minute")
        if (hour !in 0..23) return ParseResult.Error("hour must be 0 to 23")
        if (minute !in 0..59) return ParseResult.Error("minute must be 0 to 59")
        return ParseResult.Ok(Action.Alarm(hour, minute, args[2].text))
    }

    private fun parseTimer(args: List<Token>): ParseResult {
        if (args.size != 2 || args[0].quoted || !args[1].quoted) {
            return ParseResult.Error("timer takes seconds and a quoted label, like `timer 60 \"eggs\"`")
        }
        val n = args[0].text.toIntOrNull() ?: return ParseResult.Error("'${args[0].text}' is not a number of seconds")
        val seconds = n.coerceIn(Action.MIN_TIMER_SECONDS, Action.MAX_TIMER_SECONDS)
        return ParseResult.Ok(Action.Timer(seconds, args[1].text))
    }

    private fun parseEvent(args: List<Token>): ParseResult {
        if (args.size !in 2..3 || args.any { !it.quoted }) {
            return ParseResult.Error("event takes a quoted title and begin, optionally a quoted end, like `event \"Dentist\" \"2026-09-08T10:00:00\"`")
        }
        if (args[0].text.isBlank()) return ParseResult.Error("event needs a title")
        if (args[1].text.isBlank()) return ParseResult.Error("event needs a begin time")
        return ParseResult.Ok(Action.Event(args[0].text, args[1].text, args.getOrNull(2)?.text))
    }

    private fun parseContact(args: List<Token>): ParseResult {
        if (args.isEmpty() || args[0].quoted) return ParseResult.Error("contact takes lookup \"name\", or add \"name\" \"number\"")
        return when (args[0].text.lowercase()) {
            "lookup" -> {
                if (args.size != 2 || !args[1].quoted) ParseResult.Error("contact lookup takes a quoted name, like `contact lookup \"Maria\"`")
                else if (args[1].text.isBlank()) ParseResult.Error("contact lookup needs a name")
                else ParseResult.Ok(Action.ContactLookup(args[1].text))
            }
            "add" -> {
                if (args.size != 3 || !args[1].quoted || !args[2].quoted) ParseResult.Error("contact add takes a quoted name and number, like `contact add \"Maria\" \"5550100\"`")
                else if (args[1].text.isBlank()) ParseResult.Error("contact add needs a name")
                else if (args[2].text.isBlank()) ParseResult.Error("contact add needs a number")
                else ParseResult.Ok(Action.ContactAdd(args[1].text, args[2].text))
            }
            else -> ParseResult.Error("contact takes lookup \"name\", or add \"name\" \"number\"")
        }
    }

    private fun parseNavigate(args: List<Token>): ParseResult {
        if (args.size != 1 || !args[0].quoted) return ParseResult.Error("navigate takes a quoted place, like `navigate \"home\"`")
        if (args[0].text.isBlank()) return ParseResult.Error("navigate needs a place")
        return ParseResult.Ok(Action.Navigate(args[0].text))
    }

    private fun parseTorch(args: List<Token>): ParseResult = onOff(args, Verb.TORCH) { Action.Torch(it) }

    private fun parseDnd(args: List<Token>): ParseResult = onOff(args, Verb.DND) { Action.Dnd(it) }

    private fun parseBrightness(args: List<Token>): ParseResult {
        if (args.size != 1 || args[0].quoted) return ParseResult.Error("brightness takes a percent from 0 to 100, like `brightness 50`")
        val n = args[0].text.toIntOrNull() ?: return ParseResult.Error("'${args[0].text}' is not a percent")
        if (n !in 0..100) return ParseResult.Error("brightness is a percent from 0 to 100")
        return ParseResult.Ok(Action.Brightness(n))
    }

    private fun parseVolume(args: List<Token>): ParseResult {
        if (args.size != 1 || args[0].quoted) return ParseResult.Error("volume takes up, down, or a percent from 0 to 100")
        return when (args[0].text.lowercase()) {
            "up" -> ParseResult.Ok(Action.Volume(VolumeChange.Up))
            "down" -> ParseResult.Ok(Action.Volume(VolumeChange.Down))
            else -> {
                val n = args[0].text.toIntOrNull() ?: return ParseResult.Error("volume takes up, down, or a percent from 0 to 100")
                ParseResult.Ok(Action.Volume(VolumeChange.Percent(n.coerceIn(0, 100))))
            }
        }
    }

    private fun parseMedia(args: List<Token>): ParseResult {
        if (args.size != 1 || args[0].quoted) return ParseResult.Error("media takes play, pause, next or prev")
        val command = when (args[0].text.lowercase()) {
            "play" -> MediaCommand.Play
            "pause" -> MediaCommand.Pause
            "next" -> MediaCommand.Next
            "prev" -> MediaCommand.Prev
            else -> return ParseResult.Error("media takes play, pause, next or prev")
        }
        return ParseResult.Ok(Action.Media(command))
    }

    private fun parseWhatsApp(args: List<Token>): ParseResult {
        if (args.size != 2 || !args[0].quoted || !args[1].quoted) {
            return ParseResult.Error("whatsapp takes a quoted recipient and quoted body, like `whatsapp \"Maria\" \"On my way\"`")
        }
        if (args[0].text.isBlank()) return ParseResult.Error("whatsapp needs a recipient")
        return ParseResult.Ok(Action.WhatsApp(args[0].text, args[1].text))
    }

    private fun parseSpotify(args: List<Token>): ParseResult {
        if (args.isEmpty() || args[0].quoted) return ParseResult.Error("spotify takes play \"query\", pause, next or prev")
        return when (args[0].text.lowercase()) {
            "play" -> {
                if (args.size != 2 || !args[1].quoted) ParseResult.Error("spotify play takes a quoted query, like `spotify play \"jazz\"`")
                else if (args[1].text.isBlank()) ParseResult.Error("spotify play needs a query")
                else ParseResult.Ok(Action.Spotify(SpotifyCommand.Play(args[1].text)))
            }
            "pause" -> if (args.size == 1) ParseResult.Ok(Action.Spotify(SpotifyCommand.Pause)) else ParseResult.Error("spotify pause takes no arguments")
            "next" -> if (args.size == 1) ParseResult.Ok(Action.Spotify(SpotifyCommand.Next)) else ParseResult.Error("spotify next takes no arguments")
            "prev" -> if (args.size == 1) ParseResult.Ok(Action.Spotify(SpotifyCommand.Prev)) else ParseResult.Error("spotify prev takes no arguments")
            else -> ParseResult.Error("spotify takes play \"query\", pause, next or prev")
        }
    }

    private fun parseGmail(args: List<Token>): ParseResult {
        if (args.size != 3 || args.any { !it.quoted }) {
            return ParseResult.Error("gmail takes a quoted to, subject and body, like `gmail \"a@b.com\" \"Hi\" \"See you\"`")
        }
        if (args[0].text.isBlank()) return ParseResult.Error("gmail needs a recipient")
        return ParseResult.Ok(Action.Gmail(args[0].text, args[1].text, args[2].text))
    }

    private fun onOff(args: List<Token>, verb: Verb, build: (Boolean) -> Action): ParseResult {
        if (args.size != 1 || args[0].quoted) return ParseResult.Error("${verb.word} takes on or off")
        return when (args[0].text.lowercase()) {
            "on" -> ParseResult.Ok(build(true))
            "off" -> ParseResult.Ok(build(false))
            else -> ParseResult.Error("${verb.word} takes on or off")
        }
    }

    /** done/ask accept quoted text, or the rest of the line as plain words. */
    private fun tail(args: List<Token>, verb: Verb, build: (String) -> Action): ParseResult {
        if (args.isEmpty()) return ParseResult.Error("${verb.word} needs text, like `${verb.word} \"...\"`")
        val text = if (args.size == 1 && args[0].quoted) args[0].text else args.joinToString(" ") { it.text }
        if (text.isBlank()) return ParseResult.Error("${verb.word} needs text")
        return ParseResult.Ok(build(text))
    }

    private fun hint(t: Token): Int? = if (t.quoted) null else codec.decode(t.text)

    // ---- lexing ----

    internal data class Token(val text: String, val quoted: Boolean)

    internal fun firstLine(raw: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        // Tolerate one layer of inline code fencing on the action line, nothing more.
        return line.removeSurrounding("`").trim().ifEmpty { null }
    }

    internal fun tokenize(line: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    var closed = false
                    while (i < n) {
                        val d = line[i]
                        if (d == '\\' && i + 1 < n) {
                            when (val e = line[i + 1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                else -> sb.append(e)
                            }
                            i += 2
                        } else if (d == '"') {
                            closed = true
                            i++
                            break
                        } else {
                            sb.append(d)
                            i++
                        }
                    }
                    if (!closed) throw IllegalArgumentException("unbalanced quotes")
                    out.add(Token(sb.toString(), quoted = true))
                }
                else -> {
                    val start = i
                    while (i < n && !line[i].isWhitespace()) i++
                    out.add(Token(line.substring(start, i), quoted = false))
                }
            }
        }
        return out
    }
}
