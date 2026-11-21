# Copyright 2026 ExsoKamabay
# SPDX-License-Identifier: Apache-2.0+
add_library(lxcore STATIC
  src/json.cpp
  src/validate.cpp
  src/window_manager.cpp
  src/desktop.cpp
  src/settings.cpp
  src/persistence.cpp
  src/search.cpp
  src/render.cpp
  src/engine.cpp
  src/archive_io.cpp
  src/archive_path.cpp
  src/archive_tar.cpp
  src/archive_zip.cpp)
target_include_directories(lxcore PUBLIC include)
target_compile_features(lxcore PUBLIC cxx_std_20)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(lxcore PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wno-sign-conversion)
endif()
# The archive code inflates and deflates with zlib: the NDK's system libz on
# Android, the distribution's zlib on the host.
if(ANDROID)
  target_link_libraries(lxcore PUBLIC z)
else()
  find_package(ZLIB REQUIRED)
  target_link_libraries(lxcore PUBLIC ZLIB::ZLIB)
endif()
