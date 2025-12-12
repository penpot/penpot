add_library(
        lbug_parser_visitor
        OBJECT
        standalone_call_rewriter.cpp
        statement_read_write_analyzer.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_parser_visitor>
        PARENT_SCOPE)
