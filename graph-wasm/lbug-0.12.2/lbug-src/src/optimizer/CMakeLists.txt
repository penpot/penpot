add_library(lbug_optimizer
        OBJECT
        acc_hash_join_optimizer.cpp
        agg_key_dependency_optimizer.cpp
        cardinality_updater.cpp
        correlated_subquery_unnest_solver.cpp
        factorization_rewriter.cpp
        filter_push_down_optimizer.cpp
        logical_operator_collector.cpp
        logical_operator_visitor.cpp
        optimizer.cpp
        projection_push_down_optimizer.cpp
        schema_populator.cpp
        remove_factorization_rewriter.cpp
        remove_unnecessary_join_optimizer.cpp
        top_k_optimizer.cpp
        limit_push_down_optimizer.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_optimizer>
        PARENT_SCOPE)
