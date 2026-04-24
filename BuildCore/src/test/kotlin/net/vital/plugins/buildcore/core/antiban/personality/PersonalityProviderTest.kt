package net.vital.plugins.buildcore.core.antiban.personality

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vital.plugins.buildcore.core.antiban.rng.SessionRng
import net.vital.plugins.buildcore.core.events.EventBus
import net.vital.plugins.buildcore.core.events.PersonalityResolved
import net.vital.plugins.buildcore.core.logging.LoggerScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class PersonalityProviderTest {

	@Test
	fun `forUsername generates + persists on first call, loads on second`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val sid = UUID.randomUUID()
		val store = PersonalityStore(tmp)
		val provider = PersonalityProvider(store, bus, sessionIdProvider = { sid })

		val p1 = provider.forUsername("chich")
		// Second call hits the cache (same JVM); also ensure disk has the file
		val p2 = provider.forUsername("chich")
		assertEquals(p1, p2)
		assertTrue(java.nio.file.Files.list(tmp).use { stream -> stream.collect(java.util.stream.Collectors.toList()) }.isNotEmpty())
	}

	@Test
	fun `forUsername emits PersonalityResolved with generated=true on fresh create`(@TempDir tmp: Path) = runBlocking {
		val bus = EventBus()
		val scope = LoggerScope()
		val sid = UUID.randomUUID()
		val store = PersonalityStore(tmp)
		val provider = PersonalityProvider(store, bus, sessionIdProvider = { sid })

		val latch = CountDownLatch(1)
		val captured = AtomicReference<PersonalityResolved>()
		scope.coroutineScope.launch {
			bus.events
				.onStart { latch.countDown() }
				.filterIsInstance<PersonalityResolved>()
				.first()
				.also { captured.set(it) }
		}
		latch.await()

		provider.forUsername("chich")

		withTimeout(2000) {
			while (captured.get() == null) kotlinx.coroutines.yield()
		}
		val event = captured.get()
		assertNotNull(event)
		assertTrue(event.generated)
		scope.close()
	}

	@Test
	fun `ephemeral returns same vector on repeated calls`(@TempDir tmp: Path) {
		val bus = EventBus()
		val sid = UUID.randomUUID()
		val provider = PersonalityProvider(PersonalityStore(tmp), bus, sessionIdProvider = { sid })
		val rng = SessionRng.fromSeed(42L)
		val a = provider.ephemeral(rng)
		val b = provider.ephemeral(rng)
		assertEquals(a, b)
	}
}
