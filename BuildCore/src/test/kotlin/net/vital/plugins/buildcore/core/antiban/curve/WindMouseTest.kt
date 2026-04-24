package net.vital.plugins.buildcore.core.antiban.curve

import net.vital.plugins.buildcore.core.antiban.input.Point
import net.vital.plugins.buildcore.core.antiban.rng.JavaUtilRng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class WindMouseTest
{
	@Test
	fun `path terminates at target`()
	{
		val path = WindMouse.generatePath(
			from = Point(100, 100), to = Point(400, 300),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0,
			rng = JavaUtilRng(42L)
		)
		assertEquals(Point(400, 300), path.last().first)
	}

	@Test
	fun `same seed produces identical path`()
	{
		val a = WindMouse.generatePath(Point(0, 0), Point(500, 500),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0, rng = JavaUtilRng(7L))
		val b = WindMouse.generatePath(Point(0, 0), Point(500, 500),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0, rng = JavaUtilRng(7L))
		assertEquals(a, b)
	}

	@Test
	fun `from equals to returns single-point path`()
	{
		val path = WindMouse.generatePath(Point(200, 200), Point(200, 200),
			gravity = 10.0, wind = 5.0, speedCenter = 1.0, rng = JavaUtilRng(1L))
		assertEquals(1, path.size)
		assertEquals(Point(200, 200), path.first().first)
	}

	@Test
	fun `higher wind produces more curve deviation from straight line`()
	{
		val from = Point(0, 0); val target = Point(500, 0)
		fun maxOffset(windValue: Double): Int
		{
			val path = WindMouse.generatePath(from, target,
				gravity = 10.0, wind = windValue, speedCenter = 1.0, rng = JavaUtilRng(42L))
			return path.maxOf { (p, _) -> kotlin.math.abs(p.y) }
		}
		val lowWind = maxOffset(3.0)
		val highWind = maxOffset(7.0)
		assertTrue(highWind > lowWind, "expected high-wind ($highWind) > low-wind ($lowWind)")
	}
}
