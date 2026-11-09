package com.kyant.amdl.downloader

import com.kyant.amdl.api.AmApi
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.api.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadManagerUiStateTest {
    private val config = UserConfig("en-US", false, "unused-test-output", false, false, false)

    @Test
    fun `single retry is active and pending before dispatch and is queued only once`() {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        Fixture(
            loadTracks = { _, _ -> listOf(track("one")) },
            processTask = {
                if (++attempts == 1) error("first attempt failed")
                release.await()
            },
        ).use { fixture ->
            val manager = fixture.manager
            manager.parseFromUrl("song", config)
            fixture.dispatcher.runCurrent()
            val failed = manager.failedTasks.value.single()
            assertTrue(manager.tasks.value.isEmpty())

            manager.retryTask(failed)
            manager.retryTask(failed)
            manager.retryAllFailedTasks()
            assertSame(failed, manager.tasks.value.single())
            assertEquals(Task.Status.Pending, failed.status)
            assertTrue(manager.failedTasks.value.isEmpty())

            fixture.dispatcher.runCurrent()
            assertEquals(2, attempts)
            assertSame(failed, manager.tasks.value.single())
            release.complete(Unit)
            fixture.dispatcher.runCurrent()
            assertEquals(2, attempts)
            assertTrue(manager.tasks.value.isEmpty())
            assertSame(failed, manager.completedTasks.value.single())
        }
    }

    @Test
    fun `bulk retry restores every failed task to active without double queueing`() {
        val release = CompletableDeferred<Unit>()
        val attempts = mutableMapOf<String, Int>()
        Fixture(
            loadTracks = { _, _ -> listOf(track("one"), track("two")) },
            processTask = { task ->
                val count = attempts.getOrDefault(task.id, 0) + 1
                attempts[task.id] = count
                if (count == 1) error("first attempt failed")
                release.await()
            },
        ).use { fixture ->
            val manager = fixture.manager
            manager.parseFromUrl("album", config)
            fixture.dispatcher.runCurrent()
            val failed = manager.failedTasks.value.toList()
            assertEquals(2, failed.size)

            manager.retryAllFailedTasks()
            manager.retryAllFailedTasks()
            failed.forEach(manager::retryTask)
            assertEquals(failed.map { it.id }.toSet(), manager.tasks.value.map { it.id }.toSet())
            assertEquals(2, manager.tasks.value.size)
            assertTrue(manager.tasks.value.all { it.status == Task.Status.Pending })
            assertTrue(manager.failedTasks.value.isEmpty())

            fixture.dispatcher.runCurrent()
            assertEquals(listOf(2, 2), attempts.values.sorted())
            assertEquals(2, manager.tasks.value.size)
            release.complete(Unit)
            fixture.dispatcher.runCurrent()
            assertEquals(listOf(2, 2), attempts.values.sorted())
            assertTrue(manager.tasks.value.isEmpty())
            assertEquals(2, manager.completedTasks.value.size)
        }
    }

    @Test
    fun `parse reserves synchronously and rejects duplicates before dispatch and while suspended`() {
        val release = CompletableDeferred<Unit>()
        val loadedUrls = mutableListOf<String>()
        Fixture(
            loadTracks = { _, url ->
                loadedUrls += url
                release.await()
                listOf(track("one"))
            },
        ).use { fixture ->
            val manager = fixture.manager
            manager.parseFromUrl("first", config)
            assertTrue(manager.isParsing.value)
            assertTrue(loadedUrls.isEmpty())
            manager.parseFromUrl("duplicate before dispatch", config)
            fixture.dispatcher.runCurrent()
            manager.parseFromUrl("duplicate while suspended", config)
            fixture.dispatcher.runCurrent()
            assertEquals(listOf("first"), loadedUrls)
            assertTrue(manager.isParsing.value)

            release.complete(Unit)
            fixture.dispatcher.runCurrent()
            assertFalse(manager.isParsing.value)
            assertEquals(1, manager.completedTasks.value.size)

            manager.parseFromUrl("next submission", config)
            fixture.dispatcher.runCurrent()
            assertEquals(listOf("first", "next submission"), loadedUrls)
            assertFalse(manager.isParsing.value)
            assertEquals(2, manager.completedTasks.value.size)
        }
    }

    @Test
    fun `parse failures release reservation and stale acknowledgements preserve the newer error`() {
        Fixture(loadTracks = { _, url -> error(url) }).use { fixture ->
            val manager = fixture.manager
            manager.parseFromUrl("old error", config)
            fixture.dispatcher.runCurrent()
            val oldError = manager.error.value
            assertEquals("old error", oldError)
            assertFalse(manager.isParsing.value)

            manager.parseFromUrl("new error", config)
            fixture.dispatcher.runCurrent()
            assertEquals("new error", manager.error.value)
            assertFalse(manager.isParsing.value)
            manager.clearError(oldError)
            manager.clearError(null)
            assertEquals("new error", manager.error.value)
            manager.clearError("new error")
            assertNull(manager.error.value)

            manager.parseFromUrl("another error", config)
            fixture.dispatcher.runCurrent()
            manager.clearError()
            assertNull(manager.error.value)
        }
    }

    @Test
    fun `closing before dispatch releases parse reservation without starting any work`() {
        var loads = 0
        Fixture(loadTracks = { _, _ -> loads++; listOf(track("one")) }).use { fixture ->
            fixture.manager.parseFromUrl("song", config)
            assertTrue(fixture.manager.isParsing.value)
            fixture.manager.close()
            fixture.dispatcher.runCurrent()
            assertFalse(fixture.manager.isParsing.value)
            assertEquals(0, loads)
            assertTrue(fixture.manager.tasks.value.isEmpty())
        }
    }

    private fun track(id: String) = Track("us", id, "Test song", "Test artist", null)

    private class Fixture(
        loadTracks: suspend (AmApi, String) -> List<Track>,
        processTask: suspend (Task) -> Unit = {},
    ) : AutoCloseable {
        val dispatcher = QueuedDispatcher()
        val manager = DownloadManager(
            initialTokens = AmTokens("test-developer", "test-user"),
            initialLanguage = "en-US",
            dispatcher = dispatcher,
            loadTracks = loadTracks,
            processTask = processTask,
        )

        override fun close() {
            manager.close()
            dispatcher.runCurrent()
        }
    }

    // Explicitly drains coroutine work without threads, timers or an extra test dependency.
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runCurrent() {
            var remaining = 1000
            while (queued.isNotEmpty()) {
                check(remaining-- > 0) { "Coroutine work did not settle" }
                queued.removeFirst().run()
            }
        }
    }
}
