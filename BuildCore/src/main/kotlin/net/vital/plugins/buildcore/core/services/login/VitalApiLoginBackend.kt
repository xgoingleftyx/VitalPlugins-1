package net.vital.plugins.buildcore.core.services.login

/**
 * Default [LoginBackend] delegating to VitalAPI.
 * Plan 5a spec §5.
 */
object VitalApiLoginBackend : LoginBackend
{
	override suspend fun login(): Boolean =
		error("not implemented in 5a; wire when autologin plugin exposes a callable API")

	override suspend fun logout(): Boolean =
		error("not implemented in 5a; wire when autologin plugin exposes a callable API")
}
