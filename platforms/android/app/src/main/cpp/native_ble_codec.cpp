#include <jni.h>

#include "moto/ble_protocol/ble_protocol.hpp"

#include <cstdint>
#include <vector>

namespace {
using moto::ble::ByteView;
using moto::ble::Bytes;
using moto::ble::Frame;
using moto::ble::MessageType;

Bytes from_java(JNIEnv* env, jbyteArray array) {
  if (array == nullptr) return {};
  const jsize size = env->GetArrayLength(array);
  Bytes result(static_cast<std::size_t>(size));
  if (size > 0) {
    env->GetByteArrayRegion(array, 0, size,
                            reinterpret_cast<jbyte*>(result.data()));
  }
  return result;
}

jbyteArray to_java(JNIEnv* env, const Bytes& bytes) {
  auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (result != nullptr && !bytes.empty()) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
  }
  return result;
}

void put_u16(Bytes& out, std::size_t offset, std::uint16_t value) {
  out[offset] = static_cast<std::uint8_t>(value & 0xffU);
  out[offset + 1] = static_cast<std::uint8_t>((value >> 8U) & 0xffU);
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_top_maler_motogps_ble_NativeBleCodec_nativeCrc16(
    JNIEnv* env, jobject, jbyteArray input) {
  const Bytes bytes = from_java(env, input);
  return static_cast<jint>(moto::ble::crc16_ccitt_false(ByteView(bytes)));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_top_maler_motogps_ble_NativeBleCodec_nativeEncodeFrame(
    JNIEnv* env, jobject, jint type, jint flags, jint sequence, jint offset,
    jint message_length, jbyteArray chunk_array) {
  const Bytes chunk = from_java(env, chunk_array);
  Frame frame;
  frame.type = static_cast<MessageType>(static_cast<std::uint8_t>(type));
  frame.flags = static_cast<std::uint8_t>(flags);
  frame.sequence = static_cast<std::uint16_t>(sequence);
  frame.fragment_offset = static_cast<std::uint16_t>(offset);
  frame.message_length = static_cast<std::uint16_t>(message_length);
  frame.payload = chunk;
  const auto encoded = moto::ble::encode_frame(frame);
  if (!encoded.ok()) return nullptr;
  return to_java(env, encoded.value);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_top_maler_motogps_ble_NativeBleCodec_nativeFragmentMessage(
    JNIEnv* env, jobject, jint type, jint sequence, jbyteArray payload_array,
    jint max_frame_size, jint application_flags) {
  const Bytes payload = from_java(env, payload_array);
  const auto result = moto::ble::fragment_message(
      static_cast<MessageType>(static_cast<std::uint8_t>(type)),
      static_cast<std::uint16_t>(sequence), ByteView(payload),
      static_cast<std::size_t>(max_frame_size),
      static_cast<std::uint8_t>(application_flags));
  if (!result.ok()) return nullptr;

  jclass byte_array_class = env->FindClass("[B");
  if (byte_array_class == nullptr) return nullptr;
  auto frames = env->NewObjectArray(static_cast<jsize>(result.value.size()),
                                    byte_array_class, nullptr);
  if (frames == nullptr) return nullptr;
  for (std::size_t i = 0; i < result.value.size(); ++i) {
    jbyteArray frame = to_java(env, result.value[i]);
    if (frame == nullptr) return nullptr;
    env->SetObjectArrayElement(frames, static_cast<jsize>(i), frame);
    env->DeleteLocalRef(frame);
  }
  env->DeleteLocalRef(byte_array_class);
  return frames;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_top_maler_motogps_ble_NativeBleCodec_nativeDecodeFrame(
    JNIEnv* env, jobject, jbyteArray input) {
  const Bytes bytes = from_java(env, input);
  const auto decoded = moto::ble::decode_frame(ByteView(bytes));
  if (!decoded.ok()) return nullptr;

  // Stable JNI result envelope: type, flags, seq(u16), offset(u16),
  // message_length(u16), payload_length(u16), payload bytes. All u16 are LE.
  Bytes out(10 + decoded.value.payload.size());
  out[0] = static_cast<std::uint8_t>(decoded.value.type);
  out[1] = decoded.value.flags;
  put_u16(out, 2, decoded.value.sequence);
  put_u16(out, 4, decoded.value.fragment_offset);
  put_u16(out, 6, decoded.value.message_length);
  put_u16(out, 8, static_cast<std::uint16_t>(decoded.value.payload.size()));
  for (std::size_t i = 0; i < decoded.value.payload.size(); ++i) {
    out[10 + i] = decoded.value.payload[i];
  }
  return to_java(env, out);
}
