add_subdirectory(query_result)

add_library(lbug_main
        OBJECT
        attached_database.cpp
        client_context.cpp
        connection.cpp
        database.cpp
        database_manager.cpp
        plan_printer.cpp
        prepared_statement.cpp
        prepared_statement_manager.cpp
        query_result.cpp
        query_summary.cpp
        storage_driver.cpp
        version.cpp
        db_config.cpp
        settings.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_main>
        PARENT_SCOPE)
