package net.vital.plugins.buildcore.core.antiban.input

import vital.api.input.Keyboard as VitalKeyboard

/**
 * Real [KeyboardBackend] backed by [vital.api.input.Keyboard].
 *
 * Spec §7.3.
 */
internal object VitalApiKeyboardBackend : KeyboardBackend
{
	override fun keyDown(vk: Int)   = VitalKeyboard.keyDown(vk)
	override fun keyUp(vk: Int)     = VitalKeyboard.keyUp(vk)
	override fun tap(vk: Int)       = VitalKeyboard.tap(vk)
	override fun type(text: String) = VitalKeyboard.type(text)
}
