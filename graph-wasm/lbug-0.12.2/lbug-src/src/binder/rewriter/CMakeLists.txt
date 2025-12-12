add_library(
        lbug_binder_rewriter
        OBJECT
        match_clause_pattern_label_rewriter.cpp
        normalized_query_part_match_rewriter.cpp
        with_clause_projection_rewriter.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_rewriter>
        PARENT_SCOPE)
