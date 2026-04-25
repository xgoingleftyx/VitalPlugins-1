package net.vital.plugins.buildcore.core.services

import net.vital.plugins.buildcore.core.confidence.ActionStakes
import net.vital.plugins.buildcore.core.confidence.ConfidenceGate
import net.vital.plugins.buildcore.core.confidence.ConfidenceTooLow
import net.vital.plugins.buildcore.core.events.ConfidenceUnderconfidentAction
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
 * Canonical wrapper for L5 service action methods. Centralises events,
 * restriction check, confidence gate (Plan 6a), timing, outcome classification.
 *
 * Plan 5a spec §4.4 + Plan 6a spec §5.3.
 */
internal inline fun <T> withServiceCall(
	bus: EventBus?,
	sessionIdProvider: () -> UUID,
	serviceName: String,
	methodName: String,
	restriction: OperationalRestriction? = null,
	stakes: ActionStakes = ActionStakes.MEDIUM,
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
		try { ConfidenceGate.check(stakes) }
		catch (e: ConfidenceTooLow)
		{
			outcome = ServiceOutcome.UNCONFIDENT
			bus?.tryEmit(ConfidenceUnderconfidentAction(
				sessionId = sid,
				serviceName = serviceName,
				methodName = methodName,
				required = e.required,
				current = e.current
			))
			throw e
		}
		val result = block()
		if (result == false || result == null) outcome = ServiceOutcome.FAILURE
		return result
	}
	catch (e: RestrictionViolation) { throw e }
	catch (e: ConfidenceTooLow) { throw e }
	catch (e: Throwable)
	{
		if (outcome == ServiceOutcome.SUCCESS) outcome = ServiceOutcome.EXCEPTION
		throw e
	}
	finally
	{
		val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
		bus?.tryEmit(ServiceCallEnd(sessionId = sid, serviceName = serviceName, methodName = methodName, callId = callId, durationMillis = durationMs, outcome = outcome))
	}
}
