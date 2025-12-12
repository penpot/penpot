add_library(lbug_binder_bind_read
        OBJECT
        bind_in_query_call.cpp
        bind_load_from.cpp
        bind_match.cpp
        bind_unwind.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_bind_read>
        PARENT_SCOPE)
