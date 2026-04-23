package net.vital.plugins.buildcore.core.task

import net.vital.plugins.buildcore.core.restrictions.Archetype
import net.vital.plugins.buildcore.core.restrictions.Restriction
import net.vital.plugins.buildcore.core.restrictions.RestrictionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PathSelectorTest {

	private fun paths(): List<ExecutionPath> = listOf(
		ExecutionPath(
			id = PathId("ge"),
			kind = PathKind.GE,
			estimatedRate = XpPerHour(150_000),
			gatingRestrictions = setOf(Restriction.NoGrandExchange)
		),
		ExecutionPath(
			id = PathId("ironman"),
			kind = PathKind.IRONMAN,
			estimatedRate = XpPerHour(60_000)
		)
	)

	private val mainSet = RestrictionSet.compose(Archetype.MAIN, emptySet(), muleOverride = null)
	private val ironmanSet = RestrictionSet.compose(Archetype.IRONMAN, emptySet(), muleOverride = null)
	private val mainNoGe = RestrictionSet.compose(Archetype.MAIN, setOf(Restriction.NoGrandExchange), muleOverride = null)

	@Test
	fun `main with GE allowed picks highest rate (GE path)`() {
		val picked = PathSelector.pick(paths(), mainSet, AccountState())
		assertNotNull(picked)
		assertEquals(PathKind.GE, picked!!.kind)
	}

	@Test
	fun `ironman falls back to IRONMAN path`() {
		val picked = PathSelector.pick(paths(), ironmanSet, AccountState())
		assertNotNull(picked)
		assertEquals(PathKind.IRONMAN, picked!!.kind)
	}

	@Test
	fun `main with user-disabled GE falls back to IRONMAN path`() {
		val picked = PathSelector.pick(paths(), mainNoGe, AccountState())
		assertNotNull(picked)
		assertEquals(PathKind.IRONMAN, picked!!.kind)
	}

	@Test
	fun `returns null when no path is allowed (e g requirements unmet on all)`() {
		val blockedPaths = listOf(
			ExecutionPath(
				id = PathId("only"),
				kind = PathKind.IRONMAN,
				requirements = Requirement.StatLevel(Skill.MINING, 85)
			)
		)
		val state = AccountState(levels = mapOf(Skill.MINING to 40))
		val picked = PathSelector.pick(blockedPaths, mainSet, state)
		assertNull(picked)
	}

	@Test
	fun `path with higher rate but unmet requirements is skipped`() {
		val pathSet = listOf(
			ExecutionPath(
				id = PathId("fast-but-locked"),
				kind = PathKind.GE,
				estimatedRate = XpPerHour(400_000),
				requirements = Requirement.StatLevel(Skill.SMITHING, 99),
				gatingRestrictions = setOf(Restriction.NoGrandExchange)
			),
			ExecutionPath(
				id = PathId("slow-but-ready"),
				kind = PathKind.IRONMAN,
				estimatedRate = XpPerHour(50_000)
			)
		)
		val state = AccountState(levels = mapOf(Skill.SMITHING to 15))
		val picked = PathSelector.pick(pathSet, mainSet, state)
		assertNotNull(picked)
		assertEquals(PathId("slow-but-ready"), picked!!.id)
	}
}
