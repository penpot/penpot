add_library(
        lbug_binder_bind_expression
        OBJECT
        bind_boolean_expression.cpp
        bind_case_expression.cpp
        bind_comparison_expression.cpp
        bind_function_expression.cpp
        bind_literal_expression.cpp
        bind_null_operator_expression.cpp
        bind_parameter_expression.cpp
        bind_property_expression.cpp
        bind_subquery_expression.cpp
        bind_variable_expression.cpp
        bind_lambda_expression.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_binder_bind_expression>
        PARENT_SCOPE)
