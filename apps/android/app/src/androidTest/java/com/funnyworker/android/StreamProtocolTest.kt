package com.funnyworker.android

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProtocolTest {
    @Test fun messagesStreamOnlyDisplaysTextBlocks() {
        assertEquals("", chatTextDelta("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hidden\"}}"))
        assertEquals("Reply", chatTextDelta("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Reply\"}}"))
    }

    @Test fun platformJsonNullAndMetadataNeverAppearAsReplyText() {
        val reply = StringBuilder()
        val reader = SseReader { data -> if (data != "[DONE]") reply.append(chatTextDelta(data)) }
        val events = "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":{\"metadata\":true}}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好🙂\"}}]}\r\n\r\n" +
            "data: [DONE]\n\n"
        events.toByteArray(Charsets.UTF_8).forEach { reader.byte(it.toInt() and 255) }
        assertEquals("你好🙂", reply.toString())
    }
}
