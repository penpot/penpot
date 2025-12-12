add_library(
        lbug_binder_expression
        OBJECT
        aggregate_function_expression.cpp
        case_expression.cpp
        expression.cpp
        expression_util.cpp
        literal_expression.cpp
        node_expression.cpp
        node_rel_expression.cpp
        parameter_expression.cpp
        property_expression.cpp
        rel_expression.cpp
        scalar_function_expression.cpp
        variable_expression.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_expression>
        PARENT_SCOPE)
