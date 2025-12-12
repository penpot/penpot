set(INCLUDES "")
set(LOAD_LINKED_EXTENSION "")

foreach (EXT_NAME IN LISTS STATICALLY_LINKED_EXTENSIONS)
    string(TOLOWER "${EXT_NAME}" EXT_NAME_LOWER)
    # Convert to camel case
    string(REPLACE "_" ";" EXT_NAME_SPLIT ${EXT_NAME})
    set(EXT_NAME_CAMELCASE "")
    foreach (EXT_NAME_PART IN LISTS EXT_NAME_SPLIT)
        string(SUBSTRING ${EXT_NAME_PART} 0 1 FIRST_LETTER)
        string(SUBSTRING ${EXT_NAME_PART} 1 -1 REMAINDER)
        string(TOUPPER ${FIRST_LETTER} FIRST_LETTER)
        set(EXT_NAME_CAMELCASE "${EXT_NAME_CAMELCASE}${FIRST_LETTER}${REMAINDER}")
    endforeach ()

    set(LOAD_LINKED_EXTENSION "${LOAD_LINKED_EXTENSION}\
{
        ${EXT_NAME_LOWER}_extension::${EXT_NAME_CAMELCASE}Extension extension{};
        extension.load(context);
        loadedExtensions.push_back(LoadedExtension(${EXT_NAME_LOWER}_extension::${EXT_NAME_CAMELCASE}Extension::EXTENSION_NAME, \" \",
            ExtensionSource::STATIC_LINKED));
    }\n")
    include_directories(${PROJECT_SOURCE_DIR}/extension/${EXT_NAME}/src/include/main)
    set(INCLUDES "${INCLUDES}#include \"${EXT_NAME_LOWER}_extension.h\"\n")
endforeach ()

configure_file(
        "generated_extension_loader.h.in"
        "${CMAKE_CURRENT_BINARY_DIR}/codegen/include/generated_extension_loader.h"
        @ONLY
)

configure_file(
        "generated_extension_loader.cpp.in"
        "${CMAKE_CURRENT_BINARY_DIR}/codegen/generated_extension_loader.cpp"
        @ONLY
)

set(GENERATED_CPP_FILE
        ${CMAKE_CURRENT_BINARY_DIR}/codegen/generated_extension_loader.cpp)

include_directories(
        "${CMAKE_CURRENT_BINARY_DIR}/codegen/include/"
        ${PROJECT_SOURCE_DIR}/third_party/httplib
)

add_library(lbug_generated_extension_loader OBJECT ${GENERATED_CPP_FILE})

# Both the lbug source and httpfs extension includes the httplib which is a header
# only library. Httpfs requires the openssl function to be enabled in httplib, so
# we also have to enable httplib there if httpfs is static linked.
if ("httpfs" IN_LIST STATICALLY_LINKED_EXTENSIONS)
    add_compile_definitions(CPPHTTPLIB_OPENSSL_SUPPORT)
    include_directories(${OPENSSL_INCLUDE_DIR})
endif ()

add_library(lbug_extension
        OBJECT
        catalog_extension.cpp
        extension.cpp
        extension_entries.cpp
        extension_installer.cpp
        extension_manager.cpp
        loaded_extension.cpp
)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_extension>
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_generated_extension_loader>
        PARENT_SCOPE)
