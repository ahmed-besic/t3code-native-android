package com.t3tools.android.protocol

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RpcFailure(val causes: JsonArray) : RuntimeException(causes.failureMessage())
class RpcDefect(val defect: JsonElement) : RuntimeException("The server rejected this request.")
class RpcProtocolException(message: String) : RuntimeException(message)
class RpcTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun JsonArray.failureMessage(): String = firstNotNullOfOrNull { cause ->
  val value = cause as? JsonObject ?: return@firstNotNullOfOrNull null
  val error = value["error"] as? JsonObject
  error?.get("message")?.jsonPrimitive?.contentOrNull
    ?: value["defect"]?.let { defect ->
      (defect as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
    }
} ?: "The server rejected this request."

private const val REQUEST_ID_FIELD = "\"requestId\""
private const val TERMINAL_HISTORY_FIELD = "\"history\""
private const val MAX_ENVELOPE_PREFIX_CHARACTERS = 1_024
private val TERMINAL_SNAPSHOT_METHODS = setOf("terminal.attach", "terminal.open", "terminal.restart")

internal fun trimTerminalHistoryInRpcMessage(
  message: String,
  maxHistoryCharacters: Int = DEFAULT_MAX_TERMINAL_BUFFER_BYTES,
): String {
  if (message.length <= maxHistoryCharacters) return message
  var searchFrom = 0
  while (true) {
    val fieldStart = message.indexOf(TERMINAL_HISTORY_FIELD, searchFrom)
    if (fieldStart < 0) return message
    searchFrom = fieldStart + TERMINAL_HISTORY_FIELD.length
    if (message.isEscapedAt(fieldStart)) continue

    var valueStart = searchFrom
    while (valueStart < message.length && message[valueStart].isWhitespace()) valueStart += 1
    if (message.getOrNull(valueStart) != ':') continue
    valueStart += 1
    while (valueStart < message.length && message[valueStart].isWhitespace()) valueStart += 1
    if (message.getOrNull(valueStart) != '"') continue
    valueStart += 1

    val valueEnd = message.jsonStringEnd(valueStart) ?: return message
    if (valueEnd - valueStart <= maxHistoryCharacters) {
      searchFrom = valueEnd + 1
      continue
    }
    val retainedStart = message.jsonStringTokenStart(
      valueStart,
      valueEnd - maxHistoryCharacters.coerceAtLeast(0),
      valueEnd,
    )
    return buildString(message.length - retainedStart + valueStart) {
      append(message, 0, valueStart)
      append(message, retainedStart, message.length)
    }
  }
}

private fun String.isEscapedAt(index: Int): Boolean {
  var backslashes = 0
  var cursor = index - 1
  while (cursor >= 0 && this[cursor] == '\\') {
    backslashes += 1
    cursor -= 1
  }
  return backslashes % 2 == 1
}

private fun String.jsonStringEnd(start: Int): Int? {
  var cursor = start
  while (cursor < length) {
    when (this[cursor]) {
      '"' -> return cursor
      '\\' -> cursor = jsonStringTokenEnd(cursor, length)
      else -> cursor += if (
        this[cursor].isHighSurrogate() && getOrNull(cursor + 1)?.isLowSurrogate() == true
      ) 2 else 1
    }
  }
  return null
}

private fun String.jsonStringTokenStart(start: Int, target: Int, end: Int): Int {
  var cursor = start
  while (cursor < target) cursor = jsonStringTokenEnd(cursor, end)
  return cursor
}

private fun String.jsonStringTokenEnd(start: Int, end: Int): Int {
  if (this[start] == '\\') {
    val escapedCharacters = if (getOrNull(start + 1) == 'u') 6 else 2
    return (start + escapedCharacters).coerceAtMost(end)
  }
  return if (this[start].isHighSurrogate() && getOrNull(start + 1)?.isLowSurrogate() == true) {
    (start + 2).coerceAtMost(end)
  } else {
    start + 1
  }
}

private fun rpcEnvelopeRequestId(message: String): Long? {
  val fieldStart = message.indexOf(REQUEST_ID_FIELD)
  if (fieldStart !in 0..MAX_ENVELOPE_PREFIX_CHARACTERS) return null
  var valueStart = fieldStart + REQUEST_ID_FIELD.length
  while (valueStart < message.length && message[valueStart].isWhitespace()) valueStart += 1
  if (message.getOrNull(valueStart) != ':') return null
  valueStart += 1
  while (valueStart < message.length && message[valueStart].isWhitespace()) valueStart += 1
  var valueEnd = valueStart
  while (message.getOrNull(valueEnd)?.isDigit() == true) valueEnd += 1
  return message.substring(valueStart, valueEnd).toLongOrNull()
}

class EffectRpcSession private constructor(
  private val http: OkHttpClient,
  private val json: Json,
  private val keepAliveIntervalMillis: Long,
) : AutoCloseable {
  private sealed interface Pending {
    val method: String

    class Unary(
      override val method: String,
      val result: CompletableDeferred<JsonElement>,
    ) : Pending

    class Stream(
      override val method: String,
      val values: Channel<JsonElement>,
    ) : Pending
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val opened = CompletableDeferred<Unit>()
  private val closed = CompletableDeferred<Throwable?>()
  private val requestIds = AtomicLong(0)
  private val missedPongs = AtomicInteger(0)
  private val pending = ConcurrentHashMap<Long, Pending>()
  private lateinit var socket: WebSocket

  private val listener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      opened.complete(Unit)
      startKeepAlive()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      runCatching { handle(json.parseToJsonElement(prepareIncomingMessage(text)).jsonObject) }
        .onFailure { failProtocol(it) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      finish(RpcTransportException("WebSocket closed ($code): $reason"))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      val failure = RpcTransportException("WebSocket transport failed.", t)
      opened.completeExceptionally(failure)
      finish(failure)
    }
  }

  suspend fun unary(tag: String, payload: JsonElement = JsonObject(emptyMap())): JsonElement {
    val id = requestIds.incrementAndGet()
    val result = CompletableDeferred<JsonElement>()
    pending[id] = Pending.Unary(tag, result)
    sendRequest(id, tag, payload)
    return try {
      result.await()
    } finally {
      if (pending.remove(id) != null) sendInterrupt(id)
    }
  }

  fun stream(tag: String, payload: JsonElement = JsonObject(emptyMap())): Flow<JsonElement> = flow {
    val id = requestIds.incrementAndGet()
    val values = Channel<JsonElement>(Channel.UNLIMITED)
    pending[id] = Pending.Stream(tag, values)
    sendRequest(id, tag, payload)
    try {
      for (value in values) emit(value)
    } finally {
      if (pending.remove(id) != null) sendInterrupt(id)
      values.cancel()
    }
  }

  suspend fun awaitClosed(): Throwable? = closed.await()

  fun abort() {
    socket.cancel()
  }

  override fun close() {
    if (closed.isCompleted) return
    socket.send(json.encodeToString(JsonObject.serializer(), tagged("Eof")))
    socket.close(1000, "client closed")
    finish(null)
  }

  private fun sendRequest(id: Long, tag: String, payload: JsonElement) {
    send(
      JsonObject(
        mapOf(
          "_tag" to JsonPrimitive("Request"),
          "id" to JsonPrimitive(id),
          "tag" to JsonPrimitive(tag),
          "payload" to payload,
          "headers" to JsonArray(emptyList()),
        ),
      ),
    )
  }

  private fun prepareIncomingMessage(message: String): String {
    val method = rpcEnvelopeRequestId(message)?.let(pending::get)?.method ?: return message
    return if (method in TERMINAL_SNAPSHOT_METHODS) {
      trimTerminalHistoryInRpcMessage(message)
    } else {
      message
    }
  }

  private fun sendInterrupt(id: Long) {
    send(tagged("Interrupt", id))
  }

  private fun handle(message: JsonObject) {
    when (val tag = message.string("_tag")) {
      "Chunk" -> {
        val requestId = message.requestId()
        val stream = pending[requestId] as? Pending.Stream ?: return
        message.required("values").jsonArray.forEach { stream.values.trySend(it) }
        send(tagged("Ack", requestId))
      }
      "Exit" -> handleExit(message)
      "Defect" -> failAll(RpcDefect(message.required("defect")))
      "Pong" -> missedPongs.set(0)
      "ClientProtocolError" -> failProtocol(
        RpcProtocolException("Server rejected the RPC protocol: ${message["error"]}"),
      )
      else -> failProtocol(RpcProtocolException("Unknown Effect RPC envelope: $tag"))
    }
  }

  private fun handleExit(message: JsonObject) {
    val requestId = message.requestId()
    val entry = pending.remove(requestId) ?: return
    val exit = message.required("exit").jsonObject
    when (exit.string("_tag")) {
      "Success" -> when (entry) {
        is Pending.Unary -> entry.result.complete(exit["value"] ?: JsonNull)
        is Pending.Stream -> entry.values.close()
      }
      "Failure" -> fail(entry, RpcFailure(exit.required("cause").jsonArray))
      else -> fail(entry, RpcProtocolException("Unknown RPC exit: ${exit["_tag"]}"))
    }
  }

  private fun startKeepAlive() {
    scope.launch {
      while (isActive) {
        delay(keepAliveIntervalMillis)
        if (missedPongs.getAndIncrement() >= 2) {
          val error = RpcTransportException("WebSocket missed three Pong responses.")
          failAll(error)
          socket.cancel()
          return@launch
        }
        send(tagged("Ping"))
      }
    }
  }

  private fun tagged(tag: String, requestId: Long? = null) = JsonObject(
    buildMap {
      put("_tag", JsonPrimitive(tag))
      requestId?.let { put("requestId", JsonPrimitive(it)) }
    },
  )

  private fun send(message: JsonObject) {
    check(socket.send(json.encodeToString(JsonObject.serializer(), message))) {
      "WebSocket is not accepting RPC messages."
    }
  }

  private fun failProtocol(error: Throwable) {
    failAll(error)
    socket.close(1002, "protocol error")
  }

  private fun failAll(error: Throwable) {
    pending.values.forEach { fail(it, error) }
    pending.clear()
  }

  private fun fail(entry: Pending, error: Throwable) {
    when (entry) {
      is Pending.Unary -> entry.result.completeExceptionally(error)
      is Pending.Stream -> entry.values.close(error)
    }
  }

  private fun finish(error: Throwable?) {
    if (closed.complete(error)) {
      failAll(error ?: RpcTransportException("WebSocket session closed."))
      scope.cancel()
    }
  }

  private fun JsonObject.required(name: String) =
    requireNotNull(this[name]) { "RPC envelope is missing $name." }

  private fun JsonObject.string(name: String) = required(name).jsonPrimitive.content

  private fun JsonObject.requestId() = required("requestId").jsonPrimitive.long

  companion object {
    suspend fun connect(
      http: OkHttpClient,
      url: String,
      json: Json = Json { ignoreUnknownKeys = true },
      keepAliveIntervalMillis: Long = 5_000,
    ): EffectRpcSession {
      val session = EffectRpcSession(http, json, keepAliveIntervalMillis)
      session.socket = http.newWebSocket(Request.Builder().url(url).build(), session.listener)
      try {
        withTimeout(15_000) { session.opened.await() }
      } catch (error: Throwable) {
        session.socket.cancel()
        throw error
      }
      return session
    }
  }
}
