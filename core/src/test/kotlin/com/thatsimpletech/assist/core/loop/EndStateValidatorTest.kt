package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.config.AssistConfig
import com.thatsimpletech.assist.core.config.TierName
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.net.ChatResult
import com.thatsimpletech.assist.core.net.ProviderUsage
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.observe.ObservationBuilder
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Screen
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EndStateValidatorTest {
    private val prices = AssistConfig.loadDefault().active.validator
    private val whatsapp = setOf("com.whatsapp")
    private val builder = ObservationBuilder()

    private fun obs(screen: Screen) = builder.observe(screen)

    private fun emptyObs(): Observation = builder.observe(
        Screen(app = "", activity = "", display = Rect(0, 0, 1, 1), nodes = emptyList()),
    )

    private fun model(
        reply: String,
        meter: CostTracker = CostTracker(),
    ) = ModelEndStateValidator(
        complete = { ChatResult(reply, ProviderUsage(100, null, 5)) },
        meter = meter,
        prices = prices,
        model = "v",
    )

    private fun validate(
        last: Observation,
        goalApps: Set<String> = whatsapp,
        model: ModelEndStateValidator? = null,
        goal: String = "reply to Maria confirming 7pm",
    ): Validation = runBlocking {
        CompositeEndStateValidator(model).validate(goal, last, goalApps)
    }

    @Test
    fun passWhenAppMatchesGoal() {
        assertEquals(Validation.Pass, validate(obs(Screens.whatsapp()), whatsapp))
    }

    @Test
    fun failWhenEndedInOtherApp() {
        val v = validate(obs(Screens.gmail()), whatsapp, model = model("pass"))
        assertEquals(Validation.Fail("ended in com.google.android.gm, not a goal app"), v)
    }

    @Test
    fun failClosedOnGarbageModelReply() {
        val v = validate(obs(Screens.whatsapp()), model = model("lol nope"))
        assertEquals(Validation.Fail(ModelEndStateValidator.UNPARSEABLE), v)
        assertEquals(Validation.Fail(ModelEndStateValidator.UNPARSEABLE), ModelEndStateValidator.parse("{ok:true}"))
        assertEquals(Validation.Fail(ModelEndStateValidator.UNPARSEABLE), ModelEndStateValidator.parse("fail unquoted"))
        assertEquals(Validation.Fail(ModelEndStateValidator.UNPARSEABLE), ModelEndStateValidator.parse(""))
    }

    @Test
    fun passOnEmptyNoA11yObservation() {
        val empty = emptyObs()
        assertTrue(empty.app.isEmpty())
        assertTrue(empty.lines.isEmpty())
        assertEquals(Validation.Pass, validate(empty, whatsapp))
        assertEquals(Validation.Pass, validate(empty, emptySet()))
    }

    @Test
    fun modelFailReasonIsQuoted() {
        val parsed = ModelEndStateValidator.parse("""fail "still on the composer"""")
        assertEquals(Validation.Fail("still on the composer"), parsed)
        val v = validate(obs(Screens.whatsapp()), model = model("""fail "still on the composer""""))
        assertEquals(Validation.Fail("still on the composer"), v)
        assertEquals(Validation.Pass, ModelEndStateValidator.parse("pass"))
        assertEquals(Validation.Pass, ModelEndStateValidator.parse("`PASS`"))
    }

    @Test
    fun failWhenEndedOnSecureScreen() {
        val v = validate(obs(Screens.whatsapp().copy(secure = true)), whatsapp, model = model("pass"))
        assertEquals(Validation.Fail("ended on a secure screen"), v)
    }

    @Test
    fun failClosedOnModelCallError() {
        val meter = CostTracker()
        val broken = ModelEndStateValidator(
            complete = { throw IOException("down") },
            meter = meter,
            prices = prices,
            model = "v",
        )
        val v = validate(obs(Screens.whatsapp()), model = broken)
        assertEquals(Validation.Fail("validator call failed"), v)
        assertEquals(0.0, meter.classifierCost())
        assertEquals(0.0, meter.sessionCost())
    }

    @Test
    fun modelIsSkippedAfterDeterministicFail() {
        val v = validate(
            obs(Screens.gmail()),
            whatsapp,
            model = ModelEndStateValidator(
                complete = { error("model must not run after a deterministic fail") },
                meter = CostTracker(),
                prices = prices,
                model = "v",
            ),
        )
        assertIs<Validation.Fail>(v)
        assertEquals("ended in com.google.android.gm, not a goal app", v.reason)
    }

    @Test
    fun modelCallIsClassifierOnTheMeter() {
        val meter = CostTracker()
        meter.beginTurn()
        val v = validate(obs(Screens.whatsapp()), model = model("pass", meter))
        assertEquals(Validation.Pass, v)
        assertEquals(0.0, meter.sessionCost())
        assertEquals(0.0, meter.turnCost())
        assertTrue(meter.classifierCost() > 0.0)
        assertTrue(meter.costByTier().isEmpty())
        assertEquals(setOf<TierName>(), meter.calls.map { it.tier }.toSet())
    }
}
