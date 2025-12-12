add_library(lbug_function_map
        OBJECT
        map_creation_function.cpp
        map_extract_function.cpp
        map_keys_function.cpp
        map_values_function.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_function_map>
        PARENT_SCOPE)
