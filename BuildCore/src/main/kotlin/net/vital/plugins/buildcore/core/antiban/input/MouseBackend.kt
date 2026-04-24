package net.vital.plugins.buildcore.core.antiban.input

/**
 * Backend for [Mouse] primitive. `currentPosition` is tracked client-side
 * because VitalAPI's [vital.api.input.Movement] exposes no cursor-position
 * accessor — we generate the trail, so we know where the cursor is.
 *
 * Spec §7.2.
 */
interface MouseBackend
{
	fun currentPosition(): Point
	fun appendTrailPoint(x: Int, y: Int)
	fun click(x: Int, y: Int, button: MouseButton)
}

/** Spec §7.2. */
interface KeyboardBackend
{
	fun keyDown(vk: Int)
	fun keyUp(vk: Int)
	fun tap(vk: Int)
	fun type(text: String)
}

/**
 * Backend for [Camera] primitive. Uses VitalAPI's absolute rotation model
 * (`rotation` ∈ 0..2047, `pitch` ∈ ~128..383), NOT relative degrees.
 *
 * Spec §7.2.
 */
interface CameraBackend
{
	fun currentRotation(): Int
	fun currentPitch(): Int
	fun rotateTo(targetRotation: Int)
	fun setPitchTo(targetPitch: Int)
}
