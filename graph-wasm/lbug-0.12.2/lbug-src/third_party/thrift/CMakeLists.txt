add_library(thrift
        STATIC
        protocol/TProtocol.cpp
        transport/TTransportException.cpp
        transport/TBufferTransports.cpp)

target_include_directories(thrift PUBLIC ./include)
