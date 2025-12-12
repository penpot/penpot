add_library(lbug_function_struct
        OBJECT
        struct_extract_function.cpp
        struct_pack_function.cpp
        keys_function.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_function_struct>
        PARENT_SCOPE)
