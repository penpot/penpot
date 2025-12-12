add_library(lbug_binder_bind_copy
        OBJECT
        bind_copy_to.cpp
        bind_copy_from.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_bind_copy>
        PARENT_SCOPE)
