package top.maler.motogps.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Wire-compatible subset of shared/protocol/ble-navigation-v1.md. */
object BleProtocol {
    const val MAGIC = 0xB7
    const val VERSION = 1
    const val HEADER_SIZE = 10
    const val CRC_SIZE = 2
    const val FRAME_OVERHEAD = HEADER_SIZE + CRC_SIZE
    const val FLAG_START = 0x01
    const val FLAG_END = 0x02
    const val FLAG_ACK_REQUESTED = 0x04
    const val FLAG_URGENT = 0x08
    const val TYPE_CONNECTION_STATUS = 0x01
    const val TYPE_HEARTBEAT = 0x02
    const val TYPE_ACK = 0x03
    const val MAX_MESSAGE_SIZE = 4096

    data class Frame(
        val type: Int,
        val flags: Int,
        val sequence: Int,
        val offset: Int,
        val messageLength: Int,
        val chunk: ByteArray,
    )

    data class ConnectionStatus(
        val role: Int,
        val state: Int,
        val minVersion: Int,
        val maxVersion: Int,
        val capabilities: Long,
        val sessionId: Long,
        val maxFrameSize: Int,
        val heartbeatIntervalMs: Int,
    ) {
        fun sameNegotiatedValues(other: ConnectionStatus): Boolean =
            role == other.role && state == other.state && minVersion == other.minVersion &&
                maxVersion == other.maxVersion && capabilities == other.capabilities &&
                sessionId == other.sessionId && maxFrameSize == other.maxFrameSize &&
                heartbeatIntervalMs == other.heartbeatIntervalMs
    }

    fun crc16CcittFalse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        if (NativeBleCodec.available && offset == 0 && length == bytes.size) {
            return NativeBleCodec.nativeCrc16(bytes)
        }
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    fun encodeFrame(type: Int, flags: Int, sequence: Int, offset: Int, messageLength: Int, chunk: ByteArray): ByteArray {
        require(type in 0..255 && flags and 0xF0 == 0)
        require(sequence in 1..65535 && offset in 0..65535 && messageLength in 0..65535)
        require(offset + chunk.size <= messageLength)
        require((offset == 0) == (flags and FLAG_START != 0))
        require((offset + chunk.size == messageLength) == (flags and FLAG_END != 0))
        if (NativeBleCodec.available) {
            return NativeBleCodec.nativeEncodeFrame(type, flags, sequence, offset, messageLength, chunk)
                ?: throw IllegalArgumentException("shared C++ codec rejected frame")
        }
        val out = ByteArray(FRAME_OVERHEAD + chunk.size)
        out[0] = MAGIC.toByte()
        out[1] = VERSION.toByte()
        out[2] = type.toByte()
        out[3] = flags.toByte()
        putU16(out, 4, sequence)
        putU16(out, 6, offset)
        putU16(out, 8, messageLength)
        chunk.copyInto(out, HEADER_SIZE)
        val crc = crc16CcittFalse(out, 0, HEADER_SIZE + chunk.size)
        putU16(out, HEADER_SIZE + chunk.size, crc)
        return out
    }

    fun encodeMessage(type: Int, logicalFlags: Int, sequence: Int, payload: ByteArray, maxFrameSize: Int): List<ByteArray> {
        require(maxFrameSize in 13..512) { "maxFrameSize must be 13..512 bytes" }
        require(payload.size <= MAX_MESSAGE_SIZE)
        require(sequence in 1..65535)
        if (NativeBleCodec.available) {
            return NativeBleCodec.nativeFragmentMessage(type, sequence, payload, maxFrameSize,
                logicalFlags and (FLAG_ACK_REQUESTED or FLAG_URGENT))?.toList()
                ?: throw IllegalArgumentException("shared C++ codec rejected logical message")
        }
        val capacity = maxFrameSize - FRAME_OVERHEAD
        require(capacity > 0)
        if (payload.isEmpty()) return listOf(encodeFrame(type, logicalFlags or FLAG_START or FLAG_END, sequence, 0, 0, byteArrayOf()))
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val count = min(capacity, payload.size - offset)
            var flags = logicalFlags and (FLAG_ACK_REQUESTED or FLAG_URGENT)
            if (offset == 0) flags = flags or FLAG_START
            if (offset + count == payload.size) flags = flags or FLAG_END
            frames += encodeFrame(type, flags, sequence, offset, payload.size, payload.copyOfRange(offset, offset + count))
            offset += count
        }
        return frames
    }

    fun decodeFrame(bytes: ByteArray): Frame {
        if (NativeBleCodec.available) {
            val decoded = NativeBleCodec.nativeDecodeFrame(bytes)
                ?: throw IllegalArgumentException("shared C++ codec rejected frame")
            require(decoded.size >= 10) { "shared C++ codec returned malformed frame envelope" }
            val payloadLength = getU16(decoded, 8)
            require(decoded.size == 10 + payloadLength) { "shared C++ codec returned invalid payload length" }
            return Frame(
                type = decoded[0].toInt() and 0xFF,
                flags = decoded[1].toInt() and 0xFF,
                sequence = getU16(decoded, 2),
                offset = getU16(decoded, 4),
                messageLength = getU16(decoded, 6),
                chunk = decoded.copyOfRange(10, decoded.size),
            )
        }
        require(bytes.size >= FRAME_OVERHEAD) { "frame too short" }
        require((bytes[0].toInt() and 0xFF) == MAGIC) { "bad magic" }
        require((bytes[1].toInt() and 0xFF) == VERSION) { "unsupported protocol version" }
        val flags = bytes[3].toInt() and 0xFF
        require(flags and 0xF0 == 0) { "reserved flags set" }
        val payloadSize = bytes.size - FRAME_OVERHEAD
        val expectedCrc = getU16(bytes, bytes.size - 2)
        val actualCrc = crc16CcittFalse(bytes, 0, bytes.size - 2)
        require(expectedCrc == actualCrc) { "CRC mismatch" }
        val sequence = getU16(bytes, 4)
        val offset = getU16(bytes, 6)
        val messageLength = getU16(bytes, 8)
        require(sequence != 0) { "sequence zero is reserved" }
        require(messageLength <= MAX_MESSAGE_SIZE && offset + payloadSize <= messageLength) { "invalid message bounds" }
        require((offset == 0) == (flags and FLAG_START != 0)) { "START/offset mismatch" }
        require((offset + payloadSize == messageLength) == (flags and FLAG_END != 0)) { "END/length mismatch" }
        return Frame(bytes[2].toInt() and 0xFF, flags, sequence, offset, messageLength, bytes.copyOfRange(HEADER_SIZE, bytes.size - CRC_SIZE))
    }

    fun encodeConnectionStatus(status: ConnectionStatus): ByteArray {
        val out = ByteArray(17)
        out[0] = 1
        out[1] = status.role.toByte()
        out[2] = status.state.toByte()
        out[3] = status.minVersion.toByte()
        out[4] = status.maxVersion.toByte()
        putU32(out, 5, status.capabilities)
        putU32(out, 9, status.sessionId)
        putU16(out, 13, status.maxFrameSize)
        putU16(out, 15, status.heartbeatIntervalMs)
        return out
    }

    fun decodeConnectionStatus(payload: ByteArray): ConnectionStatus {
        require(payload.size == 17) { "ConnectionStatus must be 17 bytes" }
        require((payload[0].toInt() and 0xFF) == 1) { "unsupported ConnectionStatus revision" }
        return ConnectionStatus(
            role = payload[1].toInt() and 0xFF,
            state = payload[2].toInt() and 0xFF,
            minVersion = payload[3].toInt() and 0xFF,
            maxVersion = payload[4].toInt() and 0xFF,
            capabilities = getU32(payload, 5),
            sessionId = getU32(payload, 9),
            maxFrameSize = getU16(payload, 13),
            heartbeatIntervalMs = getU16(payload, 15),
        )
    }

    fun encodeHeartbeat(sessionId: Long, sessionElapsedMs: Long): ByteArray {
        val out = ByteArray(11)
        out[0] = 1
        putU32(out, 1, sessionId)
        putU32(out, 5, sessionElapsedMs and 0xFFFF_FFFFL)
        putU16(out, 9, 0)
        return out
    }

    private fun putU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte(); out[offset + 1] = (value ushr 8).toByte()
    }
    private fun getU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    private fun putU32(out: ByteArray, offset: Int, value: Long) {
        for (i in 0..3) out[offset + i] = (value ushr (8 * i)).toByte()
    }
    private fun getU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

/** One in-flight logical message per direction; continuation must be contiguous. */
class MessageReassembler(private val timeoutMs: Long = 1_000L) {
    data class CompleteMessage(val type: Int, val flags: Int, val sequence: Int, val payload: ByteArray)
    private data class Active(
        val type: Int, val flags: Int, val sequence: Int, val length: Int,
        val bytes: ByteArray, var used: Int, var lastNewFragmentAt: Long,
    )
    private var active: Active? = null
    private var lastCompletedSequence: Int? = null

    @Synchronized fun reset() { active = null; lastCompletedSequence = null }

    @Synchronized fun accept(frame: BleProtocol.Frame, nowMs: Long): CompleteMessage? {
        val current = active
        if (current != null && nowMs - current.lastNewFragmentAt > timeoutMs) active = null
        var a = active
        if (frame.flags and BleProtocol.FLAG_START != 0) {
            if (frame.offset != 0) throw IllegalArgumentException("START frame offset must be zero")
            if (lastCompletedSequence == frame.sequence) return null
            val logicalFlags = frame.flags and (BleProtocol.FLAG_ACK_REQUESTED or BleProtocol.FLAG_URGENT)
            val existing = a
            if (existing != null && existing.sequence == frame.sequence) {
                require(existing.type == frame.type && existing.length == frame.messageLength && existing.flags == logicalFlags) {
                    "START metadata changed within active sequence"
                }
                if (existing.used >= frame.chunk.size && frame.chunk.indices.all { existing.bytes[it] == frame.chunk[it] }) {
                    return null // exact duplicate START fragment; do not reset progress or timeout
                }
                throw IllegalArgumentException("conflicting duplicate START fragment")
            }
            a = Active(frame.type, logicalFlags, frame.sequence, frame.messageLength,
                ByteArray(frame.messageLength), 0, nowMs)
            active = a
        } else {
            require(a != null) { "continuation without START" }
        }
        a = active ?: throw IllegalArgumentException("no active message")
        require(frame.sequence == a.sequence && frame.type == a.type && frame.messageLength == a.length) { "fragment metadata changed" }
        require((frame.flags and (BleProtocol.FLAG_ACK_REQUESTED or BleProtocol.FLAG_URGENT)) == a.flags) { "logical flags changed" }
        if (frame.offset < a.used) {
            val end = frame.offset + frame.chunk.size
            if (end <= a.used && frame.chunk.indices.all { a.bytes[frame.offset + it] == frame.chunk[it] }) return null
            throw IllegalArgumentException("overlapping fragment differs")
        }
        require(frame.offset == a.used) { "fragment gap or overlap" }
        frame.chunk.copyInto(a.bytes, a.used)
        a.used += frame.chunk.size
        a.lastNewFragmentAt = nowMs
        if (frame.flags and BleProtocol.FLAG_END != 0) {
            require(a.used == a.length) { "END before message complete" }
            active = null
            lastCompletedSequence = a.sequence
            return CompleteMessage(a.type, a.flags, a.sequence, a.bytes)
        }
        return null
    }
}
