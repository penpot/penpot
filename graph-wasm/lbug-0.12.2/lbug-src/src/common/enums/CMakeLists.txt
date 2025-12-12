add_library(lbug_common_enums
        OBJECT
        accumulate_type.cpp
        path_semantic.cpp
        query_rel_type.cpp
        rel_direction.cpp
        rel_multiplicity.cpp
        scan_source_type.cpp
        table_type.cpp
        transaction_action.cpp
        drop_type.cpp
        extend_direction_util.cpp
        conflict_action.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_common_enums>
        PARENT_SCOPE)
