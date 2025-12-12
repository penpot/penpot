add_library(lbug_function_sequence
        OBJECT
        sequence_functions.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_function_sequence>
        PARENT_SCOPE)
