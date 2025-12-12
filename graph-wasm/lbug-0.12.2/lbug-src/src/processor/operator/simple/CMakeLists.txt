add_library(lbug_processor_operator_simple
        OBJECT
        attach_database.cpp
        detach_database.cpp
        install_extension.cpp
        load_extension.cpp
        import_db.cpp
        export_db.cpp
        use_database.cpp
        uninstall_extension.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_processor_operator_simple>
        PARENT_SCOPE)
