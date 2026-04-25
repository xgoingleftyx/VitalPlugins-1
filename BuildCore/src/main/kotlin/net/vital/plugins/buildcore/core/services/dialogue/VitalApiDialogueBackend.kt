package net.vital.plugins.buildcore.core.services.dialogue

/**
 * Default [DialogueBackend]. No standardised dialogue API in VitalAPI 5a —
 * all methods are stubbed until a widget-based implementation is wired.
 * Plan 5a spec §5.
 */
object VitalApiDialogueBackend : DialogueBackend
{
	override suspend fun continueAll(): Boolean =
		error("not implemented in 5a; wire widget-based dialogue when consumer needs it")

	override suspend fun chooseOption(matcher: String): Boolean =
		error("not implemented in 5a; wire widget-based dialogue when consumer needs it")

	override suspend fun close(): Boolean =
		error("not implemented in 5a; wire widget-based dialogue when consumer needs it")
}
