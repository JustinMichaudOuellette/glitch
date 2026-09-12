cmake_minimum_required(VERSION 3.22.1)

# The JPEG pipeline, built from libjpeg-turbo's own source.
#
# Anyone who clones this repository needs no extra step: the first build fetches
# libjpeg-turbo at a pinned tag and builds it for the ABI in question. There is
# no dependency to add and no submodule to remember.
#
# It is built with ExternalProject_Add rather than add_subdirectory or
# FetchContent because libjpeg-turbo's own CMakeLists refuses to be a subproject:
#
#   "The libjpeg-turbo build system cannot be integrated into another build
#    system using add_subdirectory().  Use ExternalProject_Add() instead."
#
# so this is the integration path upstream prescribes: the library is configured
# and built as its own top-level project, exactly as its maintainers test it,
# and we link the archive out of that build tree. (Its install step is skipped:
# libjpeg-turbo pins CMAKE_INSTALL_LIBDIR to an absolute path inside its own
# build directory, so the archive is already where we want it.)
#
# Pinned to 3.0.1 because that is the release this app's output was verified
# byte-for-byte against: the fixtures in app/src/androidTest/assets/parity were
# written by Pillow, and the instrumentation tests compare against them. Moving
# this tag means re-running those tests.
#
# Optimization is passed explicitly instead of inherited from the app's build
# type. AGP builds the debug variant with CMAKE_BUILD_TYPE=Debug, and this NDK
# leaves CMAKE_C_FLAGS_DEBUG empty, so the library and the wrapper below were
# compiled with no -O flag at all - clang's default, -O0. A JPEG codec at -O0 is
# not a useful debug build of anything, it is several times slower than the
# decoder in the OS it replaced. -O3 is what libjpeg-turbo's own release builds
# and Pillow's wheels use, and unlike -ffast-math (deliberately absent, it would
# move the pixels) it cannot change the output.
#
# SIMD is left at upstream's default of on, which is most of what makes
# libjpeg-turbo fast: Neon intrinsics on arm64 and arm, SSE2/AVX2 kernels
# assembled with NASM on x86 and x86_64. NASM is not part of the NDK - the
# Android SDK's own CMake directory is where this build found it - so
# REQUIRE_SIMD is left FALSE: on a machine without it the configure prints a
# warning and builds the C paths instead of failing. libjpeg-turbo implements the
# SIMD paths to be bit-exact with the C ones, and the parity tests in
# app/src/androidTest are what holds that to account.
#
# Building with no network, or from a checkout you already have: set
#   JPEGPIPE_LIBJPEG_TURBO_DIR   (PowerShell: $env:JPEGPIPE_LIBJPEG_TURBO_DIR)
# to a libjpeg-turbo 3.0.1 checkout and the download is skipped entirely.
project(jpegpipe C)

set(CMAKE_C_STANDARD 11)

# The wrapper is a codec too, and its resampler is a filter over millions of
# pixels, so it gets the same treatment as the library it links.
#
# -ffp-contract=off pins the one piece of floating point left in it: the resampler
# computes its weights in double before quantising them to the fixed point it
# accumulates in, and clang's default is to contract a*b+c into a fused
# multiply-add wherever the target has one - which arm64 always does and x86-64
# does not. The quantisation absorbs a difference of an ulp almost always, and
# "almost always" is not the property this app is built on: one file has to have
# the same pixels on every phone. (libjpeg-turbo needs none of this: its hot paths
# are integer, and its own SIMD is written to reproduce them exactly.)
set(JPEGPIPE_OPT_FLAGS -O3 -ffp-contract=off)

# What libjpeg-turbo itself is built with. Spelled out, and deliberately not
# JPEGPIPE_OPT_FLAGS: an argument passed to the nested configure through
# ExternalProject_Add cannot carry more than one space, and -ffp-contract cannot
# matter for a library whose hot paths are integer arithmetic anyway.
set(JPEGPIPE_LIB_FLAGS "-O3 -DNDEBUG")

# --- libjpeg-turbo, as its own build -----------------------------------------
set(JPEG_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo)
set(JPEG_BUILD_DIR ${JPEG_PREFIX}/build)

if(DEFINED ENV{JPEGPIPE_LIBJPEG_TURBO_DIR})
  message(STATUS "jpegpipe: libjpeg-turbo from $ENV{JPEGPIPE_LIBJPEG_TURBO_DIR}")
  set(JPEG_SOURCE_DIR "$ENV{JPEGPIPE_LIBJPEG_TURBO_DIR}")
  set(JPEG_SOURCE_ARGS SOURCE_DIR "${JPEG_SOURCE_DIR}" DOWNLOAD_COMMAND "")
else()
  set(JPEG_SOURCE_DIR ${JPEG_PREFIX}/src)
  set(JPEG_SOURCE_ARGS
    GIT_REPOSITORY https://github.com/libjpeg-turbo/libjpeg-turbo.git
    GIT_TAG 3.0.1
    GIT_SHALLOW TRUE
    SOURCE_DIR ${JPEG_SOURCE_DIR}
  )
endif()

# The imported target's include paths have to exist when CMake generates, and
# both are produced by the build: this project's own headers sit in the source
# tree, and jconfig.h is generated into the build tree.
file(MAKE_DIRECTORY ${JPEG_BUILD_DIR})
file(MAKE_DIRECTORY ${JPEG_SOURCE_DIR})

include(ExternalProject)
ExternalProject_Add(libjpeg-turbo-project
  ${JPEG_SOURCE_ARGS}
  BINARY_DIR ${JPEG_BUILD_DIR}
  # The nested build has to use the same generator and build tool as this one,
  # which is whatever Gradle handed us (Ninja on every platform now): without
  # these the nested configure looks for make and fails.
  CMAKE_GENERATOR ${CMAKE_GENERATOR}
  CMAKE_GENERATOR_PLATFORM ${CMAKE_GENERATOR_PLATFORM}
  CMAKE_GENERATOR_TOOLSET ${CMAKE_GENERATOR_TOOLSET}
  CMAKE_ARGS
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    # Release, and the flags spelled out: the nested build is the codec, and it is
    # built the same way whatever the app's build type is. (This NDK ships an
    # empty CMAKE_C_FLAGS_RELEASE, so "Release" alone would still mean -O0.)
    -DCMAKE_BUILD_TYPE=Release
    "-DCMAKE_C_FLAGS_RELEASE=${JPEGPIPE_LIB_FLAGS}"
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DANDROID_NDK=${ANDROID_NDK}
    -DENABLE_SHARED=FALSE
    -DENABLE_STATIC=TRUE
    -DWITH_TURBOJPEG=FALSE
    -DWITH_SIMD=TRUE
    -DWITH_ARITH_DEC=FALSE
    -DWITH_ARITH_ENC=FALSE
    -DREQUIRE_SIMD=FALSE
  UPDATE_COMMAND ""
  # Nothing is consumed from the install tree: the archive below is the one the
  # build produces.
  INSTALL_COMMAND ""
  TEST_COMMAND ""
  BUILD_BYPRODUCTS ${JPEG_BUILD_DIR}/libjpeg.a
)

add_library(jpeg-static STATIC IMPORTED GLOBAL)
add_dependencies(jpeg-static libjpeg-turbo-project)
set_target_properties(jpeg-static PROPERTIES
  IMPORTED_LOCATION ${JPEG_BUILD_DIR}/libjpeg.a
  INTERFACE_INCLUDE_DIRECTORIES "${JPEG_SOURCE_DIR};${JPEG_BUILD_DIR}"
)

# --- our wrapper -------------------------------------------------------------
add_library(jpegpipe SHARED jpeg_pipe.c)

target_compile_options(jpegpipe PRIVATE ${JPEGPIPE_OPT_FLAGS})

target_link_libraries(jpegpipe PRIVATE jpeg-static)
