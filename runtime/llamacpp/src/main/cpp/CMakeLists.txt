cmake_minimum_required(VERSION 3.22.1)
project(androml_llamacpp)

set(LLAMA_VENDOR "${CMAKE_CURRENT_LIST_DIR}/../../../vendor")
include_directories("${LLAMA_VENDOR}/include")

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION "${LLAMA_VENDOR}/jni/${ANDROID_ABI}/libggml-base.so")
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION "${LLAMA_VENDOR}/jni/${ANDROID_ABI}/libggml.so")
add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION "${LLAMA_VENDOR}/jni/${ANDROID_ABI}/libggml-cpu-android_armv8.0_1.so")
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${LLAMA_VENDOR}/jni/${ANDROID_ABI}/libllama.so")

add_library(androml_llamacpp SHARED llama_jni.cpp)
target_compile_features(androml_llamacpp PRIVATE cxx_std_17)
target_compile_options(androml_llamacpp PRIVATE -fvisibility=hidden)
target_link_libraries(androml_llamacpp PRIVATE llama ggml ggml-cpu ggml-base log android)
