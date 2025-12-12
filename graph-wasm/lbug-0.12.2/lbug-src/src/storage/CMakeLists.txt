add_subdirectory(buffer_manager)
add_subdirectory(compression)
add_subdirectory(local_storage)
add_subdirectory(predicate)
add_subdirectory(index)
add_subdirectory(stats)
add_subdirectory(table)
add_subdirectory(wal)

add_library(lbug_storage
        OBJECT
        checkpointer.cpp
        database_header.cpp
        disk_array.cpp
        disk_array_collection.cpp
        file_db_id_utils.cpp
        file_handle.cpp
        free_space_manager.cpp
        optimistic_allocator.cpp
        overflow_file.cpp
        page_manager.cpp
        shadow_file.cpp
        shadow_utils.cpp
        storage_manager.cpp
        storage_utils.cpp
        storage_version_info.cpp
        undo_buffer.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_storage>
        PARENT_SCOPE)
