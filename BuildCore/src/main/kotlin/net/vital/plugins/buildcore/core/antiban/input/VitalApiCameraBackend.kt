package net.vital.plugins.buildcore.core.antiban.input

import vital.api.input.Camera as VitalCamera

/**
 * Real [CameraBackend] backed by [vital.api.input.Camera]. Uses VitalAPI's
 * absolute rotation model (0-2047 yaw units, ~128-383 pitch units).
 *
 * Spec §7.3.
 */
internal object VitalApiCameraBackend : CameraBackend
{
	override fun currentRotation(): Int = VitalCamera.getRotation()
	override fun currentPitch(): Int = VitalCamera.getPitch()
	override fun rotateTo(targetRotation: Int) = VitalCamera.rotateTo(targetRotation)
	override fun setPitchTo(targetPitch: Int) = VitalCamera.setPitchTo(targetPitch)
}
