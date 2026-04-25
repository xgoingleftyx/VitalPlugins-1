package net.vital.plugins.buildcore.core.services.world

interface WorldBackend
{
	suspend fun hop(targetWorld: Int): Boolean
}
