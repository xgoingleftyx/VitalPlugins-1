package net.vital.plugins.buildcore.core.antiban.input

/**
 * Screen-pixel coordinate pair. Packed into a Long to avoid allocation
 * pressure inside WindMouse's per-step path generation (hundreds of
 * points per move).
 *
 * Spec §7.1.
 */
@JvmInline
value class Point(val packed: Long) {
	constructor(x: Int, y: Int) : this(
		(x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
	)
	val x: Int get() = (packed ushr 32).toInt()
	val y: Int get() = packed.toInt()
	override fun toString(): String = "Point($x, $y)"
}

enum class MouseButton { LEFT, RIGHT, MIDDLE }

@JvmInline
value class Key(val vk: Int)

data class CameraAngle(val rotation: Int, val pitch: Int)

// InputMode is re-declared in core.events.BusEvent for schema stability;
// imported here for primitive API clarity.
typealias InputMode = net.vital.plugins.buildcore.core.events.InputMode
