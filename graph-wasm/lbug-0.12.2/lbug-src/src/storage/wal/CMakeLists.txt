add_library(lbug_storage_wal
        OBJECT
        checksum_reader.cpp
        checksum_writer.cpp
        local_wal.cpp
        wal.cpp
        wal_record.cpp
        wal_replayer.cpp)

set(ALL_OBJECT_FILES
        ${ALL_OBJECT_FILES} $<TARGET_OBJECTS:lbug_storage_wal>
        PARENT_SCOPE)
