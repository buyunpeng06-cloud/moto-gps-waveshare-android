cmake_minimum_required(VERSION 3.22.1)
project(moto_android_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Reuse the exact upstream wire codec; do not copy protocol implementation
# into a second C++ module and do not link ESP32/LVGL display code into Android.
add_subdirectory(
  "${CMAKE_CURRENT_LIST_DIR}/../../../../../../shared/ble_protocol"
  "${CMAKE_CURRENT_BINARY_DIR}/moto_ble_protocol"
)

add_library(moto_android_bridge SHARED native_ble_codec.cpp)
target_link_libraries(moto_android_bridge PRIVATE moto_ble_protocol)
if(ANDROID)
  target_link_libraries(moto_android_bridge PRIVATE android log)
else()
  # Lets host CI/dev machines run the exact JNI codec against golden fixtures.
  find_package(JNI REQUIRED)
  target_include_directories(moto_android_bridge PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_compile_features(moto_android_bridge PRIVATE cxx_std_17)
target_compile_options(moto_android_bridge PRIVATE -Wall -Wextra -Wpedantic)
