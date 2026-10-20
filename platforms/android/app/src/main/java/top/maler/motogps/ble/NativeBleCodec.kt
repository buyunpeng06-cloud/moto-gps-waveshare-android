package top.maler.motogps.ble

/** JNI adapter to the upstream shared/ble_protocol C++ implementation. */
internal object NativeBleCodec {
    val available: Boolean = try {
        System.loadLibrary("moto_android_bridge")
        true
    } catch (_: UnsatisfiedLinkError) {
        // JVM-only protocol unit tests can use the Kotlin reference path.
        // Android APK builds include this library and should report it in diagnostics.
        false
    }

    external fun nativeCrc16(input: ByteArray): Int
    external fun nativeEncodeFrame(type: Int, flags: Int, sequence: Int, offset: Int, messageLength: Int, chunk: ByteArray): ByteArray?
    external fun nativeFragmentMessage(type: Int, sequence: Int, payload: ByteArray, maxFrameSize: Int, applicationFlags: Int): Array<ByteArray>?
    external fun nativeDecodeFrame(input: ByteArray): ByteArray?
}
