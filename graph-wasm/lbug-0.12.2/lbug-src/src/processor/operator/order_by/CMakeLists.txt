add_library(lbug_processor_operator_order_by
        OBJECT
        key_block_merger.cpp
        order_by.cpp
        order_by_key_encoder.cpp
        order_by_merge.cpp
        order_by_scan.cpp
        radix_sort.cpp
        sort_state.cpp
        top_k.cpp
        top_k_scanner.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_processor_operator_order_by>
        PARENT_SCOPE)
