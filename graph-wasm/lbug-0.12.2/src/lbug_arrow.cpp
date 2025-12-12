#include "lbug_arrow.h"

namespace lbug_arrow {

ArrowSchema query_result_get_arrow_schema(const lbug::main::QueryResult& result) {
    // Could use directly, except that we can't (yet) mark ArrowSchema as being safe to store in a
    // cxx::UniquePtr
    return *result.getArrowSchema();
}

ArrowArray query_result_get_next_arrow_chunk(lbug::main::QueryResult& result, uint64_t chunkSize) {
    return *result.getNextArrowChunk(chunkSize);
}

} // namespace lbug_arrow
