add_library(lbug_c_api
        OBJECT
        connection.cpp
        database.cpp
        data_type.cpp
        helpers.cpp
        flat_tuple.cpp
        prepared_statement.cpp
        query_result.cpp
        query_summary.cpp
        value.cpp
        version.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_c_api>
        PARENT_SCOPE)
