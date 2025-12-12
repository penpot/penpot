add_library(lbug_rs STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/lbug_rs.cpp
    ${LBUG_RS_BINDINGS_DIR}/sources/lbug/src/lib.rs.cc
)

target_include_directories(lbug_rs
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../include
        ${LBUG_RS_BINDINGS_DIR}/include
        ${LBUG_RS_BINDINGS_DIR}/crate
        ${LBUG_RS_BINDINGS_DIR}/sources
)

target_link_libraries(lbug_rs lbug)
