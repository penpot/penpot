add_subdirectory(reader)
add_subdirectory(writer/parquet)

add_library(lbug_processor_operator_persistent
        OBJECT
        batch_insert_error_handler.cpp
        node_batch_insert.cpp
        node_batch_insert_error_handler.cpp
        copy_rel_batch_insert.cpp
        rel_batch_insert.cpp
        copy_to.cpp
        delete.cpp
        delete_executor.cpp
        index_builder.cpp
        insert.cpp
        insert_executor.cpp
        merge.cpp
        set.cpp
        set_executor.cpp
)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_processor_operator_persistent>
        PARENT_SCOPE)
