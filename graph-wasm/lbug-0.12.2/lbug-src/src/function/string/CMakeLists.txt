add_library(lbug_string_function
        OBJECT
        concat_ws.cpp
        string_split_function.cpp
        init_cap_function.cpp
        levenshtein_function.cpp
        split_part.cpp
        regex_full_match_function.cpp
        regex_replace_function.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_string_function>
        PARENT_SCOPE)
