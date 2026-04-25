package net.vital.plugins.buildcore.core.services.dialogue

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object DialogueService
{
	@Volatile internal var backend: DialogueBackend = VitalApiDialogueBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun continueAll(): Boolean =
		withServiceCall(bus, sessionIdProvider, "DialogueService", "continueAll",
			stakes = ActionStakes.MEDIUM) { backend.continueAll() }

	suspend fun chooseOption(matcher: String): Boolean =
		withServiceCall(bus, sessionIdProvider, "DialogueService", "chooseOption",
			stakes = ActionStakes.MEDIUM) { backend.chooseOption(matcher) }

	suspend fun close(): Boolean =
		withServiceCall(bus, sessionIdProvider, "DialogueService", "close",
			stakes = ActionStakes.MEDIUM) { backend.close() }

	internal fun resetForTests()
	{
		backend = VitalApiDialogueBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
