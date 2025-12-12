add_library(lbug_planner_scan
        OBJECT
        logical_expressions_scan.cpp
        logical_index_look_up.cpp
        logical_scan_node_table.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_planner_scan>
        PARENT_SCOPE)
