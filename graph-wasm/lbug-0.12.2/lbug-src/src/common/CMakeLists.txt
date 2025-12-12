add_subdirectory(arrow)
add_subdirectory(copier_config)
add_subdirectory(data_chunk)
add_subdirectory(enums)
add_subdirectory(exception)
add_subdirectory(serializer)
add_subdirectory(signal)
add_subdirectory(task_system)
add_subdirectory(types)
add_subdirectory(vector)
add_subdirectory(file_system)

add_library(lbug_common
        OBJECT
        case_insensitive_map.cpp
        checksum.cpp
        constants.cpp
        database_lifecycle_manager.cpp
        expression_type.cpp
        in_mem_overflow_buffer.cpp
        mask.cpp
        md5.cpp
        metric.cpp
        null_mask.cpp
        profiler.cpp
        random_engine.cpp
        roaring_mask.cpp
        sha256.cpp
        string_utils.cpp
        system_message.cpp
        type_utils.cpp
        utils.cpp
        windows_utils.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_common>
        PARENT_SCOPE)
