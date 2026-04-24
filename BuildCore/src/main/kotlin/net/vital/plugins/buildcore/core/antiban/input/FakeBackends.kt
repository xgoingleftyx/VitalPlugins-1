package net.vital.plugins.buildcore.core.antiban.input

/**
 * Recording test fixtures. All three fakes track calls in public mutable
 * state so tests can assert behavior directly (no mocking framework needed).
 *
 * Spec §7.2 (fake backends).
 */
class FakeMouseBackend : MouseBackend
{
	val trailPoints = mutableListOf<Point>()
	val clicks = mutableListOf<Triple<Int, Int, MouseButton>>()
	var position = Point(0, 0)

	override fun currentPosition(): Point = position
	override fun appendTrailPoint(x: Int, y: Int)
	{
		trailPoints += Point(x, y)
		position = Point(x, y)
	}
	override fun click(x: Int, y: Int, button: MouseButton)
	{
		clicks += Triple(x, y, button)
	}
}

class FakeKeyboardBackend : KeyboardBackend
{
	val keyDowns = mutableListOf<Int>()
	val keyUps = mutableListOf<Int>()
	val taps = mutableListOf<Int>()
	val typed = mutableListOf<String>()

	override fun keyDown(vk: Int) { keyDowns += vk }
	override fun keyUp(vk: Int) { keyUps += vk }
	override fun tap(vk: Int) { taps += vk }
	override fun type(text: String) { typed += text }
}

class FakeCameraBackend : CameraBackend
{
	var rotation = 0
	var pitch = 256
	val rotateToCalls = mutableListOf<Int>()
	val pitchToCalls = mutableListOf<Int>()

	override fun currentRotation(): Int = rotation
	override fun currentPitch(): Int = pitch
	override fun rotateTo(targetRotation: Int)
	{
		rotateToCalls += targetRotation
		rotation = targetRotation
	}
	override fun setPitchTo(targetPitch: Int)
	{
		pitchToCalls += targetPitch
		pitch = targetPitch
	}
}
