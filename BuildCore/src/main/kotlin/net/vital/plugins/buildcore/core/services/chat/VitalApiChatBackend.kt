package net.vital.plugins.buildcore.core.services.chat

/**
 * Default [ChatBackend]. No direct chat send API in VitalAPI 5a —
 * both methods are stubbed until a widget/input-based implementation is wired.
 * Plan 5a spec §5.
 */
object VitalApiChatBackend : ChatBackend
{
	override suspend fun send(text: String): Boolean =
		error("not implemented in 5a; wire chat input when consumer needs it")

	override suspend fun sendChannel(channel: ChatChannel, text: String): Boolean =
		error("not implemented in 5a; wire chat input when consumer needs it")
}
