add_library(lbug_timestamp_function
        OBJECT
        to_epoch_ms.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_timestamp_function>
        PARENT_SCOPE)
