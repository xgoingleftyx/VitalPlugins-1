package net.vital.plugins.buildcore.core.services.login

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.services.OperationalRestriction
import net.vital.plugins.buildcore.core.services.withServiceCall
import java.util.UUID

object LoginService
{
	@Volatile internal var backend: LoginBackend = VitalApiLoginBackend
	@Volatile internal var bus: EventBus? = null
	@Volatile internal var sessionIdProvider: () -> UUID = { UUID(0, 0) }

	suspend fun login(): Boolean = withServiceCall(bus, sessionIdProvider, "LoginService", "login",
		restriction = OperationalRestriction.LOGIN_DISABLED) { backend.login() }

	suspend fun logout(): Boolean = withServiceCall(bus, sessionIdProvider, "LoginService", "logout",
		restriction = OperationalRestriction.LOGIN_DISABLED) { backend.logout() }

	internal fun resetForTests()
	{
		backend = VitalApiLoginBackend
		bus = null
		sessionIdProvider = { UUID(0, 0) }
	}
}
