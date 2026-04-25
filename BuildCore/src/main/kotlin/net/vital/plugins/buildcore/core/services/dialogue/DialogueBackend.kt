package net.vital.plugins.buildcore.core.services.dialogue

interface DialogueBackend
{
	suspend fun continueAll(): Boolean
	suspend fun chooseOption(matcher: String): Boolean
	suspend fun close(): Boolean
}
