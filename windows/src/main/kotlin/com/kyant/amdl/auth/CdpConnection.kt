package com.kyant.amdl.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A small, serialized CDP client. It accepts only a loopback endpoint supplied by BrowserLogin. */
internal class CdpConnection private constructor(
    private val webSocket: WebSocket,
    private val eventHandler: (String) -> Unit
) : AutoCloseable {
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val sendGate = Semaphore(1, true)
    private val closed = AtomicBoolean(false)
    private val connectionFailure = AtomicReference<Throwable?>(null)

    val isOpen: Boolean
        get() = !closed.get() &&
            connectionFailure.get() == null &&
            !webSocket.isInputClosed &&
            !webSocket.isOutputClosed

    suspend fun command(
        method: String,
        params: JSONObject = JSONObject(),
        sessionId: String? = null
    ): JSONObject {
        if (!isOpen) throw BrowserLoginException("浏览器登录连接已断开，请重新登录。")

        val id = nextId.incrementAndGet()
        val response = CompletableFuture<JSONObject>()
        pending[id] = response
        val message = buildCommand(id, method, params, sessionId)
        try {
            sendText(message)
            val received = withTimeout(COMMAND_TIMEOUT_MILLIS) { response.awaitCompat() }
            if (received.has("error")) {
                throw BrowserLoginException("浏览器调试命令失败：$method。")
            }
            return received.optJSONObject("result") ?: JSONObject()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BrowserLoginException) {
            throw failure
        } catch (_: Exception) {
            throw BrowserLoginException("浏览器登录连接已断开，请重新登录。")
        } finally {
            pending.remove(id)
        }
    }

    fun requestBrowserClose() {
        if (!isOpen || !sendGate.tryAcquire(1, TimeUnit.SECONDS)) return
        try {
            val id = nextId.incrementAndGet()
            val message = buildCommand(id, "Browser.close", JSONObject(), null)
            webSocket.sendText(message, true).get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Owned process-tree cleanup is the fallback when graceful close is unavailable.
        } finally {
            sendGate.release()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        failPending()
        if (sendGate.tryAcquire(500, TimeUnit.MILLISECONDS)) {
            try {
                runCatching {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "AMDL login finished")
                        .get(1, TimeUnit.SECONDS)
                }
            } finally {
                sendGate.release()
            }
        }
        runCatching { webSocket.abort() }
    }

    private suspend fun sendText(message: String) {
        sendGate.acquire()
        try {
            withTimeout(SEND_TIMEOUT_MILLIS) {
                webSocket.sendText(message, true).awaitCompat()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BrowserLoginException("无法发送浏览器调试请求，请重新登录。")
        } finally {
            sendGate.release()
        }
    }

    private fun onMessage(value: String) {
        val message = runCatching { JSONObject(value) }.getOrNull() ?: return
        if (message.has("id")) {
            val id = message.optLong("id", -1)
            pending.remove(id)?.complete(message)
            return
        }
        if (message.has("method")) {
            runCatching { eventHandler(value) }
        }
    }

    private fun onConnectionFailure(value: Throwable) {
        if (closed.get()) return
        connectionFailure.compareAndSet(null, value)
        failPending()
    }

    private fun failPending() {
        val cause = connectionFailure.get() ?: IOException("CDP connection closed")
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private fun buildCommand(
        id: Long,
        method: String,
        params: JSONObject,
        sessionId: String?
    ): String {
        val message = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
        if (sessionId != null) message.put("sessionId", sessionId)
        return message.toString()
    }

    companion object {
        private const val COMMAND_TIMEOUT_MILLIS = 8_000L
        private const val SEND_TIMEOUT_MILLIS = 5_000L

        suspend fun open(uri: URI, eventHandler: (String) -> Unit): CdpConnection {
            val listener = CdpListener()
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(7))
                .buildAsync(uri, listener)
                .awaitCompat()
            val connection = CdpConnection(webSocket, eventHandler)
            listener.attach(connection::onMessage, connection::onConnectionFailure)
            return connection
        }
    }
}

private class CdpListener : WebSocket.Listener {
    private val parts = StringBuilder()
    private val queuedMessages = ConcurrentLinkedQueue<String>()
    private val mailbox = Any()
    private var messageHandler: ((String) -> Unit)? = null
    private var failureHandler: ((Throwable) -> Unit)? = null
    private var queuedFailure: Throwable? = null

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean
    ): CompletionStage<*>? {
        parts.append(data)
        if (last) {
            val completeMessage = parts.toString()
            parts.setLength(0)
            synchronized(mailbox) {
                val handler = messageHandler
                if (handler == null) queuedMessages.add(completeMessage) else handler(completeMessage)
            }
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String
    ): CompletionStage<*>? {
        deliverFailure(IOException("CDP input closed ($statusCode)"))
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        deliverFailure(error)
    }

    fun attach(messageSink: (String) -> Unit, failureSink: (Throwable) -> Unit) {
        val messages = ArrayList<String>()
        var failure: Throwable? = null
        synchronized(mailbox) {
            messageHandler = messageSink
            failureHandler = failureSink
            while (true) messages += queuedMessages.poll() ?: break
            failure = queuedFailure
            queuedFailure = null
        }
        messages.forEach(messageSink)
        failure?.let(failureSink)
    }

    private fun deliverFailure(error: Throwable) {
        val handler = synchronized(mailbox) {
            failureHandler ?: run {
                queuedFailure = error
                null
            }
        }
        handler?.invoke(error)
    }
}

private suspend fun <T> CompletableFuture<T>.awaitCompat(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (!continuation.isActive) return@whenComplete
            if (error == null) {
                continuation.resume(value)
            } else {
                val unwrapped = if (error is CompletionException && error.cause != null) {
                    error.cause!!
                } else {
                    error
                }
                continuation.resumeWithException(unwrapped)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
