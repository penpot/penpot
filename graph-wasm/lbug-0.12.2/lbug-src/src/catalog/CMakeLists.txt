add_subdirectory(catalog_entry)

add_library(lbug_catalog
        OBJECT
        catalog.cpp
        catalog_set.cpp
        property_definition_collection.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_catalog>
        PARENT_SCOPE)
