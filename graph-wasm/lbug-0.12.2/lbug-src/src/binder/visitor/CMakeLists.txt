add_library(
        lbug_binder_visitor
        OBJECT
        confidential_statement_analyzer.cpp
        default_type_solver.cpp
        property_collector.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_visitor>
        PARENT_SCOPE)
