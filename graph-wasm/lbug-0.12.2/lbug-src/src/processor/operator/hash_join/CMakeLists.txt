add_library(lbug_processor_operator_hash_join
        OBJECT
        hash_join_build.cpp
        hash_join_probe.cpp
        join_hash_table.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_processor_operator_hash_join>
        PARENT_SCOPE)
