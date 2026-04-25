package net.vital.plugins.buildcore.core.services.chat

interface ChatBackend
{
	suspend fun send(text: String): Boolean
	suspend fun sendChannel(channel: ChatChannel, text: String): Boolean
}
