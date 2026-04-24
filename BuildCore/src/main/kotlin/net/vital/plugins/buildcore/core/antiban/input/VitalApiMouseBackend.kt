package net.vital.plugins.buildcore.core.antiban.input

import vital.api.input.Movement

/**
 * Real [MouseBackend] backed by [vital.api.input.Movement]. Cursor position
 * is tracked client-side because VitalAPI exposes no accessor; every
 * [appendTrailPoint] / [click] call updates the cached [position].
 *
 * Spec §7.3.
 */
internal object VitalApiMouseBackend : MouseBackend
{

	@Volatile
	private var position: Point = Point(0, 0)

	override fun currentPosition(): Point = position

	override fun appendTrailPoint(x: Int, y: Int)
	{
		Movement.appendTrailPoint(x, y)
		position = Point(x, y)
	}

	override fun click(x: Int, y: Int, button: MouseButton)
	{
		Movement.click(x, y, button == MouseButton.RIGHT)
		position = Point(x, y)
	}
}
