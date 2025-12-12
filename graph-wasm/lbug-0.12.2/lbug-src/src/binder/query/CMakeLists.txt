add_library(
        lbug_binder_query
        OBJECT
        bound_insert_clause.cpp
        bound_delete_clause.cpp
        bound_merge_clause.cpp
        bound_set_clause.cpp
        query_graph.cpp
        query_graph_label_analyzer.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_query>
        PARENT_SCOPE)
