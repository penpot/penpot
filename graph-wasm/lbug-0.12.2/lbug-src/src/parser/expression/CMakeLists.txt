add_library(lbug_parser_expression
        OBJECT
        parsed_case_expression.cpp
        parsed_expression.cpp
        parsed_expression_visitor.cpp
        parsed_function_expression.cpp
        parsed_property_expression.cpp
        parsed_variable_expression.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_parser_expression>
        PARENT_SCOPE)
