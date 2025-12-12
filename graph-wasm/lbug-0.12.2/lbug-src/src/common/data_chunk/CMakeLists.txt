add_library(lbug_common_data_chunk
        OBJECT
        data_chunk.cpp
        data_chunk_collection.cpp
        data_chunk_state.cpp
        sel_vector.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_common_data_chunk>
        PARENT_SCOPE)
