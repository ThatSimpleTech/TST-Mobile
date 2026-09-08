package com.thatsimpletech.assist.core.grammar

import com.thatsimpletech.assist.core.media.MediaCommand
import com.thatsimpletech.assist.core.media.SpotifyCommand
import com.thatsimpletech.assist.core.observe.Spatial

sealed interface ParseResult {
    data class Ok(val action: Action) : ParseResult
    /** [reason] is returned to the model once, verbatim, as the repair message (plan §7). */
    data class Error(val reason: String) : ParseResult
}

/**
 * Strict parser for the closed grammar. First non-empty line only; unknown verbs and
 * hints that are not on the current screen are errors, never guesses.
 */
class ActionParser(val codec: HintCodec = HintCodec.Numeric) {

    /**
     * @param raw the model's whole reply
     * @param known hints present in the current observation; when given, any hint outside
     *   it is refused here, before policy or execution
     * @param at maps `@x,y` screen percents to a hint on the current observation
     */
    fun parse(raw: String, known: Set<Int>? = null, at: ((Int, Int) -> Int?)? = null): ParseResult {
        val line = firstLine(raw) ?: return ParseResult.Error("empty reply: answer with one action line")
        val tokens = try {
            tokenize(line)
        } catch (e: IllegalArgumentException) {
            return ParseResult.Error(e.message ?: "unbalanced quotes")
        }
        if (tokens.isEmpty()) return ParseResult.Error("empty reply: answer with one action line")
        val head = tokens.first()
        if (head.quoted) return ParseResult.Error("the line must start with a verb, not a quoted string")
        val verb = Verb.of(head.text)
        val parsed = if (verb == null) {
            val taken = takeHint(tokens, at)
            if (taken != null && taken.rest.isEmpty()) {
                ParseResult.Ok(Action.Tap(taken.hint))
            } else {
                ParseResult.Error("unknown verb '${head.text}'")
            }
        } else {
            val args = tokens.drop(1)
            when (verb) {
                Verb.TAP -> oneHint(args, verb, at) { Action.Tap(it) }
                Verb.LONG -> oneHint(args, verb, at) { Action.Long(it) }
                Verb.CLEAR -> oneHint(args, verb, at) { Action.Clear(it) }
                Verb.TYPE -> parseType(args, at)
                Verb.SCROLL -> parseScroll(args, at)
                Verb.SWIPE -> parseSwipe(args, at)
                Verb.DRAG -> parseDrag(args, at)
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

    private fun oneHint(args: List<Token>, verb: Verb, at: ((Int, Int) -> Int?)?, build: (Int) -> Action): ParseResult {
        val taken = takeHint(args, at) ?: return notAHint(args, verb)
        if (taken.rest.isNotEmpty()) return ParseResult.Error("${verb.word} takes exactly one hint, like `${verb.word} 3` or `${verb.word} @80,92`")
        return ParseResult.Ok(build(taken.hint))
    }

    private fun parseType(args: List<Token>, at: ((Int, Int) -> Int?)?): ParseResult {
        val taken = takeHint(args, at)
        if (taken == null || taken.rest.size != 1 || !taken.rest[0].quoted) {
            return ParseResult.Error("type takes a hint and quoted text, like `type 1 \"hello\"` or `type @40,90 \"hello\"`")
        }
        return ParseResult.Ok(Action.Type(taken.hint, taken.rest[0].text))
    }

    private fun parseScroll(args: List<Token>, at: ((Int, Int) -> Int?)?): ParseResult {
        val taken = takeHint(args, at)
        if (taken == null || taken.rest.size != 1 || taken.rest[0].quoted) {
            return ParseResult.Error("scroll takes a hint and a direction, like `scroll 4 down`")
        }
        val dir = Direction.of(taken.rest[0].text) ?: return ParseResult.Error("direction must be up, down, left or right")
        return ParseResult.Ok(Action.Scroll(taken.hint, dir))
    }

    private fun parseSwipe(args: List<Token>, at: ((Int, Int) -> Int?)?): ParseResult {
        if (args.size >= 2 && !args[0].quoted && args[0].text.equals("screen", ignoreCase = true)) {
            val dir = Direction.of(args[1].text) ?: return ParseResult.Error("direction must be up, down, left or right")
            if (args.size != 2 || args[1].quoted) return ParseResult.Error("swipe takes a hint or `screen`, then a direction, like `swipe screen up`")
            return ParseResult.Ok(Action.Swipe(SwipeTarget.Screen, dir))
        }
        val taken = takeHint(args, at)
        if (taken == null || taken.rest.size != 1 || taken.rest[0].quoted) {
            return ParseResult.Error("swipe takes a hint or `screen`, then a direction, like `swipe screen up`")
        }
        val dir = Direction.of(taken.rest[0].text) ?: return ParseResult.Error("direction must be up, down, left or right")
        return ParseResult.Ok(Action.Swipe(SwipeTarget.Hint(taken.hint), dir))
    }

    private fun parseDrag(args: List<Token>, at: ((Int, Int) -> Int?)?): ParseResult {
        val from = takeHint(args, at)
        if (from == null || from.rest.size < 2 || from.rest[0].quoted || !from.rest[0].text.equals("to", ignoreCase = true)) {
            return ParseResult.Error("drag takes two hints, like `drag 2 to 5` or `drag @10,20 to @80,90`")
        }
        val to = takeHint(from.rest.drop(1), at)
        if (to == null || to.rest.isNotEmpty()) {
            return ParseResult.Error("drag takes two hints, like `drag 2 to 5` or `drag @10,20 to @80,90`")
        }
        return ParseResult.Ok(Action.Drag(from.hint, to.hint))
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
        if (args.size != 1 || !args[0].quoted) return ParseResult.Error("call takes a quoted name or number, like `call \"Jerry\"`")
        if (args[0].text.isBlank()) return ParseResult.Error("call needs a name or number")
        return ParseResult.Ok(Action.Call(args[0].text))
    }

    private fun parseText(args: List<Token>): ParseResult {
        if (args.size != 2 || !args[0].quoted || !args[1].quoted) {
            return ParseResult.Error("text takes a quoted name or number and quoted body, like `text \"Jerry\" \"On my way\"`")
        }
        if (args[0].text.isBlank()) return ParseResult.Error("text needs a name or number")
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

    private data class Taken(val hint: Int, val rest: List<Token>)

    private fun takeHint(args: List<Token>, at: ((Int, Int) -> Int?)?): Taken? {
        if (args.isEmpty() || args[0].quoted) return null
        hint(args[0])?.let { return Taken(it, args.drop(1)) }
        val (xy, n) = takeAt(args) ?: return null
        val hint = at?.invoke(xy.first, xy.second) ?: return null
        return Taken(hint, args.drop(n))
    }

    private fun takeAt(args: List<Token>): Pair<Pair<Int, Int>, Int>? {
        if (args.isEmpty() || args[0].quoted) return null
        Spatial.parseAt(args[0].text)?.let { return it to 1 }
        if (args.size >= 2 && !args[1].quoted) {
            Spatial.parseAt(args[0].text + args[1].text)?.let { return it to 2 }
            Spatial.parseAt(args[0].text + "," + args[1].text)?.let { return it to 2 }
        }
        if (args.size >= 3 && !args[1].quoted && !args[2].quoted && args[1].text == ",") {
            Spatial.parseAt(args[0].text + "," + args[2].text)?.let { return it to 3 }
        }
        return null
    }

    private fun notAHint(args: List<Token>, verb: Verb): ParseResult {
        val shown = args.firstOrNull()?.text ?: ""
        val at = takeAt(args)
        return if (at != null) {
            ParseResult.Error("no control at @${at.first.first},${at.first.second}")
        } else if (shown.startsWith("@") || "," in shown) {
            ParseResult.Error("'$shown' is a screen percent; write `${verb.word} @80,92` for the control at that point")
        } else {
            ParseResult.Error("'$shown' is not a hint")
        }
    }

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
