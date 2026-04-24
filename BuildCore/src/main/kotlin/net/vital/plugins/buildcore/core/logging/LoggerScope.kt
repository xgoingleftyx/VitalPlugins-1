package net.vital.plugins.buildcore.core.logging

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dedicated single-thread coroutine scope that hosts every fast-path
 * log subscriber. Because only one thread services collect { } bodies,
 * writes across subscribers are serialised for free — no mutex around
 * file writes; subscriber ordering per event is deterministic.
 *
 * Spec §4.2.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class LoggerScope : AutoCloseable {

	internal val dispatcher: ExecutorCoroutineDispatcher = newSingleThreadContext("buildcore-logger")

	private val job = SupervisorJob()

	val coroutineScope: CoroutineScope = CoroutineScope(dispatcher + job + CoroutineName("LoggerScope"))

	/**
	 * Wait up to [deadlineMillis] for all launched collectors to complete.
	 * If the deadline expires, [close] cancels them; writers' finally
	 * blocks are still invoked.
	 */
	suspend fun drain(deadlineMillis: Long = 500) {
		withTimeoutOrNull(deadlineMillis) {
			job.children.forEach { it.join() }
		}
	}

	override fun close() {
		job.cancel()
		dispatcher.close()
	}
}
