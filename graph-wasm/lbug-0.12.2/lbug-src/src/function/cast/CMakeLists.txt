add_library(lbug_function_cast
        OBJECT
        cast_array.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_function_cast>
        PARENT_SCOPE)
