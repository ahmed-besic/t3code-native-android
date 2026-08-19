package com.t3tools.android.protocol

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class EffectRpcSessionTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun multiplexes_unary_and_stream_calls_with_ack_and_cancellation() = runBlocking {
    val clientMessages = LinkedBlockingQueue<JsonObject>()
    val server = webSocketServer(clientMessages) { socket, message ->
      if (message["_tag"] == JsonPrimitive("Request")) {
        when (message["tag"]?.toString()?.trim('"')) {
          "server.getConfig" -> socket.send(
            """{"_tag":"Exit","requestId":${message["id"]},"exit":{"_tag":"Success","value":{"environment":{"environmentId":"env-1"}}}}""",
          )
          "orchestration.subscribeShell" -> socket.send(
            """{"_tag":"Chunk","requestId":${message["id"]},"values":[{"kind":"synchronized"}]}""",
          )
        }
      }
    }
    try {
      val session = EffectRpcSession.connect(
        OkHttpClient(),
        server.url("/ws").toString().replace("http://", "ws://"),
        keepAliveIntervalMillis = 60_000,
      )
      val config = session.unary("server.getConfig")
      val item = session.stream("orchestration.subscribeShell").first()

      assertEquals("env-1", config.jsonObject["environment"]!!.jsonObject["environmentId"]!!.toString().trim('"'))
      assertEquals("synchronized", item.jsonObject["kind"]!!.toString().trim('"'))
      val sent = generateSequence { clientMessages.poll(1, TimeUnit.SECONDS) }.take(4).toList()
      assertTrue(sent.any { it["_tag"] == JsonPrimitive("Ack") })
      assertTrue(sent.any { it["_tag"] == JsonPrimitive("Interrupt") })
      session.close()
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun surfaces_typed_failure_causes() = runBlocking {
    val server = webSocketServer(LinkedBlockingQueue()) { socket, message ->
      if (message["_tag"] == JsonPrimitive("Request")) {
        socket.send(
          """{"_tag":"Exit","requestId":${message["id"]},"exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"ExpectedError","message":"rejected"}}]}}""",
        )
      }
    }
    try {
      val session = EffectRpcSession.connect(
        OkHttpClient(),
        server.url("/ws").toString().replace("http://", "ws://"),
        keepAliveIntervalMillis = 60_000,
      )
      val failure = assertFailsWith<RpcFailure> { session.unary("test.failure") }
      assertEquals("ExpectedError", failure.causes[0].jsonObject["error"]!!.jsonObject["_tag"]!!.toString().trim('"'))
      assertEquals("rejected", failure.message)
      session.close()
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun trims_oversized_terminal_history_before_decoding_the_rpc_frame() = runBlocking {
    val history = "discard-${"x".repeat(600 * 1024)}-tail"
    val server = webSocketServer(LinkedBlockingQueue()) { socket, message ->
      if (message["_tag"] == JsonPrimitive("Request")) {
        socket.send(
          """{"_tag":"Chunk","requestId":${message["id"]},"values":[{"type":"snapshot","snapshot":{"history":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(history))}}}]}""",
        )
      }
    }
    try {
      val session = EffectRpcSession.connect(
        OkHttpClient(),
        server.url("/ws").toString().replace("http://", "ws://"),
        keepAliveIntervalMillis = 60_000,
      )

      val item = session.stream("terminal.attach").first()
      val receivedHistory = item.jsonObject["snapshot"]!!.jsonObject["history"]!!.jsonPrimitive.content

      assertEquals(512 * 1024, receivedHistory.length)
      assertTrue(receivedHistory.endsWith("-tail"))
      assertTrue(!receivedHistory.startsWith("discard-"))
      session.close()
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun preserves_json_escape_boundaries_while_trimming_terminal_history() {
    val tail = "\u001b[31m\"tail\\🙂"
    val history = "discard-${"x".repeat(40)}$tail"
    val encodedHistory = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(history))
    val message = """{"snapshot":{"history":$encodedHistory}}"""

    val trimmed = trimTerminalHistoryInRpcMessage(message, maxHistoryCharacters = 24)
    val decoded = json.parseToJsonElement(trimmed).jsonObject["snapshot"]!!
      .jsonObject["history"]!!.jsonPrimitive.content

    assertTrue(decoded.endsWith(tail))
    assertTrue(!decoded.startsWith("discard-"))
  }

  private fun webSocketServer(
    messages: LinkedBlockingQueue<JsonObject>,
    respond: (WebSocket, JsonObject) -> Unit,
  ) = MockWebServer().apply {
    enqueue(
      MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val message = json.parseToJsonElement(text).jsonObject
            messages.offer(message)
            respond(webSocket, message)
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
          }
        },
      ),
    )
    start()
  }
}
