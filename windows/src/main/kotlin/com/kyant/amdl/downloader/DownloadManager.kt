package com.kyant.amdl.downloader

import com.kyant.amdl.api.AmApi
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.api.AppleMusicHttpException
import com.kyant.amdl.api.Track
import com.kyant.amdl.engine.Cdm
import com.kyant.amdl.engine.M4aTagger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

class DownloadManager internal constructor(
    initialTokens: AmTokens,
    initialLanguage: String,
    dispatcher: CoroutineDispatcher,
    private val loadTracks: suspend (AmApi, String) -> List<Track> = { api, url -> api.getTracks(url) },
    private val processTask: (suspend (Task) -> Unit)? = null,
) : AutoCloseable {

    constructor(initialTokens: AmTokens, initialLanguage: String) : this(
        initialTokens, initialLanguage, Dispatchers.IO,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val channel = Channel<Task>(Channel.UNLIMITED)
    private val queueLock = Any()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val api = MutableStateFlow(AmApi(initialTokens, initialLanguage))

    private val mutableTasks = MutableStateFlow<List<Task>>(emptyList())
    private val mutableFailedTasks = MutableStateFlow<List<Task>>(emptyList())
    private val mutableCompletedTasks = MutableStateFlow<List<Task>>(emptyList())
    private val mutableIsParsing = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableAuthenticationRequired = MutableStateFlow(false)

    val tasks: StateFlow<List<Task>> = mutableTasks.asStateFlow()
    val failedTasks: StateFlow<List<Task>> = mutableFailedTasks.asStateFlow()
    val completedTasks: StateFlow<List<Task>> = mutableCompletedTasks.asStateFlow()
    val isParsing: StateFlow<Boolean> = mutableIsParsing.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val authenticationRequired: StateFlow<Boolean> = mutableAuthenticationRequired.asStateFlow()

    init {
        repeat(WorkerCount) {
            scope.launch {
                for (task in channel) executeTask(task)
            }
        }
    }

    fun updateTokens(tokens: AmTokens) {
        api.update { it.copy(tokens = tokens) }
        mutableAuthenticationRequired.value = false
    }

    fun updateLanguage(language: String) {
        api.update { it.copy(language = language) }
    }

    fun parseFromUrl(url: String, config: UserConfig) {
        if (closed.get()) {
            mutableError.value = "The download manager is closed."
            return
        }
        // Reserve before dispatch so a second UI callback cannot queue another parse.
        if (!mutableIsParsing.compareAndSet(false, true)) return
        mutableError.value = null
        scope.launch {
            try {
                val tracks = loadTracks(api.value, url)
                if (tracks.isEmpty()) {
                    throw IllegalArgumentException("The Apple Music link did not contain any downloadable tracks.")
                }
                addTasks(
                    tracks.map { track ->
                        Task(
                            id = UUID.randomUUID().toString(),
                            track = track,
                            config = config
                        )
                    }
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (failure.isFatal()) throw failure
                recordAuthenticationFailure(failure)
                mutableError.value = failure.userMessage("Could not read the Apple Music link.")
            }
        }.invokeOnCompletion {
            // Also runs if the scope is cancelled before the coroutine starts.
            mutableIsParsing.value = false
        }
    }

    fun clearError(expected: String? = mutableError.value) {
        mutableError.compareAndSet(expected, null)
    }

    fun retryTask(task: Task) {
        val retry = synchronized(queueLock) {
            if (closed.get()) return
            val match = mutableFailedTasks.value.firstOrNull { it.id == task.id }
                ?: return
            mutableFailedTasks.value = mutableFailedTasks.value.filterNot { it.id == match.id }
            match.status = Task.Status.Pending
            mutableTasks.value = mutableTasks.value + match
            match
        }
        enqueue(listOf(retry))
    }

    fun retryAllFailedTasks() {
        val retries = synchronized(queueLock) {
            if (closed.get()) return
            val retry = mutableFailedTasks.value
            mutableFailedTasks.value = emptyList()
            retry.onEach { it.status = Task.Status.Pending }
            mutableTasks.value = mutableTasks.value + retry
            retry
        }
        enqueue(retries)
    }

    fun clearAllFailedTasks() {
        synchronized(queueLock) { mutableFailedTasks.value = emptyList() }
    }

    fun clearCompletedTasks() {
        synchronized(queueLock) { mutableCompletedTasks.value = emptyList() }
    }

    private fun addTasks(newTasks: List<Task>) {
        if (newTasks.isEmpty()) return
        synchronized(queueLock) {
            mutableTasks.value = mutableTasks.value + newTasks
        }
        enqueue(newTasks)
    }

    private fun enqueue(tasksToRun: List<Task>) {
        for (task in tasksToRun) {
            if (closed.get()) {
                failClosedTask(task)
                continue
            }
            val result = channel.trySend(task)
            if (result.isFailure) failClosedTask(task)
        }
    }

    private fun failClosedTask(task: Task) {
        synchronized(queueLock) {
            mutableTasks.value = mutableTasks.value.filterNot { it.id == task.id }
            mutableError.value = "The download manager is closed."
        }
        task.status = Task.Status.Failed("The download manager is closed.")
    }

    private suspend fun executeTask(task: Task) {
        if (closed.get()) return
        try {
            validateConfig(task.config)
            task.status = Task.Status.Preparing
            if (processTask != null) processTask.invoke(task) else downloadAndSaveTask(task)
            task.status = Task.Status.Completed
            finishTask(task)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            recordAuthenticationFailure(failure)
            task.status = Task.Status.Failed(failure.userMessage("Download failed."))
            finishTask(task)
        }
    }

    private suspend fun downloadAndSaveTask(task: Task): Unit = coroutineScope {
        val taskApi = api.value.copy(language = task.config.language)
        val metadataJob = async { taskApi.getTrackMetadata(task.track) }
        val artworkJob = task.track.artwork?.let { artwork ->
            async { artwork.getArtworkData(taskApi) }
        }
        val audioJob = async {
            Cdm().use { cdm ->
                taskApi.getM4a(task.track.id, cdm, atmos = task.config.preferAtmos)
            }
        }

        task.status = Task.Status.Downloading
        val metadata = metadataJob.await()
        val artwork = artworkJob?.await()
        val m4aData = audioJob.await()

        task.status = Task.Status.Processing
        val taggedM4a = M4aTagger(m4aData).use { tagger ->
            metadata.setTag(tagger)
            artwork?.let(tagger::setArtwork)
            tagger.writeTag()
                ?: throw IllegalStateException("Failed to write M4A metadata.")
        }

        val relativeName = metadata.getFileName(task.config)
        val m4aFile = resolveOutputFile(task.config.downloadPath, "$relativeName.m4a")
        writeAtomically(m4aFile, taggedM4a)

        if (task.config.saveTtml) {
            metadata.syllableLyrics?.takeIf(String::isNotBlank)?.let { lyrics ->
                val ttmlFile = resolveOutputFile(task.config.downloadPath, "$relativeName.ttml")
                writeAtomically(ttmlFile, lyrics.toByteArray(UTF_8))
            }
        }
    }

    private fun finishTask(task: Task) {
        synchronized(queueLock) {
            if (closed.get()) return
            mutableTasks.value = mutableTasks.value.filterNot { it.id == task.id }
            when (task.status) {
                Task.Status.Completed -> {
                    if (mutableCompletedTasks.value.none { it.id == task.id }) {
                        mutableCompletedTasks.value = mutableCompletedTasks.value + task
                    }
                }

                is Task.Status.Failed -> {
                    if (mutableFailedTasks.value.none { it.id == task.id }) {
                        mutableFailedTasks.value = mutableFailedTasks.value + task
                    }
                }

                else -> Unit
            }
        }
    }

    private fun validateConfig(config: UserConfig) {
        require(config.downloadPath.isNotBlank()) { "Choose a download folder first." }
        require(config.language.isNotBlank()) { "Choose a metadata language first." }
    }

    private fun resolveOutputFile(downloadPath: String, relativeName: String): File {
        val root = File(downloadPath).toPath().toAbsolutePath().normalize()
        val target = root.resolve(relativeName).normalize()
        require(target.startsWith(root)) { "The generated file path escapes the download folder." }
        require(target.fileName.toString() !in setOf(".", "..")) { "Invalid download file name." }
        return target.toFile()
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val directory = target.parentFile
            ?: throw IllegalStateException("The download file has no parent directory.")
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), ".amdl-", ".tmp")
        try {
            Files.write(temporary, data)
            try {
                Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.close()
        scope.cancel()
        synchronized(queueLock) {
            mutableTasks.value = emptyList()
            mutableIsParsing.value = false
        }
    }

    private fun recordAuthenticationFailure(failure: Throwable) {
        if (failure is AppleMusicHttpException && failure.requiresLogin) {
            mutableAuthenticationRequired.value = true
        }
    }

    private fun Throwable.userMessage(fallback: String): String {
        return message?.trim()?.takeIf(String::isNotEmpty) ?: fallback
    }

    private fun Throwable.isFatal(): Boolean {
        return this is VirtualMachineError || this is ThreadDeath
    }

    private companion object {
        const val WorkerCount = 4
    }
}
