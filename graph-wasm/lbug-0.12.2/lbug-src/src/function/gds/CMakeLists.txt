add_library(lbug_function_algorithm
        OBJECT
        asp_destinations.cpp
        asp_paths.cpp
        awsp_paths.cpp
        bfs_graph.cpp
        frontier_morsel.cpp
        gds.cpp
        gds_frontier.cpp
        gds_state.cpp
        gds_task.cpp
        gds_utils.cpp
        output_writer.cpp
        rec_joins.cpp
        ssp_destinations.cpp
        ssp_paths.cpp
        variable_length_path.cpp
        wsp_destinations.cpp
        wsp_paths.cpp
        )

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_function_algorithm>
        PARENT_SCOPE)
