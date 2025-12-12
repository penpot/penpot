add_library(lbug_common_types_value
        OBJECT
        nested.cpp
        node.cpp
        recursive_rel.cpp
        rel.cpp
        value.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_common_types_value>
        PARENT_SCOPE)
