package net.vital.plugins.buildcore.core.services.login

interface LoginBackend
{
	suspend fun login(): Boolean
	suspend fun logout(): Boolean
}
