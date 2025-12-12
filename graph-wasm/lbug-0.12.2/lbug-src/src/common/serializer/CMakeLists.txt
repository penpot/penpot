add_library(lbug_common_serializer
        OBJECT
        buffer_writer.cpp
        buffered_file.cpp
        deserializer.cpp
        in_mem_file_writer.cpp
        serializer.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_common_serializer>
        PARENT_SCOPE)
