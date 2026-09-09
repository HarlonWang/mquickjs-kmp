cmake_minimum_required(VERSION 3.22)
project(mquickjs_kmp C)

# Directory holding kmp_stdlib.h and mquickjs_atom.h produced by the host tool (docs/native-build.md).
set(MQJS_GEN_DIR "" CACHE PATH "generated stdlib headers")
option(MQJS_DEBUG_GC "move objects on every allocation to surface stale JSValue bugs" OFF)
# Host-side JNI library so Android host tests can exercise the real JNI path on the build machine
option(MQJS_HOST_JNI "build the JNI shared library for the host JVM" OFF)
option(MQJS_SHIM_TEST "build the host shim test binary (native/test)" OFF)
option(MQJS_ASAN "compile everything with AddressSanitizer" OFF)

if(NOT MQJS_GEN_DIR)
    message(FATAL_ERROR "MQJS_GEN_DIR must point at the generated stdlib headers")
endif()

set(MQJS_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/mquickjs)

add_library(mquickjs_kmp_static STATIC
    ${MQJS_ROOT}/mquickjs.c
    ${MQJS_ROOT}/dtoa.c
    ${MQJS_ROOT}/libm.c
    ${MQJS_ROOT}/cutils.c
    shim/mquickjs_kmp.c
)
set_target_properties(mquickjs_kmp_static PROPERTIES OUTPUT_NAME mquickjs_kmp)
target_include_directories(mquickjs_kmp_static
    PUBLIC shim
    PRIVATE ${MQJS_GEN_DIR} ${MQJS_ROOT}
)
target_compile_definitions(mquickjs_kmp_static PRIVATE _GNU_SOURCE)
target_compile_options(mquickjs_kmp_static PRIVATE -Os -Wall -Wno-macro-redefined -fno-math-errno -fno-trapping-math -fvisibility=hidden)
if(MQJS_DEBUG_GC)
    target_compile_definitions(mquickjs_kmp_static PRIVATE DEBUG_GC)
endif()
if(MQJS_ASAN)
    target_compile_options(mquickjs_kmp_static PRIVATE -fsanitize=address -fno-omit-frame-pointer)
endif()

if(ANDROID)
    add_library(mquickjs_kmp SHARED jni/mquickjs_jni.c)
    target_compile_options(mquickjs_kmp PRIVATE -Os -Wall -fvisibility=hidden)
    target_link_libraries(mquickjs_kmp PRIVATE mquickjs_kmp_static)
    target_link_options(mquickjs_kmp PRIVATE -Wl,--exclude-libs,ALL -Wl,--strip-all)
elseif(MQJS_HOST_JNI)
    # MQJS_JNI_INCLUDE lets the build pass the JDK's include dirs directly (FindJNI insists on AWT on macOS)
    if(MQJS_JNI_INCLUDE)
        set(JNI_INCLUDE_DIRS ${MQJS_JNI_INCLUDE})
    else()
        find_package(JNI REQUIRED COMPONENTS Development)
    endif()
    add_library(mquickjs_kmp SHARED jni/mquickjs_jni.c)
    target_compile_options(mquickjs_kmp PRIVATE -Os -Wall -fvisibility=hidden)
    target_include_directories(mquickjs_kmp PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(mquickjs_kmp PRIVATE mquickjs_kmp_static)
endif()

if(MQJS_SHIM_TEST)
    add_executable(shim_test test/shim_test.c)
    target_compile_options(shim_test PRIVATE -Wall)
    target_link_libraries(shim_test PRIVATE mquickjs_kmp_static)
    if(MQJS_ASAN)
        target_compile_options(shim_test PRIVATE -fsanitize=address -fno-omit-frame-pointer)
        target_link_options(shim_test PRIVATE -fsanitize=address)
    endif()
endif()
