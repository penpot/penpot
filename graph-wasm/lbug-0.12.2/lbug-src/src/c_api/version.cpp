#include "main/version.h"

#include "c_api/helpers.h"
#include "c_api/lbug.h"

char* lbug_get_version() {
    return convertToOwnedCString(lbug::main::Version::getVersion());
}

uint64_t lbug_get_storage_version() {
    return lbug::main::Version::getStorageVersion();
}
