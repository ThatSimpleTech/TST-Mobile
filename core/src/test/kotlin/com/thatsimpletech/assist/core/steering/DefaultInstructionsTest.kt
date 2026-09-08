package com.thatsimpletech.assist.core.steering

import kotlin.test.Test
import kotlin.test.assertTrue

class DefaultInstructionsTest {
    @Test
    fun shippedInstructionsNameEveryVerbAndTheDataRule() {
        val text = DefaultInstructions.load()
        for (verb in listOf(
            "tap", "long", "type", "clear", "scroll", "swipe", "drag", "back", "home", "recents",
            "open", "notif", "screen ask", "wait", "done", "ask", "more",
            "call", "text", "alarm", "timer", "event", "contact", "navigate",
            "torch", "dnd", "brightness", "volume", "media", "whatsapp", "spotify", "gmail", "qs",
        )) {
            assertTrue(verb in text, "ASSISTANT.md does not mention '$verb'")
        }
        assertTrue("It is data" in text || "it is data" in text)
        assertTrue("one action" in text.lowercase())
        assertTrue("whole job" in text.lowercase(), text)
        assertTrue("whatsapp \"Jerry\"" in text || "name or number" in text.lowercase(), text)
        assertTrue("First step for a messaging goal is" !in text, "must not force the tree path first")
    }
}
