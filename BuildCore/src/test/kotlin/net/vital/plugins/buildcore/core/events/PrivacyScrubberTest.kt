package net.vital.plugins.buildcore.core.events

import net.vital.plugins.buildcore.core.events.FatigueUpdated
import net.vital.plugins.buildcore.core.events.InputAction
import net.vital.plugins.buildcore.core.events.InputKind
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import net.vital.plugins.buildcore.core.events.SessionRngSeeded
import net.vital.plugins.buildcore.core.events.PrecisionModeEntered
import net.vital.plugins.buildcore.core.events.PrecisionModeExited
import net.vital.plugins.buildcore.core.events.InputMode
import net.vital.plugins.buildcore.core.events.BreakScheduled
import net.vital.plugins.buildcore.core.events.BreakStarted
import net.vital.plugins.buildcore.core.events.BreakEnded
import net.vital.plugins.buildcore.core.events.BreakDeferred
import net.vital.plugins.buildcore.core.events.BreakDropped
import net.vital.plugins.buildcore.core.events.BreakRescheduled
import net.vital.plugins.buildcore.core.events.BreakPreempted
import net.vital.plugins.buildcore.core.events.BreakTier
import net.vital.plugins.buildcore.core.events.EarlyStopRequested
import net.vital.plugins.buildcore.core.events.EarlyStopReason
import net.vital.plugins.buildcore.core.events.Misclick
import net.vital.plugins.buildcore.core.events.MisclickKind
import net.vital.plugins.buildcore.core.events.SemanticMisclick
import net.vital.plugins.buildcore.core.events.ServiceCallStart
import net.vital.plugins.buildcore.core.events.ServiceCallEnd
import net.vital.plugins.buildcore.core.events.ServiceOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PrivacyScrubberTest {

	private val sid = UUID.randomUUID()
	private val tid = UUID.randomUUID()

	@Test
	fun `scrubs password tokens from task failure detail`() {
		val input = TaskFailed(
			sessionId = sid, taskInstanceId = tid, taskId = "login",
			reasonType = "EXCEPTION",
			reasonDetail = "login failed: username=alice password=s3cret retry later",
			attemptNumber = 1
		)
		val out = PrivacyScrubber.scrub(input) as TaskFailed
		assertFalse(out.reasonDetail.contains("s3cret"), "password survived scrub")
		assertTrue(out.reasonDetail.contains("password=«redacted»"))
		assertFalse(out.reasonDetail.contains("alice"), "username survived scrub")
	}

	@Test
	fun `leaves events without free-form text untouched`() {
		val input = TaskStarted(
			sessionId = sid, taskInstanceId = tid, taskId = "x", methodId = "m", pathId = "p"
		)
		val out = PrivacyScrubber.scrub(input)
		assertEquals(input, out)
	}

	@Test
	fun `idempotent — scrub(scrub(e)) equals scrub(e)`() {
		val input = TaskFailed(
			sessionId = sid, taskInstanceId = tid, taskId = "t",
			reasonType = "EX", reasonDetail = "token=abc123 username=bob",
			attemptNumber = 1
		)
		val once = PrivacyScrubber.scrub(input)
		val twice = PrivacyScrubber.scrub(once)
		assertEquals(once, twice)
	}

	@Test
	fun `scrubs stack trace from UnhandledException`() {
		val input = UnhandledException(
			sessionId = sid,
			threadName = "main",
			exceptionClass = "RuntimeException",
			message = "password=hunter2 is wrong",
			stackTrace = "at com.example.auth(Auth.java:42) username=carol"
		)
		val out = PrivacyScrubber.scrub(input) as UnhandledException
		assertFalse(out.message.contains("hunter2"))
		assertFalse(out.stackTrace.contains("carol"))
	}

	@Test
	fun `every BusEvent subtype returns without throwing`() {
		// Belt-and-braces — the exhaustive when inside scrub should already guarantee this.
		val samples: List<BusEvent> = listOf(
			SessionStart(sessionId = sid, buildcoreVersion = "0.0.0"),
			SessionEnd(sessionId = sid, reason = StopReason.USER),
			SessionSummary(sessionId = sid, durationMillis = 0, taskCounts = emptyMap(), totalEvents = 0),
			TaskQueued(sessionId = sid, taskInstanceId = tid, taskId = "t"),
			TaskValidated(sessionId = sid, taskInstanceId = tid, taskId = "t", pass = true),
			TaskStarted(sessionId = sid, taskInstanceId = tid, taskId = "t", methodId = "m", pathId = "p"),
			TaskProgress(sessionId = sid, taskInstanceId = tid, taskId = "t"),
			TaskCompleted(sessionId = sid, taskInstanceId = tid, taskId = "t", durationMillis = 1),
			TaskFailed(sessionId = sid, taskInstanceId = tid, taskId = "t", reasonType = "X", reasonDetail = "y", attemptNumber = 1),
			TaskRetrying(sessionId = sid, taskInstanceId = tid, taskId = "t", attemptNumber = 2, backoffMillis = 10),
			TaskSkipped(sessionId = sid, taskInstanceId = tid, taskId = "t", reason = "r"),
			TaskPaused(sessionId = sid, taskInstanceId = tid, taskId = "t", reason = "r"),
			TaskResumed(sessionId = sid, taskInstanceId = tid, taskId = "t"),
			MethodPicked(sessionId = sid, taskInstanceId = tid, taskId = "t", methodId = "m"),
			PathPicked(sessionId = sid, taskInstanceId = tid, taskId = "t", pathId = "p", pathKind = "IRONMAN"),
			SafeStopRequested(sessionId = sid, reason = "r"),
			SafeStopCompleted(sessionId = sid, durationMillis = 1),
			UnhandledException(sessionId = sid, threadName = "main", exceptionClass = "E", message = "m", stackTrace = "s"),
			ValidationFailed(sessionId = sid, subject = "Plan", detail = "bad"),
			RestrictionViolated(sessionId = sid, restrictionId = "r", effectSummary = "e", moment = RestrictionMoment.EDIT),
			PerformanceSample(sessionId = sid, intervalSeconds = 300, eventRatePerSec = 1.0,
				jvmHeapUsedMb = 100, jvmHeapMaxMb = 1000, loggerLagMaxMs = 0, droppedEventsSinceLastSample = 0),
			SubscriberOverflowed(sessionId = sid, subscriberName = "telemetry", droppedCount = 3),
			TestPing(sessionId = sid, payload = "hi"),
			InputAction(sessionId = sid, kind = InputKind.MOUSE_CLICK, durationMillis = 5),
			FatigueUpdated(sessionId = sid, sessionAgeMillis = 0,
				reactionMultiplier = 1.0, misclickMultiplier = 1.0,
				overshootVarianceMultiplier = 1.0, fidgetRateMultiplier = 1.0),
			PersonalityResolved(sessionId = sid, usernameHash = "abc123def456", generated = true),
			SessionRngSeeded(sessionId = sid, seed = 42L),
			PrecisionModeEntered(sessionId = sid, scopeId = 1L, mode = InputMode.PRECISION),
			PrecisionModeExited (sessionId = sid, scopeId = 1L, mode = InputMode.PRECISION, durationMillis = 250L),
			BreakScheduled      (sessionId = sid, tier = BreakTier.MICRO,    fireAtEpochMs = 1_000L),
			BreakStarted        (sessionId = sid, tier = BreakTier.NORMAL,   plannedDurationMillis = 60_000L),
			BreakEnded          (sessionId = sid, tier = BreakTier.NORMAL,   actualDurationMillis = 58_000L),
			BreakDeferred       (sessionId = sid, tier = BreakTier.NORMAL,   deferredMillis = 5_000L),
			BreakDropped        (sessionId = sid, tier = BreakTier.MICRO,    deferredMillis = 60_000L),
			BreakRescheduled    (sessionId = sid, tier = BreakTier.NORMAL,   newFireAtEpochMs = 2_000L),
			BreakPreempted      (sessionId = sid, tier = BreakTier.MICRO,    remainingMillis = 10_000L),
			EarlyStopRequested  (sessionId = sid, reason = EarlyStopReason.BEDTIME),
			Misclick            (sessionId = sid, kind = MisclickKind.PIXEL_JITTER, intendedX = 100, intendedY = 200, actualX = 101, actualY = 199, corrected = false),
			SemanticMisclick    (sessionId = sid, context = "useItemOn", intended = "feather", actual = "fishing-rod"),
			ServiceCallStart(sessionId = sid, serviceName = "BankService", methodName = "open", callId = 1L),
			ServiceCallEnd  (sessionId = sid, serviceName = "BankService", methodName = "open", callId = 1L, durationMillis = 12L, outcome = ServiceOutcome.SUCCESS)
		)
		// Must cover all 41 current subtypes — update this list when Plans 5a/6/8 add new ones.
		assertEquals(41, samples.size, "update the sample list when a new BusEvent subtype is added")
		samples.forEach { PrivacyScrubber.scrub(it) }  // just assert no throw
	}
}
