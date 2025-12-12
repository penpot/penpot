include_directories(${CMAKE_CURRENT_BINARY_DIR})

# Have to pass this down to every subdirectory, which actually adds the files.
# This doesn't affect parent directories.
add_compile_definitions(LBUG_EXPORTS)
add_compile_definitions(ANTLR4CPP_STATIC)
add_subdirectory(binder)
add_subdirectory(c_api)
add_subdirectory(catalog)
add_subdirectory(common)
add_subdirectory(expression_evaluator)
add_subdirectory(function)
add_subdirectory(graph)
add_subdirectory(main)
add_subdirectory(optimizer)
add_subdirectory(parser)
add_subdirectory(planner)
add_subdirectory(processor)
add_subdirectory(storage)
add_subdirectory(transaction)
add_subdirectory(extension)

add_library(lbug STATIC ${ALL_OBJECT_FILES})
add_library(lbug_shared SHARED ${ALL_OBJECT_FILES})

set(LBUG_LIBRARIES antlr4_cypher antlr4_runtime brotlidec brotlicommon fast_float utf8proc re2 fastpfor parquet snappy thrift yyjson zstd miniz mbedtls lz4 roaring_bitmap simsimd)
if (NOT __SINGLE_THREADED__)
        set(LBUG_LIBRARIES ${LBUG_LIBRARIES} Threads::Threads)
endif()
if(NOT WIN32)
        set(LBUG_LIBRARIES dl ${LBUG_LIBRARIES})
endif()
# Seems to be needed for clang on linux only
# for compiling std::atomic<T>::compare_exchange_weak
if ((NOT APPLE AND CMAKE_CXX_COMPILER_ID STREQUAL "Clang") AND NOT __WASM__ AND NOT __SINGLE_THREADED__)
        set(LBUG_LIBRARIES atomic ${LBUG_LIBRARIES})
endif()
if (ENABLE_BACKTRACES)
        set(LBUG_LIBRARIES ${LBUG_LIBRARIES} cpptrace::cpptrace)
endif()
target_link_libraries(lbug PUBLIC ${LBUG_LIBRARIES})
target_link_libraries(lbug_shared PUBLIC ${LBUG_LIBRARIES})
unset(LBUG_LIBRARIES)

set(LBUG_INCLUDES $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include> $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}> ${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/include/c_api ${CMAKE_CURRENT_BINARY_DIR}/../src/include)
target_include_directories(lbug PUBLIC ${LBUG_INCLUDES})
target_include_directories(lbug_shared PUBLIC ${LBUG_INCLUDES})
unset(LBUG_INCLUDES)

if(WIN32)
        # Anything linking against the static library must not use dllimport.
        target_compile_definitions(lbug INTERFACE LBUG_STATIC_DEFINE)
endif()

if(NOT WIN32)
        set_target_properties(lbug_shared PROPERTIES OUTPUT_NAME lbug)
endif()

install(TARGETS lbug lbug_shared)

if(${BUILD_SINGLE_FILE_HEADER})
        # Create a command to generate lbug.hpp, and then create a target that is
        # always built that depends on it. This allows our generator to detect when
        # exactly to build lbug.hpp, while still building the target by default.
        find_package(Python3 3.9...4 REQUIRED)
        add_custom_command(
                OUTPUT lbug.hpp
                COMMAND
                        ${Python3_EXECUTABLE} ${PROJECT_SOURCE_DIR}/scripts/collect-single-file-header.py ${CMAKE_CURRENT_BINARY_DIR}/..
                DEPENDS
                        ${PROJECT_SOURCE_DIR}/scripts/collect-single-file-header.py lbug_shared)
        add_custom_target(single_file_header ALL DEPENDS lbug.hpp)
endif()

install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/include/c_api/lbug.h TYPE INCLUDE)

if(${BUILD_SINGLE_FILE_HEADER})
        install(FILES ${CMAKE_CURRENT_BINARY_DIR}/lbug.hpp  TYPE INCLUDE)
endif()
