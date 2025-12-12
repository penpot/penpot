cmake_minimum_required(VERSION 3.15)

project(Lbug VERSION 0.12.2 LANGUAGES CXX C)

option(SINGLE_THREADED "Single-threaded mode" FALSE)
if(SINGLE_THREADED)
    set(__SINGLE_THREADED__ TRUE)
    add_compile_definitions(__SINGLE_THREADED__)
    message(STATUS "Single-threaded mode is enabled")
else()
    message(STATUS "Multi-threaded mode is enabled: CMAKE_BUILD_PARALLEL_LEVEL=$ENV{CMAKE_BUILD_PARALLEL_LEVEL}")
    find_package(Threads REQUIRED)
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED TRUE)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_EXPORT_COMPILE_COMMANDS TRUE)
set(CMAKE_FIND_PACKAGE_RESOLVE_SYMLINKS TRUE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
# On Linux, symbols in executables are not accessible by loaded shared libraries (e.g. via dlopen(3)). However, we need to export public symbols in executables so that extensions can access public symbols. This enables that behaviour.
set(CMAKE_ENABLE_EXPORTS TRUE)

option(ENABLE_WERROR "Treat all warnings as errors" FALSE)
if(ENABLE_WERROR)
    if (CMAKE_VERSION VERSION_GREATER "3.24.0" OR CMAKE_VERSION VERSION_EQUAL "3.24.0")
        set(CMAKE_COMPILE_WARNING_AS_ERROR TRUE)
    elseif (MSVC)
        add_compile_options(\WX)
    else ()
        add_compile_options(-Werror)
    endif()
endif()

# Detect OS and architecture, copied from DuckDB
set(OS_NAME "unknown")
set(OS_ARCH "amd64")

string(REGEX MATCH "(arm64|aarch64)" IS_ARM "${CMAKE_SYSTEM_PROCESSOR}")
if(IS_ARM)
  set(OS_ARCH "arm64")
elseif(FORCE_32_BIT)
  set(OS_ARCH "i386")
endif()

if(APPLE)
  set(OS_NAME "osx")
endif()
if(WIN32)
  set(OS_NAME "windows")
endif()
if(UNIX AND NOT APPLE)
  set(OS_NAME "linux") # sorry BSD
endif()

if(CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(STATUS "64-bit architecture detected")
    add_compile_definitions(__64BIT__)
elseif(CMAKE_SIZEOF_VOID_P EQUAL 4)
    message(STATUS "32-bit architecture detected")
    add_compile_definitions(__32BIT__)
    set(__32BIT__ TRUE)
endif()

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

if(DEFINED ENV{PYBIND11_PYTHON_VERSION})
    set(PYBIND11_PYTHON_VERSION $ENV{PYBIND11_PYTHON_VERSION})
endif()

if(DEFINED ENV{PYTHON_EXECUTABLE})
    set(PYTHON_EXECUTABLE $ENV{PYTHON_EXECUTABLE})
endif()

find_program(CCACHE_PROGRAM ccache)
if (CCACHE_PROGRAM)
    set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    message(STATUS "ccache found and enabled")
else ()
    find_program(CCACHE_PROGRAM sccache)
    if (CCACHE_PROGRAM)
        set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
        set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
        message(STATUS "sccache found and enabled")
    endif ()
endif ()

set(INSTALL_LIB_DIR
        lib
        CACHE PATH "Installation directory for libraries")
set(INSTALL_BIN_DIR
        bin
        CACHE PATH "Installation directory for executables")
set(INSTALL_INCLUDE_DIR
        include
        CACHE PATH "Installation directory for header files")
set(INSTALL_CMAKE_DIR
        ${DEF_INSTALL_CMAKE_DIR}
        CACHE PATH "Installation directory for CMake files")

option(ENABLE_ADDRESS_SANITIZER "Enable address sanitizer." FALSE)
option(ENABLE_THREAD_SANITIZER "Enable thread sanitizer." FALSE)
option(ENABLE_UBSAN "Enable undefined behavior sanitizer." FALSE)
option(ENABLE_RUNTIME_CHECKS "Enable runtime coherency checks (e.g. asserts)" FALSE)
option(ENABLE_LTO "Enable Link-Time Optimization" FALSE)
option(ENABLE_MALLOC_BUFFER_MANAGER "Enable Buffer manager using malloc. Default option for webassembly" OFF)

option(LBUG_DEFAULT_REL_STORAGE_DIRECTION "Only store fwd direction in rel tables by default." BOTH)
if(NOT LBUG_DEFAULT_REL_STORAGE_DIRECTION)
    set(LBUG_DEFAULT_REL_STORAGE_DIRECTION BOTH)
endif()
set(LBUG_DEFAULT_REL_STORAGE_DIRECTION ${LBUG_DEFAULT_REL_STORAGE_DIRECTION}_REL_STORAGE)
option(LBUG_PAGE_SIZE_LOG2 "Log2 of the page size." 12)
if(NOT LBUG_PAGE_SIZE_LOG2)
    set(LBUG_PAGE_SIZE_LOG2 12)
endif()
message(STATUS "LBUG_PAGE_SIZE_LOG2: ${LBUG_PAGE_SIZE_LOG2}")
option(LBUG_VECTOR_CAPACITY_LOG2 "Log2 of the vector capacity." 11)
if(NOT LBUG_VECTOR_CAPACITY_LOG2)
    set(LBUG_VECTOR_CAPACITY_LOG2 11)
endif()
message(STATUS "LBUG_VECTOR_CAPACITY_LOG2: ${LBUG_VECTOR_CAPACITY_LOG2}")

# 64 * 2048 nodes per group
option(LBUG_NODE_GROUP_SIZE_LOG2 "Log2 of the vector capacity." 17)
if(NOT LBUG_NODE_GROUP_SIZE_LOG2)
    set(LBUG_NODE_GROUP_SIZE_LOG2 17)
endif()
message(STATUS "LBUG_NODE_GROUP_SIZE_LOG2: ${LBUG_NODE_GROUP_SIZE_LOG2}")

option(LBUG_MAX_SEGMENT_SIZE_LOG2 "Log2 of the maximum segment size in bytes." 18)
if(NOT LBUG_MAX_SEGMENT_SIZE_LOG2)
    set(LBUG_MAX_SEGMENT_SIZE_LOG2 18)
endif()
message(STATUS "LBUG_MAX_SEGMENT_SIZE_LOG2: ${LBUG_MAX_SEGMENT_SIZE_LOG2}")

configure_file(${CMAKE_CURRENT_SOURCE_DIR}/cmake/templates/system_config.h.in ${CMAKE_CURRENT_BINARY_DIR}/src/include/common/system_config.h @ONLY)

include(CheckCXXSymbolExists)
check_cxx_symbol_exists(F_FULLFSYNC "fcntl.h" HAS_FULLFSYNC)
check_cxx_symbol_exists(fdatasync "unistd.h" HAS_FDATASYNC)
if(HAS_FULLFSYNC)
    message(STATUS "✓ F_FULLFSYNC will be used on this platform")
    add_compile_definitions(HAS_FULLFSYNC)
else()
    message(STATUS "✗ F_FULLFSYNC not available")
endif()

if(HAS_FDATASYNC)
    message(STATUS "✓ fdatasync will be used on this platform")
    add_compile_definitions(HAS_FDATASYNC)
else()
    message(STATUS "✗ fdatasync not available, using fsync fallback")
endif()

if(MSVC)
    # Required for M_PI on Windows
    add_compile_definitions(_USE_MATH_DEFINES)
    add_compile_definitions(NOMINMAX)
    add_compile_definitions(SERD_STATIC)
    # This is a workaround for regex oom issue on windows in gtest.
    add_compile_definitions(_REGEX_MAX_STACK_COUNT=0)
    add_compile_definitions(_REGEX_MAX_COMPLEXITY_COUNT=0)
    # Disable constexpr mutex constructor to avoid compatibility issues with
    # older versions of the MSVC runtime library
    # See: https://github.com/microsoft/STL/wiki/Changelog#vs-2022-1710
    add_compile_definitions(_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
    # TODO (bmwinger): Figure out if this can be set automatically by cmake,
    # or at least better integrated with user-specified options
    # For now, hardcode _AMD64_
    # CMAKE_GENERATOR_PLATFORM can be used for visual studio builds, but not for ninja
    add_compile_definitions(_AMD64_)
    # Non-english windows system may use other encodings other than utf-8 (e.g. Chinese use GBK).
    add_compile_options("/utf-8")
    # Enables support for custom hardware exception handling
    add_compile_options("/EHa")
    # Reduces the size of the static library by roughly 1/2
    add_compile_options("/Zc:inline")
    # Disable type conversion warnings
    add_compile_options(/wd4244 /wd4267)
    # Remove the default to avoid warnings
    STRING(REPLACE "/EHsc" "" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    STRING(REPLACE "/EHs" "" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    # Store all libraries and binaries in the same directory so that lbug_shared.dll is found at runtime
    set(LIBRARY_OUTPUT_PATH "${CMAKE_BINARY_DIR}/src")
    set(EXECUTABLE_OUTPUT_PATH "${CMAKE_BINARY_DIR}/src")
    # This is a workaround for regex stackoverflow issue on windows in gtest.
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} /STACK:8388608")

    string(REGEX REPLACE "/W[3|4]" "/w" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    add_compile_options($<$<CONFIG:Release>:/W0>)
else()
    add_compile_options(-Wall -Wextra)
    # Disable warnings for unknown pragmas, which is used by several third-party libraries
    add_compile_options(-Wno-unknown-pragmas)
endif()

if(${BUILD_WASM})
    if(NOT __SINGLE_THREADED__)
        add_compile_options(-pthread)
        add_link_options(-pthread)
        add_link_options(-sPTHREAD_POOL_SIZE=8)
    endif()
    add_compile_options(-s DISABLE_EXCEPTION_CATCHING=0)
    add_link_options(-sSTACK_SIZE=4MB)
    add_link_options(-sASSERTIONS=1)
    add_link_options(-lembind)
    add_link_options(-sWASM_BIGINT)

    if(BUILD_TESTS OR BUILD_EXTENSION_TESTS)
        add_link_options(-sINITIAL_MEMORY=3892MB)
        add_link_options(-sNODERAWFS=1)
    elseif(WASM_NODEFS)
        add_link_options(-sNODERAWFS=1)
        add_link_options(-sALLOW_MEMORY_GROWTH=1)
        add_link_options(-sMODULARIZE=1)
        add_link_options(-sEXPORTED_RUNTIME_METHODS=FS,wasmMemory)
        add_link_options(-sEXPORT_NAME=lbug)
        add_link_options(-sMAXIMUM_MEMORY=4GB)
    else()
        add_link_options(-sSINGLE_FILE=1)
        add_link_options(-sALLOW_MEMORY_GROWTH=1)
        add_link_options(-sMODULARIZE=1)
        add_link_options(-sEXPORTED_RUNTIME_METHODS=FS,wasmMemory)
        add_link_options(-lidbfs.js)
        add_link_options(-lworkerfs.js)
        add_link_options(-sEXPORT_NAME=lbug)
        add_link_options(-sMAXIMUM_MEMORY=4GB)
    endif()
    set(__WASM__ TRUE)
    add_compile_options(-fexceptions)
    add_link_options(-s DISABLE_EXCEPTION_CATCHING=0)
    add_link_options(-fexceptions)
    add_compile_definitions(__WASM__)
    set(ENABLE_MALLOC_BUFFER_MANAGER ON)
endif()

if(${BUILD_SWIFT})
    add_compile_definitions(__SWIFT__)
    set(ENABLE_MALLOC_BUFFER_MANAGER ON)
endif()

if (${ENABLE_MALLOC_BUFFER_MANAGER})
    add_compile_definitions(BM_MALLOC)
endif()

if(ANDROID_ABI)
    message(STATUS "Android ABI detected: ${ANDROID_ABI}")
    add_compile_definitions(__ANDROID__)
    set(__ANDROID__ TRUE)
endif()

if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
    add_compile_options(-Wno-restrict) # no restrict until https://gcc.gnu.org/bugzilla/show_bug.cgi?id=105651 is fixed
endif()

if(${ENABLE_THREAD_SANITIZER} AND (NOT __SINGLE_THREADED__))
    if(MSVC)
        message(FATAL_ERROR "Thread sanitizer is not supported on MSVC")
    else()
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fsanitize=thread -fno-omit-frame-pointer")
    endif()
endif()
if(${ENABLE_ADDRESS_SANITIZER})
    if(MSVC)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /fsanitize=address")
    else()
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fsanitize=address -fno-omit-frame-pointer")
    endif()
endif()
if(${ENABLE_UBSAN})
    if(MSVC)
        message(FATAL_ERROR "Undefined behavior sanitizer is not supported on MSVC")
    else()
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fsanitize=undefined -fno-omit-frame-pointer")
    endif()
endif()

if(${ENABLE_RUNTIME_CHECKS})
    add_compile_definitions(LBUG_RUNTIME_CHECKS)
endif()

if (${ENABLE_DESER_DEBUG})
    add_compile_definitions(LBUG_DESER_DEBUG)
endif()

if(${ENABLE_LTO})
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)
endif()

option(AUTO_UPDATE_GRAMMAR "Automatically regenerate C++ grammar files on change." TRUE)
option(BUILD_BENCHMARK "Build benchmarks." FALSE)
option(BUILD_EXTENSIONS "Semicolon-separated list of extensions to build." "")
option(BUILD_EXAMPLES "Build examples." FALSE)
option(BUILD_JAVA "Build Java API." FALSE)
option(BUILD_NODEJS "Build NodeJS API." FALSE)
option(BUILD_PYTHON "Build Python API." FALSE)
option(BUILD_SHELL "Build Interactive Shell" TRUE)
option(BUILD_SINGLE_FILE_HEADER "Build single file header. Requires Python >= 3.9." TRUE)
option(BUILD_TESTS "Build C++ tests." FALSE)
option(BUILD_EXTENSION_TESTS "Build C++ extension tests." FALSE)
option(BUILD_LBUG "Build Lbug." TRUE)
option(ENABLE_BACKTRACES "Enable backtrace printing for exceptions and segfaults" FALSE)
option(USE_STD_FORMAT "Use std::format instead of a custom formatter." FALSE)
option(PREFER_SYSTEM_DEPS "Only download certain deps if not found on the system" TRUE)

option(BUILD_LCOV "Build coverage report." FALSE)
if(${BUILD_LCOV})
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fprofile-arcs -ftest-coverage")
endif()

if (ENABLE_BACKTRACES)
    set(DOWNLOAD_CPPTRACE TRUE)
    if(${PREFER_SYSTEM_DEPS})
        find_package(cpptrace QUIET)
        if(cpptrace_FOUND)
            message(STATUS "Using system cpptrace")
            set(DOWNLOAD_CPPTRACE FALSE)
        endif()
    endif()
    if(${DOWNLOAD_CPPTRACE})
        message(STATUS "Fetching cpptrace from GitHub...")
        include(FetchContent)
        FetchContent_Declare(
            cpptrace
            GIT_REPOSITORY https://github.com/jeremy-rifkin/cpptrace.git
            GIT_TAG        v0.8.3
            GIT_SHALLOW    TRUE
        )
        FetchContent_MakeAvailable(cpptrace)
    endif()
    add_compile_definitions(LBUG_BACKTRACE)
endif()

if (USE_STD_FORMAT)
    add_compile_definitions(USE_STD_FORMAT)
endif()

function(add_lbug_test TEST_NAME)
    set(SRCS ${ARGN})
    add_executable(${TEST_NAME} ${SRCS})
    target_link_libraries(${TEST_NAME} PRIVATE test_helper test_runner graph_test)
    if (ENABLE_BACKTRACES)
        target_link_libraries(${TEST_NAME} PRIVATE register_backtrace_signal_handler)
    endif()
    target_include_directories(${TEST_NAME} PRIVATE ${PROJECT_SOURCE_DIR}/test/include)
    include(GoogleTest)

    if (TEST_NAME STREQUAL "e2e_test")
        gtest_discover_tests(${TEST_NAME}
            DISCOVERY_TIMEOUT 600
            DISCOVERY_MODE PRE_TEST
            TEST_PREFIX e2e_test_
        )
    else()
        gtest_discover_tests(${TEST_NAME}
            DISCOVERY_TIMEOUT 600
            DISCOVERY_MODE PRE_TEST
        )
    endif()
endfunction()

function(add_lbug_api_test TEST_NAME)
    set(SRCS ${ARGN})
    add_executable(${TEST_NAME} ${SRCS})
    target_link_libraries(${TEST_NAME} PRIVATE api_graph_test api_test_helper)
    if (ENABLE_BACKTRACES)
        target_link_libraries(${TEST_NAME} PRIVATE register_backtrace_signal_handler)
    endif()
    target_include_directories(${TEST_NAME} PRIVATE ${PROJECT_SOURCE_DIR}/test/include)
    include(GoogleTest)
    gtest_discover_tests(${TEST_NAME})
endfunction()

# Windows doesn't support dynamic lookup, so we have to link extensions against lbug.
if (MSVC AND (NOT BUILD_EXTENSIONS EQUAL ""))
    set(BUILD_LBUG TRUE)
endif ()

include_directories(third_party/antlr4_cypher/include)
include_directories(third_party/antlr4_runtime/src)
include_directories(third_party/brotli/c/include)
include_directories(third_party/fast_float/include)
include_directories(third_party/mbedtls/include)
include_directories(third_party/parquet)
include_directories(third_party/snappy)
include_directories(third_party/thrift)
include_directories(third_party/miniz)
include_directories(third_party/nlohmann_json)
include_directories(third_party/pybind11/include)
include_directories(third_party/pyparse)
include_directories(third_party/re2/include)
include_directories(third_party/alp/include)
if (${BUILD_TESTS} OR ${BUILD_EXTENSION_TESTS})
    include_directories(third_party/spdlog)
elseif (${BUILD_BENCHMARK})
    include_directories(third_party/spdlog)
endif ()
include_directories(third_party/utf8proc/include)
include_directories(third_party/zstd/include)
include_directories(third_party/httplib)
include_directories(third_party/pcg)
include_directories(third_party/lz4)
include_directories(third_party/roaring_bitmap)
# Use SYSTEM to suppress warnings from simsimd
include_directories(SYSTEM third_party/simsimd/include)

add_subdirectory(third_party)

add_definitions(-DLBUG_ROOT_DIRECTORY="${PROJECT_SOURCE_DIR}")
add_definitions(-DLBUG_CMAKE_VERSION="${CMAKE_PROJECT_VERSION}")
add_definitions(-DLBUG_EXTENSION_VERSION="0.12.0")

if(BUILD_LBUG)
include_directories(
    src/include
    ${CMAKE_CURRENT_BINARY_DIR}/src/include
)
endif()

if (EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/extension/CMakeLists.txt")
    add_subdirectory(extension)
endif ()

if(BUILD_LBUG)

add_subdirectory(src)

# Link extensions which require static linking.
foreach(ext IN LISTS STATICALLY_LINKED_EXTENSIONS)
    if (${BUILD_EXTENSION_TESTS})
        add_compile_definitions(__STATIC_LINK_EXTENSION_TEST__)
    endif ()
    target_link_libraries(lbug PRIVATE "lbug_${ext}_static_extension")
    target_link_libraries(lbug_shared PRIVATE "lbug_${ext}_static_extension")
endforeach()

if (${BUILD_TESTS} OR ${BUILD_EXTENSION_TESTS})
    add_subdirectory(test)
elseif (${BUILD_BENCHMARK})
    add_subdirectory(test/test_helper)
endif ()
add_subdirectory(tools)
endif ()

if (${BUILD_EXAMPLES})
    add_subdirectory(examples/c)
    add_subdirectory(examples/cpp)
endif()
