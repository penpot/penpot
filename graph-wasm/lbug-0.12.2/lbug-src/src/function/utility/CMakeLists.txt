add_library(lbug_utility_function
        OBJECT
        coalesce.cpp
        md5.cpp
        sha256.cpp
        constant_or_null.cpp
        count_if.cpp
        error.cpp
        nullif.cpp
        typeof.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_utility_function>
        PARENT_SCOPE)
