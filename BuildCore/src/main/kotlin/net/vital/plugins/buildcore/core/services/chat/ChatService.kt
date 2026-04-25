package net.vital.plugins.buildcore.core.services.chat

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object ChatService
{
	@Volatile internal var backend: ChatBackend = VitalApiChatBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun send(text: String): Boolean =
		withServiceCall(bus, sessionIdProvider, "ChatService", "send",
			stakes = ActionStakes.MEDIUM) { backend.send(text) }

	suspend fun sendChannel(channel: ChatChannel, text: String): Boolean =
		withServiceCall(bus, sessionIdProvider, "ChatService", "sendChannel",
			stakes = ActionStakes.MEDIUM) { backend.sendChannel(channel, text) }

	internal fun resetForTests()
	{
		backend = VitalApiChatBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
