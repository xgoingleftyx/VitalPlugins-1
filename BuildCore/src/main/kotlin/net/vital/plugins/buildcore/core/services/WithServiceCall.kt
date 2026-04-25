package net.vital.plugins.buildcore.core.services

import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceCallStart
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal object ServiceCallContext
{
	private val counter = AtomicLong(1L)
	fun nextCallId(): Long = counter.getAndIncrement()
	internal fun resetForTests() { counter.set(1L) }
}

/**
 * The canonical wrapper shape for every L5 service action method. Centralises
 * event emission, restriction checking, timing, and outcome classification.
 *
 * Plan 5a spec §4.4.
 */
internal inline fun <T> withServiceCall(
	bus: EventBus?,
	sessionIdProvider: () -> UUID,
	serviceName: String,
	methodName: String,
	restriction: OperationalRestriction? = null,
	block: () -> T
): T
{
	val callId = ServiceCallContext.nextCallId()
	val sid = sessionIdProvider()
	bus?.tryEmit(ServiceCallStart(sessionId = sid, serviceName = serviceName, methodName = methodName, callId = callId))
	val startNanos = System.nanoTime()
	var outcome = ServiceOutcome.SUCCESS
	try
	{
		if (restriction != null)
		{
			try { RestrictionGate.check(restriction) }
			catch (e: RestrictionViolation) { outcome = ServiceOutcome.RESTRICTED; throw e }
		}
		val result = block()
		if (result == false || result == null) outcome = ServiceOutcome.FAILURE
		return result
	}
	catch (e: RestrictionViolation) { throw e }
	catch (e: Throwable)
	{
		if (outcome != ServiceOutcome.RESTRICTED) outcome = ServiceOutcome.EXCEPTION
		throw e
	}
	finally
	{
		val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
		bus?.tryEmit(ServiceCallEnd(sessionId = sid, serviceName = serviceName, methodName = methodName, callId = callId, durationMillis = durationMs, outcome = outcome))
	}
}
