cmake_minimum_required(VERSION 3.15)
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
project(audio_capture CXX)

set(CMAKE_CXX_STANDARD 17)
add_executable(audio-capture audio_capture.cpp)
# Per-process loopback needs the Windows 10 2004 SDK or later.
target_link_libraries(audio-capture PRIVATE mmdevapi ole32)
target_compile_definitions(audio-capture PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
if (MSVC)
    target_compile_options(audio-capture PRIVATE /utf-8 /W3)
endif()
