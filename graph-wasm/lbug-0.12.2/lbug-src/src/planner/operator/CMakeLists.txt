add_subdirectory(extend)
add_subdirectory(factorization)
add_subdirectory(persistent)
add_subdirectory(scan)
add_subdirectory(simple)
add_subdirectory(sip)

add_library(lbug_planner_operator
        OBJECT
        logical_accumulate.cpp
        logical_aggregate.cpp
        logical_create_macro.cpp
        logical_cross_product.cpp
        logical_distinct.cpp
        logical_dummy_scan.cpp
        logical_dummy_sink.cpp
        logical_explain.cpp
        logical_filter.cpp
        logical_flatten.cpp
        logical_hash_join.cpp
        logical_table_function_call.cpp
        logical_intersect.cpp
        logical_limit.cpp
        logical_operator.cpp
        logical_order_by.cpp
        logical_partitioner.cpp
        logical_path_property_probe.cpp
        logical_plan.cpp
        logical_plan_util.cpp
        logical_projection.cpp
        logical_standalone_call.cpp
        logical_union.cpp
        logical_unwind.cpp
        schema.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_planner_operator>
        PARENT_SCOPE)
