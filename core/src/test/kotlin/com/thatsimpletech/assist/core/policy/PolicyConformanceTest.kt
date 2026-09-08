package com.thatsimpletech.assist.core.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared suite: policy/cases.yaml against policy/policy.yaml. */
class PolicyConformanceTest {
    @Test
    fun shippedPackIsValid() {
        val pack = PolicyPack.loadDefault()
        assertEquals(emptyList(), pack.validate())
        assertEquals(1, pack.version)
    }

    @Test
    fun everyCasePasses() {
        val enforcer = PolicyEnforcer(PolicyPack.loadDefault())
        val results = Conformance.run(enforcer, Conformance.loadDefault())
        assertTrue(results.size >= 25, "expected a real suite, got ${results.size} cases")
        val failed = results.filter { !it.passed }
        assertTrue(
            failed.isEmpty(),
            failed.joinToString("\n") { "${it.case.name}: expected ${it.case.expect.tier}/${it.case.expect.rule}, got ${it.tier}/${it.rule}" },
        )
    }

    @Test
    fun everyRuleHasACase() {
        val pack = PolicyPack.loadDefault()
        val enforcer = PolicyEnforcer(pack)
        val hit = Conformance.run(enforcer, Conformance.loadDefault()).mapTo(HashSet()) { it.rule }
        val uncovered = pack.rules.map { it.id }.filter { it !in hit }
        assertTrue(uncovered.isEmpty(), "rules with no conformance case: $uncovered")
    }

    @Test
    fun refusalsComeBeforeGrants() {
        val pack = PolicyPack.loadDefault()
        val firstGrant = pack.rules.indexOfFirst { it.tier != Tier.REFUSED }
        val lastRefusal = pack.rules.indexOfLast { it.tier == Tier.REFUSED }
        assertTrue(lastRefusal < firstGrant, "a refusal rule sits after a grant rule")
    }
}
