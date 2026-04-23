package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class MethodSelectorTest {

	private fun method(id: String, risk: RiskProfile = RiskProfile.NONE): Method = object : Method {
		override val id = MethodId(id)
		override val displayName = id
		override val description = id
		override val paths = listOf(
			ExecutionPath(PathId("$id.ironman"), PathKind.IRONMAN, estimatedRate = XpPerHour(50_000))
		)
		override val requirements: Requirement? = null
		override val effects: Set<Effect> = emptySet()
		override val config = ConfigSchema.EMPTY
		override val locationFootprint: Set<AreaTag> = emptySet()
		override val risk = risk
		override fun estimatedRate(accountState: AccountState) = XpPerHour(50_000)
	}

	private val main = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)

	@Test
	fun `single selected method is always picked`() {
		val m = method("a")
		val sel = MethodSelector(
			allMethods = listOf(m),
			selectedIds = setOf(m.id),
			weights = mapOf(m.id to 1.0)
		)
		val picked = sel.pickNext(main, AccountState(), lastPickedId = null, rotation = RotationPolicy.WEIGHTED)
		assertEquals(m.id, picked!!.id)
	}

	@Test
	fun `empty selection returns null`() {
		val m = method("a")
		val sel = MethodSelector(allMethods = listOf(m), selectedIds = emptySet(), weights = emptyMap())
		val picked = sel.pickNext(main, AccountState(), lastPickedId = null, rotation = RotationPolicy.WEIGHTED)
		assertNull(picked)
	}

	@Test
	fun `WEIGHTED_NO_REPEAT rotation never picks same method twice in a row when multiple available`() {
		val a = method("a")
		val b = method("b")
		val sel = MethodSelector(
			allMethods = listOf(a, b),
			selectedIds = setOf(a.id, b.id),
			weights = mapOf(a.id to 0.99, b.id to 0.01)
		)

		// Force a pick for "a" first, then subsequent pick must be "b"
		val picked = sel.pickNext(main, AccountState(), lastPickedId = a.id, rotation = RotationPolicy.WEIGHTED_NO_REPEAT)
		assertNotNull(picked)
		assertNotEquals(a.id, picked!!.id)
	}

	@Test
	fun `method whose requirements are unmet is skipped`() {
		val a = method("a")
		val hardMethod = object : Method {
			override val id = MethodId("hard")
			override val displayName = "hard"
			override val description = "hard"
			override val paths = listOf(
				ExecutionPath(
					PathId("hard.ironman"),
					PathKind.IRONMAN,
					estimatedRate = XpPerHour(200_000),
					requirements = Requirement.StatLevel(Skill.SLAYER, 85)
				)
			)
			override val requirements = Requirement.StatLevel(Skill.SLAYER, 85)
			override val effects: Set<Effect> = emptySet()
			override val config = ConfigSchema.EMPTY
			override val locationFootprint: Set<AreaTag> = emptySet()
			override val risk = RiskProfile.NONE
			override fun estimatedRate(accountState: AccountState) = XpPerHour(200_000)
		}
		val sel = MethodSelector(
			allMethods = listOf(a, hardMethod),
			selectedIds = setOf(a.id, hardMethod.id),
			weights = mapOf(a.id to 1.0, hardMethod.id to 1.0)
		)
		val picked = sel.pickNext(main, AccountState(), lastPickedId = null, rotation = RotationPolicy.WEIGHTED)
		assertNotNull(picked)
		assertEquals(a.id, picked!!.id)
	}

	@Test
	fun `structurally-invalid method (multiple IRONMAN paths) is rejected before selection`() {
		val broken = object : Method {
			override val id = MethodId("broken")
			override val displayName = "broken"
			override val description = "broken"
			override val paths = listOf(
				ExecutionPath(PathId("a"), PathKind.IRONMAN),
				ExecutionPath(PathId("b"), PathKind.IRONMAN) // second ironman path — illegal
			)
			override val requirements: Requirement? = null
			override val effects: Set<Effect> = emptySet()
			override val config = ConfigSchema.EMPTY
			override val locationFootprint: Set<AreaTag> = emptySet()
			override val risk = RiskProfile.NONE
			override fun estimatedRate(accountState: AccountState) = XpPerHour(50_000)
		}
		assertTrue(broken.validateStructure() is ValidationResult.Reject)
	}
}
