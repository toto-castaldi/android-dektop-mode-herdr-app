# JNI binding of libghostty-vt (ADR-001). The static library is built by the Gradle task
# :terminal:buildLibGhosttyVt and installed in GHOSTTY_VT_PREFIX.
cmake_minimum_required(VERSION 3.22.1)
project(herdrterminal C)

if(NOT GHOSTTY_VT_PREFIX)
  message(FATAL_ERROR "GHOSTTY_VT_PREFIX is not set: build through Gradle")
endif()

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
  IMPORTED_LOCATION "${GHOSTTY_VT_PREFIX}/lib/libghostty-vt.a"
  INTERFACE_INCLUDE_DIRECTORIES "${GHOSTTY_VT_PREFIX}/include"
  INTERFACE_COMPILE_DEFINITIONS GHOSTTY_STATIC)

add_library(herdrterminal SHARED ghostty_jni.c)
target_compile_options(herdrterminal PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(herdrterminal PRIVATE ghostty-vt)
target_link_options(herdrterminal PRIVATE
  -Wl,-z,max-page-size=16384
  -Wl,--gc-sections
  -Wl,--exclude-libs,ALL)
