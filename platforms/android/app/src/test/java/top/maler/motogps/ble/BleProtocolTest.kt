package top.maler.motogps.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BleProtocolTest {
    private fun bytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun golden(): Map<String, String> = javaClass.getResourceAsStream("/ble-navigation-v1.golden.txt")!!
        .bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('=') to it.substringAfter('=') }.toMap() }

    @Test fun crcStandardCheckValue() {
        assertEquals(0x29B1, BleProtocol.crc16CcittFalse("123456789".toByteArray()))
    }

    @Test fun upstreamGoldenConnectionFrameMatchesExactly() {
        val fixture = golden()
        val payload = bytes(fixture.getValue("connection.payload"))
        val frame = BleProtocol.encodeMessage(BleProtocol.TYPE_CONNECTION_STATUS, 0, 1, payload, 512).single()
        assertArrayEquals(bytes(fixture.getValue("connection.frame")), frame)
        val decoded = BleProtocol.decodeFrame(frame)
        assertEquals(BleProtocol.TYPE_CONNECTION_STATUS, decoded.type)
        assertArrayEquals(payload, decoded.chunk)
        val status = BleProtocol.decodeConnectionStatus(decoded.chunk)
        assertEquals(0x12345678L, status.sessionId)
        assertEquals(185, status.maxFrameSize)
    }

    @Test fun upstreamGoldenCommandFramesAtTwentyBytesMatchExactly() {
        val fixture = golden()
        val payload = bytes(fixture.getValue("command.payload"))
        val actual = BleProtocol.encodeMessage(0x20, BleProtocol.FLAG_ACK_REQUESTED or BleProtocol.FLAG_URGENT, 0x1234, payload, 20)
        assertEquals(2, actual.size)
        val expectedFrames = fixture.getValue("command.frames20").split(';')
        assertEquals(expectedFrames[0], actual[0].joinToString("") { "%02x".format(it) })
        assertEquals(expectedFrames[1], actual[1].joinToString("") { "%02x".format(it) })
        assertEquals(20, actual[0].size)
        assertEquals(20, actual[1].size)
    }

    @Test fun reassemblyRebuildsGoldenMultiFrameCommand() {
        val reassembler = MessageReassembler()
        val payload = bytes(golden().getValue("command.payload"))
        val frames = BleProtocol.encodeMessage(0x20, BleProtocol.FLAG_ACK_REQUESTED or BleProtocol.FLAG_URGENT, 0x1234, payload, 20)
        assertEquals(null, reassembler.accept(BleProtocol.decodeFrame(frames[0]), 100))
        val completed = reassembler.accept(BleProtocol.decodeFrame(frames[1]), 101)
        assertNotNull(completed)
        assertArrayEquals(payload, completed!!.payload)
        assertEquals(0x20, completed.type)
        assertEquals(0x1234, completed.sequence)
    }

    @Test fun corruptedCrcIsRejected() {
        val frame = bytes(golden().getValue("connection.frame"))
        frame[12] = (frame[12].toInt() xor 0x01).toByte()
        try {
            BleProtocol.decodeFrame(frame)
            throw AssertionError("corrupt CRC should fail")
        } catch (expected: IllegalArgumentException) {
            assertEquals("CRC mismatch", expected.message)
        }
    }
}
