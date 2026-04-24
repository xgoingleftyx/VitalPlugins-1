package net.vital.plugins.buildcore.core.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class BusEventInterfaceTest {

	private val sid = UUID.randomUUID()
	private val tid = UUID.randomUUID()

	@Test
	fun `TaskStarted exposes correlation IDs via interface fields`() {
		val e: BusEvent = TaskStarted(
			sessionId = sid,
			taskInstanceId = tid,
			taskId = "demo",
			methodId = "m1",
			pathId = "p1"
		)
		assertEquals(tid, e.taskInstanceId)
		assertNull(e.moduleId)
	}

	@Test
	fun `events without task context expose nullable taskInstanceId as null`() {
		val ping: BusEvent = TestPing(sessionId = sid, payload = "hi")
		assertNull(ping.taskInstanceId)
		assertNull(ping.moduleId)
	}
}
