package top.maler.motogps.ble

private fun fromHex(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
private fun golden(): Map<String, String> = java.io.File("app/src/test/resources/ble-navigation-v1.golden.txt")
    .readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    .associate { it.substringBefore('=') to it.substringAfter('=') }

fun main() {
    val fixture = golden()
    check(BleProtocol.crc16CcittFalse("123456789".toByteArray()) == 0x29B1) { "CRC standard vector failed" }

    val connectionPayload = fromHex(fixture.getValue("connection.payload"))
    val connectionFrames = BleProtocol.encodeMessage(BleProtocol.TYPE_CONNECTION_STATUS, 0, 1, connectionPayload, 512)
    check(connectionFrames.size == 1)
    check(toHex(connectionFrames.single()) == fixture.getValue("connection.frame")) {
        "Upstream connection golden mismatch: ${toHex(connectionFrames.single())}"
    }
    val parsed = BleProtocol.decodeConnectionStatus(BleProtocol.decodeFrame(connectionFrames.single()).chunk)
    check(parsed.sessionId == 0x12345678L && parsed.maxFrameSize == 185 && parsed.heartbeatIntervalMs == 1000)

    val commandPayload = fromHex(fixture.getValue("command.payload"))
    val commandFrames = BleProtocol.encodeMessage(0x20, BleProtocol.FLAG_ACK_REQUESTED or BleProtocol.FLAG_URGENT, 0x1234, commandPayload, 20)
    check(commandFrames.size == 2)
    val expectedCommandFrames = fixture.getValue("command.frames20").split(';')
    check(toHex(commandFrames[0]) == expectedCommandFrames[0])
    check(toHex(commandFrames[1]) == expectedCommandFrames[1])
    check(commandFrames.all { it.size <= 20 })

    val reassembler = MessageReassembler()
    check(reassembler.accept(BleProtocol.decodeFrame(commandFrames[0]), 100) == null)
    check(reassembler.accept(BleProtocol.decodeFrame(commandFrames[0]), 100) == null) // duplicate START ignored
    val complete = reassembler.accept(BleProtocol.decodeFrame(commandFrames[1]), 101)
    check(complete != null && complete.type == 0x20 && complete.sequence == 0x1234)
    check(toHex(complete.payload) == toHex(commandPayload))

    val timeoutAssembler = MessageReassembler(timeoutMs = 1_000)
    check(timeoutAssembler.accept(BleProtocol.decodeFrame(commandFrames[0]), 10) == null)
    var timeoutRejected = false
    try { timeoutAssembler.accept(BleProtocol.decodeFrame(commandFrames[1]), 1_011) }
    catch (_: IllegalArgumentException) { timeoutRejected = true }
    check(timeoutRejected) { "continuation after reassembly timeout was accepted" }

    val corrupt = connectionFrames.single().clone()
    corrupt[12] = (corrupt[12].toInt() xor 1).toByte()
    var rejected = false
    try { BleProtocol.decodeFrame(corrupt) } catch (_: IllegalArgumentException) { rejected = true }
    check(rejected) { "corrupted CRC was not rejected" }

    println("PASS: CRC-16/CCITT-FALSE standard vector")
    println("PASS: upstream connection.payload/frame golden bytes")
    println("PASS: upstream command.frames20 fragmentation golden bytes")
    println("PASS: fragmented message reassembly and duplicate START handling")
    println("PASS: reassembly timeout rejects late continuation")
    println("PASS: CRC corruption rejected")
}
