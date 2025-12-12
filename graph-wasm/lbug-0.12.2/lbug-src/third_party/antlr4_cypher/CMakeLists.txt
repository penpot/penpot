if(${AUTO_UPDATE_GRAMMAR})
        add_custom_command(
                OUTPUT
                        ${CMAKE_CURRENT_SOURCE_DIR}/cypher_lexer.cpp
                        ${CMAKE_CURRENT_SOURCE_DIR}/cypher_parser.cpp
                        ${CMAKE_CURRENT_SOURCE_DIR}/include/cypher_lexer.h
                        ${CMAKE_CURRENT_SOURCE_DIR}/include/cypher_parser.h
                COMMAND cmake -D ROOT_DIR=${PROJECT_SOURCE_DIR} -P generate_grammar.cmake
                DEPENDS
                        ${PROJECT_SOURCE_DIR}/src/antlr4/Cypher.g4
                        ${PROJECT_SOURCE_DIR}/scripts/antlr4/generate_grammar.cmake
                WORKING_DIRECTORY ${PROJECT_SOURCE_DIR}/scripts/antlr4)
endif()

add_library(antlr4_cypher
        STATIC
        cypher_lexer.cpp
        cypher_parser.cpp)

target_include_directories(antlr4_cypher
        PRIVATE ../antlr4_runtime/src)

target_link_libraries(antlr4_cypher PRIVATE antlr4_runtime)
