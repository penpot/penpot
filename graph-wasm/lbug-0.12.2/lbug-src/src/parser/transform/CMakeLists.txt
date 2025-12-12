add_library(lbug_parser_transform
        OBJECT
        transform_copy.cpp
        transform_ddl.cpp
        transform_expression.cpp
        transform_graph_pattern.cpp
        transform_macro.cpp
        transform_projection.cpp
        transform_query.cpp
        transform_reading_clause.cpp
        transform_standalone_call.cpp
        transform_transaction.cpp
        transform_updating_clause.cpp
        transform_extension.cpp
        transform_port_db.cpp
        transform_attach_database.cpp
        transform_detach_database.cpp
        transform_use_database.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_parser_transform>
        PARENT_SCOPE)
