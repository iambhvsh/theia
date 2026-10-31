cmake_minimum_required(VERSION 3.22.1)
project(assistant CXX)

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/engine/libneedle.a)
  message(FATAL_ERROR "Missing app/src/main/cpp/engine/libneedle.a and needle.h. Run scripts/setup.sh first.")
endif()

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/engine/libneedle.a)

add_library(assistant SHARED jni.cpp)
target_include_directories(assistant PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/engine)
target_link_libraries(assistant needle log)
target_link_options(assistant PRIVATE "-Wl,-z,max-page-size=16384")
