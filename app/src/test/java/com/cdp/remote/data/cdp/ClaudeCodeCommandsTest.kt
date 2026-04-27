package com.cdp.remote.data.cdp

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeCommandsTest {

    @Test
    fun `sendMessage dispatches non BMP character as one char event`() = runBlocking {
        val mockServer = MockCdpServer()
        val client = CdpClient()
        try {
            mockServer.start()
            assertTrue(client.connectDirect(mockServer.wsUrl).isSuccess)
            mockServer.onRequest { req ->
                when (req.get("method")?.asString) {
                    "Runtime.evaluate" -> JsonObject().apply {
                        add("result", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", "ok")
                        })
                    }
                    "Input.dispatchKeyEvent" -> JsonObject()
                    else -> null
                }
            }

            val commands = ClaudeCodeCommands(client)
            val result = commands.sendMessage("😀")

            assertTrue(result.isSuccess)
            val charEvents = mockServer.receivedMessages.filter { msg ->
                msg.get("method")?.asString == "Input.dispatchKeyEvent" &&
                    msg.getAsJsonObject("params")?.get("type")?.asString == "char"
            }
            assertEquals("emoji should be dispatched as one Unicode scalar", 1, charEvents.size)
            assertEquals(
                "😀",
                charEvents.single().getAsJsonObject("params").get("text").asString
            )
        } finally {
            client.disconnect()
            mockServer.shutdown()
        }
    }
}
