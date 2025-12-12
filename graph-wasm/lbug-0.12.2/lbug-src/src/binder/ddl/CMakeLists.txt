add_library(
        lbug_binder_ddl
        OBJECT
        bound_alter_info.cpp
        property_definition.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_ddl>
        PARENT_SCOPE)
